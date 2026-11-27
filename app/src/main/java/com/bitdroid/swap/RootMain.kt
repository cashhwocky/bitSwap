package com.bitdroid.swap

import android.system.Os
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * The part of bitSwap that runs as root. Started by the app through Magisk:
 *
 *   su -c "CLASSPATH=<bitSwap.apk> exec app_process /system/bin com.bitdroid.swap.RootMain"
 *
 * bitSwap installs a ROM the same way Android's own OTA does: it hands the ROM's payload.bin to
 * `update_engine`, which writes the inactive slot as a compressed snapshot (so it fits in `super`,
 * which a second full copy never would), builds verity, and marks the new slot active. bitSwap then
 * optionally patches the new slot's init_boot with Magisk, schedules a data wipe, and reboots.
 *
 * Safety is enforced HERE, on the root side:
 *  - the inactive slot is worked out from ro.boot.slot_suffix; the running slot is never the target
 *  - update_engine only writes boot images + dynamic partitions; a full OTA has no bootloader/modem/firmware
 *  - the only partition bitSwap writes by hand is init_boot on the inactive slot (for Magisk), checked here
 *  - misc only ever gets the standard recovery wipe message
 */
object RootMain {

    private val out = PrintStream(FileOutputStream(FileDescriptor.out), false, "UTF-8")
    private const val BY_NAME = "/dev/block/by-name"
    private const val MISC = "/dev/block/by-name/misc"
    private const val OTA_DIR = "/data/ota_package"
    private const val OTA_FILE = "/data/ota_package/payload.bin"
    private const val UE = "update_engine_client"

    private lateinit var current: String
    private lateinit var target: String
    private lateinit var device: String
    private val files = mutableMapOf<String, RandomAccessFile>()

    @JvmStatic
    fun main(args: Array<String>) {
        val input = DataInputStream(BufferedInputStream(System.`in`, 1 shl 20))
        try {
            if (Os.getuid() != 0) { reply("@@ERR not running as root"); return }
            current = prop("ro.boot.slot_suffix")
            target = Safety.otherSuffix(current)
            device = prop("ro.product.device")
            if (device !in Safety.DEVICES) { reply("@@ERR This phone ($device) isn't supported."); return }
            reply("@@READY uid=0 slot=$current")
        } catch (e: Throwable) {
            reply("@@ERR ${e.message ?: e.javaClass.simpleName}")
            return
        }
        while (true) {
            val line = readLine(input) ?: break
            if (line.isBlank()) continue
            val a = line.trim().split(' ')
            try {
                when (a[0]) {
                    "INFO" -> info()
                    "STAGE" -> stage(a[1])
                    "PAYLOADKEY" -> { val len = a[1].toInt(); installKey(readBlob(input, len)) }
                    "OTA" -> ota(a[1].toLong(), a[2])
                    "PLAINSIZE" -> reply("@@OK ${plainSize(a[1])}")
                    "WRITE" -> { val len = a[3].toInt(); write(a[1], a[2].toLong(), readBlob(input, len), len); reply("@@OK") }
                    "ZERO" -> { zero(a[1], a[2].toLong(), a[3].toLong()); reply("@@OK") }
                    "HASH" -> reply("@@OK ${hash(a[1], a[2].toLong())}")
                    "WIPE" -> { wipeMessage(); reply("@@OK") }
                    "CLEANUP" -> { runCatching { File(OTA_FILE).delete() }; reply("@@OK") }
                    "REBOOT" -> { sync(); sh("/system/bin/svc power reboot recovery || /system/bin/reboot recovery"); reply("@@OK") }
                    "QUIT" -> { sync(); reply("@@OK"); return }
                    else -> reply("@@ERR unknown command ${a[0]}")
                }
            } catch (e: Throwable) {
                reply("@@ERR " + (e.message ?: e.javaClass.simpleName).replace('\n', ' '))
            }
        }
        sync()
    }

    // ---------------- protocol ----------------

    private fun reply(s: String) = synchronized(out) { out.println(s); out.flush() }
    private fun log(s: String) = reply("@@L " + s.replace('\n', ' '))
    private var lastProg = 0L
    private fun prog(p: Float) {
        val now = System.currentTimeMillis()
        if (now - lastProg > 300 || p >= 1f) { lastProg = now; reply("@@P $p") }
    }

