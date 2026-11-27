package com.bitdroid.swap

import android.content.Context
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Talks to RootMain (running as root through Magisk's su). */
class RootClient private constructor(private val proc: Process) : Closeable {

    var onLog: (String) -> Unit = {}
    var onProgress: (Float) -> Unit = {}

    private val reader = proc.inputStream.bufferedReader()
    private val writer = BufferedOutputStream(proc.outputStream, 1 shl 20)

    enum class Root { OK, NO_SU, DENIED }

    companion object {
        private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "/sbin/su", "/su/bin/su")

        fun findSu(): String? = SU_PATHS.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
            ?: runCatching {
                val p = ProcessBuilder("sh", "-c", "command -v su").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 && it.isNotEmpty() }
            }.getOrNull()

        /** Runs `su -c id`. Magisk shows its "allow?" prompt the first time. */
        fun check(): Root {
            val su = findSu() ?: return Root.NO_SU
            return try {
                val p = ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start()
                p.outputStream.close()
                val ok = p.waitFor(60, TimeUnit.SECONDS)
                val text = if (ok) p.inputStream.bufferedReader().readText() else { p.destroy(); "" }
                if (text.contains("uid=0")) Root.OK else Root.DENIED
            } catch (e: Exception) {
                Root.DENIED
            }
        }

        fun start(ctx: Context, log: (String) -> Unit): RootClient {
            val su = findSu() ?: throw IOException("No su. This phone isn't rooted.")
            val apk = ctx.applicationInfo.sourceDir
            val cmd = "CLASSPATH='$apk' exec /system/bin/app_process /system/bin com.bitdroid.swap.RootMain"
            val p = ProcessBuilder(su, "-c", cmd).start()
            // stderr: just log it
            Thread {
                runCatching { p.errorStream.bufferedReader().forEachLine { if (it.isNotBlank()) log("   root: $it") } }
            }.apply { isDaemon = true }.start()
            val c = RootClient(p)
            while (true) {
                val line = c.reader.readLine() ?: throw IOException("The root helper didn't start (Magisk said no?).")
                if (line.startsWith("@@READY")) break
                if (line.startsWith("@@ERR")) throw IOException(line.removePrefix("@@ERR").trim())
                log("   root: $line")
            }
            return c
        }
    }

    @Synchronized
    fun cmd(line: String, data: ByteArray? = null, off: Int = 0, len: Int = data?.size ?: 0): String {
        writer.write((line + "\n").toByteArray())
        if (data != null) writer.write(data, off, len)
        writer.flush()
        while (true) {
            val r = reader.readLine() ?: throw IOException("The root helper stopped unexpectedly.")
            when {
                r.startsWith("@@OK") -> return r.removePrefix("@@OK").trim()
                r.startsWith("@@ERR") -> throw IOException(r.removePrefix("@@ERR").trim())
                r.startsWith("@@P ") -> r.removePrefix("@@P ").toFloatOrNull()?.let(onProgress)
                r.startsWith("@@L ") -> onLog(r.removePrefix("@@L "))
                r.isNotBlank() -> onLog("   root: $r")
            }
        }
    }

    fun info(): Map<String, String> = String(Base64.getDecoder().decode(cmd("INFO")), Charsets.UTF_8).lines()
        .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()

    /** geometry block, then each metadata slot */
    fun superDump(): List<ByteArray> = cmd("SUPER").split(' ').filter { it.isNotEmpty() }.map { Base64.getDecoder().decode(it) }

    fun write(name: String, offset: Long, data: ByteArray, off: Int, len: Int) {
        cmd("WRITE $name $offset $len", data, off, len)
    }

    override fun close() {
        runCatching { cmd("QUIT") }
        runCatching { proc.waitFor(5, TimeUnit.SECONDS) }
        runCatching { proc.destroy() }
    }
}