    private fun readLine(i: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
            if (sb.length > 1 shl 20) throw IOException("line too long")
        }
    }

    private fun readBlob(i: DataInputStream, len: Int): ByteArray {
        if (len < 0 || len > 64 * 1024 * 1024) throw IOException("bad length")
        return ByteArray(len).also { i.readFully(it) }
    }

    // ---------------- shell ----------------

    class ShResult(val code: Int, val out: String)

    private fun sh(cmd: String): ShResult {
        val p = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val text = p.inputStream.bufferedReader().readText()
        return ShResult(p.waitFor(), text.trim())
    }

    private fun prop(name: String): String = sh("getprop $name").out.lines().firstOrNull()?.trim() ?: ""

    // ---------------- info ----------------

    private fun ueStatus(): String {
        val r = sh("$UE --status 2>&1")
        return Regex("CURRENT_OP=([A-Z_]+)").find(r.out)?.groupValues?.get(1) ?: "UNKNOWN"
    }

    private fun info() {
        val kv = linkedMapOf(
            "slot" to current,
            "target" to target,
            "device" to device,
            "sdk" to prop("ro.build.version.sdk"),
            "build" to prop("ro.build.display.id"),
            "ue" to (if (File("/system/bin/$UE").exists() || sh("command -v $UE").out.isNotBlank()) "yes" else "no"),
            "ue_status" to ueStatus(),
            "data_free_mb" to dataFreeMb().toString(),
        )
        reply("@@OK " + Base64.getEncoder().encodeToString(kv.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray()))
    }

    private fun dataFreeMb(): Long = runCatching {
        val st = Os.statvfs("/data"); st.f_bavail * st.f_frsize / (1024 * 1024)
    }.getOrDefault(-1L)

    // ---------------- stage payload ----------------

    /** Moves the payload.bin the app wrote (at [appPath]) into /data/ota_package where update_engine can read it. */
    private fun stage(appPath: String) {
        val src = File(appPath)
        if (!src.exists()) throw IOException("staged payload not found")
        File(OTA_DIR).mkdirs()
        sh("chcon u:object_r:ota_package_file:s0 $OTA_DIR 2>/dev/null")
        runCatching { File(OTA_FILE).delete() }
        if (!src.renameTo(File(OTA_FILE))) { // same /data filesystem: usually instant
            src.inputStream().use { i -> FileOutputStream(OTA_FILE).use { o -> i.copyTo(o, 4 shl 20) } }
            src.delete()
        }
        sh("chmod 644 $OTA_FILE")
        val ctx = sh("chcon u:object_r:ota_package_file:s0 $OTA_FILE 2>&1")
        if (ctx.code != 0) log("note: couldn't label the payload for update_engine (${ctx.out}); it may refuse to read it.")
        reply("@@OK ${File(OTA_FILE).length()}")
    }

    // ---------------- trust the ROM's signing key ----------------

    /**
     * update_engine verifies the payload against the key at its fixed path (in the current ROM's
     * /system). A different ROM is signed with a different key, so to install it we overlay that
     * ROM's own public key there with a bind mount, done in init's mount namespace so the
     * update_engine daemon (a child of init) actually sees it. Undone by the reboot.
     */
    private fun installKey(pem: ByteArray) {
        File(OTA_DIR).mkdirs()
        val pk = File(OTA_DIR, "pk.pem")
        pk.writeBytes(pem)
        sh("chmod 644 ${pk.absolutePath}")
        // Find the public key(s) update_engine verifies the payload with. Names and locations vary by
        // ROM, so look in the known dirs AND search the read-only partitions for anything key-shaped.
        val found = LinkedHashSet<String>()
        for (d in listOf("/system/etc/update_engine", "/system_ext/etc/update_engine", "/vendor/etc/update_engine",
                         "/product/etc/update_engine", "/etc/update_engine", "/system/etc/security")) {
            sh("ls -1 $d 2>/dev/null").out.lines().forEach { n -> if (n.trim().endsWith(".pem")) found.add("$d/${n.trim()}") }
        }
        sh("find /system /system_ext /vendor /product -type f \\( -iname '*payload*key*.pem' -o -iname 'update?payload?key*.pem' \\) 2>/dev/null")
            .out.lines().forEach { if (it.trim().isNotBlank()) found.add(it.trim()) }
        val targets = found.map { File(it) }.filter { it.exists() }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
        if (targets.isEmpty()) { reply("@@OK NO-KEY-FILE: update_engine's key isn't a file on this ROM; its signature can't be overridden"); return }
        var done = 0
        val names = mutableListOf<String>()
        for (t in targets) {
            sh("chcon --reference=${t.absolutePath} ${pk.absolutePath} 2>/dev/null")
            // init's mount namespace, so the update_engine daemon (child of init) actually sees the swap
            val ns = sh("nsenter -t 1 -m -- mount -o bind ${pk.absolutePath} ${t.absolutePath} 2>&1")
            val ok = ns.code == 0 || sh("mount -o bind ${pk.absolutePath} ${t.absolutePath} 2>&1").code == 0
            if (ok) { done++; names.add(t.absolutePath) } else log("couldn't swap the key at ${t.absolutePath}: ${ns.out}")
        }
        reply("@@OK swapped $done/${targets.size}: ${names.joinToString(", ")}")
    }

    // ---------------- run update_engine ----------------

    private fun ota(size: Long, headersB64: String) {
        if (!File(OTA_FILE).exists()) throw IOException("payload isn't staged")
        if (File(OTA_FILE).length() != size) throw IOException("staged payload is the wrong size")
        val headers = String(Base64.getDecoder().decode(headersB64), Charsets.UTF_8)
        sh("$UE --reset_status 2>&1")           // clear any previous (failed/applied) update
        sh("logcat -b all -c 2>/dev/null")      // so we can read the fresh reason if it rejects
        // kick off the install (no --follow: it would hang forever if the daemon rejects and goes idle)
        val kick = sh("$UE --payload=file://$OTA_FILE --offset=0 --size=$size --headers=${shellQuote(headers)} 2>&1")
        // poll the daemon for progress + terminal state
        val start = System.currentTimeMillis()
        var started = false
        while (true) {
            val st = sh("$UE --status 2>&1").out
            val op = Regex("CURRENT_OP=([A-Z_]+)").find(st)?.groupValues?.get(1) ?: "UNKNOWN"
            Regex("PROGRESS=([0-9.]+)").find(st)?.let { it.groupValues[1].toFloatOrNull()?.let { v -> prog(v) } }
            when (op) {
                "UPDATED_NEED_REBOOT" -> { prog(1f); reply("@@OK"); return }
                "REPORTING_ERROR_EVENT" -> throw IOException(explainFail(kick))
                "DOWNLOADING", "VERIFYING", "FINALIZING", "CLEANUP_PREVIOUS_UPDATE" -> { started = true }
                "IDLE" -> {
                    // idle after we kicked it off = it rejected the payload and never ran
                    if (started || kick.code != 0 || System.currentTimeMillis() - start > 20_000) throw IOException(explainFail(kick))
                }
            }
            if (System.currentTimeMillis() - start > 2L * 60 * 60 * 1000) throw IOException("Android's updater timed out after 2 hours.")
            Thread.sleep(1500)
        }
    }

    /** Builds a plain-words reason from the kick-off output + update_engine's own logcat. */
    private fun explainFail(kick: ShResult): String {
        val logs = sh("logcat -b all -d -s update_engine:* update_engine_client:* DeltaPerformer:* 2>/dev/null").out
        val lines = (kick.out + "\n" + logs).lines()
            .filter { it.contains("rror", true) || it.contains("verif", true) || it.contains("signature", true) ||
                      it.contains("timestamp", true) || it.contains("key", true) || it.contains("fail", true) }
            .map { it.trim() }.distinct().takeLast(6)
        val detail = lines.joinToString(" | ").ifBlank { kick.out.lines().lastOrNull { it.isNotBlank() } ?: "no details" }
        val hint = when {
            detail.contains("signature", true) || detail.contains("verif", true) || detail.contains("key", true) ->
                " → This ROM is signed with a key update_engine won't trust, and its key couldn't be overridden on this ROM. Install it with bitFlash instead."
            detail.contains("timestamp", true) || detail.contains("downgrade", true) ->
                " → This ROM's build is older than what's installed (anti-rollback). Use a newer build, or bitFlash."
            detail.contains("space", true) || detail.contains("ENOSPC", true) ->
                " → Not enough space to install. Free up storage and try again."
            else -> " → Install it with bitFlash from your other phone instead."
        }
        return "Android's updater rejected the ROM. $detail$hint"
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // ---------------- init_boot patch write (inactive slot only) ----------------

    private fun plainPath(name: String): String {
        if (name !in Safety.OS_PLAIN || name in Safety.NEVER) throw SecurityException("bitSwap never writes '$name'.")
        val f = File("$BY_NAME/$name$target")
        if (!f.exists()) throw IOException("This phone has no $name$target partition.")
        val real = f.canonicalPath
        if (!real.startsWith("/dev/block/")) throw SecurityException("odd path for $name$target")
        val mine = File("$BY_NAME/$name$current")
        if (mine.exists() && mine.canonicalPath == real) throw SecurityException("$name$target is the running slot. Refused.")
        return "$BY_NAME/$name$target"
    }

    private fun plainSize(name: String): Long = devSize(open(plainPath(name), true))

    private fun write(name: String, off: Long, data: ByteArray, len: Int) {
        val f = open(plainPath(name), true)
        if (off < 0 || off + len > devSize(f)) throw SecurityException("write past the end of $name$target. Refused.")
        writeAt(f, off, data, 0, len)
    }

    private val zeros = ByteArray(1 shl 20)
    private fun zero(name: String, off: Long, len: Long) {
        val f = open(plainPath(name), true)
        if (off < 0 || off + len > devSize(f)) throw SecurityException("zero past the end of $name$target. Refused.")
        var p = 0L
        while (p < len) { val k = minOf(zeros.size.toLong(), len - p).toInt(); writeAt(f, off + p, zeros, 0, k); p += k }
    }

    private fun hash(name: String, size: Long): String {
        val f = open(plainPath(name), false)
        if (size > devSize(f)) throw IOException("hash past the end of $name$target")
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(4 shl 20)
        var p = 0L
        while (p < size) { val n = minOf(buf.size.toLong(), size - p).toInt(); readInto(f, p, buf, 0, n); md.update(buf, 0, n); p += n; prog(p.toFloat() / size) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------- wipe message ----------------

    private fun wipeMessage() {
        val f = open(MISC, true)
        val old = readAt(f, 0, 2048)
        val msg = old.copyOf()
        java.util.Arrays.fill(msg, 0, 864, 0)
        "boot-recovery".toByteArray().copyInto(msg, 0)
        "recovery\n--wipe_data\n--reason=bitSwap\n".toByteArray().copyInto(msg, 64)
        writeAt(f, 0, msg)
        f.fd.sync()
        if (!readAt(f, 0, 2048).contentEquals(msg)) { writeAt(f, 0, old); f.fd.sync(); throw IOException("the wipe message didn't read back right") }
    }

    // ---------------- raw io ----------------

    private fun open(path: String, write: Boolean): RandomAccessFile =
        files.getOrPut("$path|${if (write) "rw" else "r"}") { RandomAccessFile(path, if (write) "rw" else "r") }

    private fun devSize(f: RandomAccessFile): Long = Os.lseek(f.fd, 0, android.system.OsConstants.SEEK_END)

    private fun readAt(f: RandomAccessFile, pos: Long, len: Int): ByteArray = ByteArray(len).also { readInto(f, pos, it, 0, len) }

    private fun readInto(f: RandomAccessFile, pos: Long, b: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(b, off, len); var p = pos
        while (bb.hasRemaining()) { val r = f.channel.read(bb, p); if (r < 0) throw IOException("read past end"); p += r }
    }

    private fun writeAt(f: RandomAccessFile, pos: Long, b: ByteArray, off: Int = 0, len: Int = b.size) {
        val bb = ByteBuffer.wrap(b, off, len); var p = pos
        while (bb.hasRemaining()) p += f.channel.write(bb, p)
    }

    private fun sync() = files.filterKeys { it.endsWith("|rw") }.values.forEach { runCatching { it.fd.sync() } }
}
