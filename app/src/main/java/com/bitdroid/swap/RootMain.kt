package com.bitdroid.swap

import android.system.Os
import android.system.OsConstants
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
 * The app sends commands on stdin; this answers with "@@OK …" / "@@ERR …" lines.
 * EVERY safety rule is enforced here, on the root side, no matter what the app asks:
 *  - the slot to write is worked out here from ro.boot.slot_suffix; the running slot is never written
 *  - only OS partitions (Safety.OS_PLAIN) and the ROM's partitions inside super can be written
 *  - writes into super only land in space that no running-slot partition uses
 *  - bootloader, modem, firmware and lock state are never touched; misc only gets the wipe message
 */
object RootMain {

    private val out = PrintStream(FileOutputStream(FileDescriptor.out), false, "UTF-8")
    private const val SUPER = "/dev/block/by-name/super"
    private const val MISC = "/dev/block/by-name/misc"
    private const val BY_NAME = "/dev/block/by-name"

    private lateinit var current: String   // "_a" / "_b": the slot we're running on
    private lateinit var target: String    // the other one
    private lateinit var device: String

    private var geo: Lp.Geometry? = null
    private var slots: List<Lp.Metadata> = emptyList()
    private var plan: Lp.Metadata? = null
    private var planBlob: ByteArray? = null
    private var planTargets: Map<String, List<Lp.Extent>> = emptyMap()  // name (no suffix) → extents
    private var forbidden: List<LongRange> = emptyList()                 // bytes on super we must never write
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
                    "SUPER" -> superDump()
                    "PLAN" -> planCmd(readBlob(input, a[1].toInt()))
                    "PLAINSIZE" -> reply("@@OK ${plainSize(a[1])}")
                    "UNBOOTABLE" -> markOtherUnbootable()
                    "FWCHECK" -> fwCheck()
                    "WRITE" -> { val len = a[3].toInt(); val data = readBlob(input, len); write(a[1], a[2].toLong(), data, len); reply("@@OK") }
                    "ZERO" -> { zero(a[1], a[2].toLong(), a[3].toLong()); reply("@@OK") }
                    "VERITY" -> verity(a)
                    "HASH" -> reply("@@OK ${hash(a[1], a[2].toLong())}")
                    "SYNC" -> { syncAll(); reply("@@OK") }
                    "COMMIT" -> commit()
                    "SWITCH" -> { setActive(Safety.slotIndex(target)); reply("@@OK") }
                    "REVERT" -> { setActive(Safety.slotIndex(current)); reply("@@OK") }
                    "WIPE" -> { wipeMessage(); reply("@@OK") }
                    "REBOOT" -> { syncAll(); sh("/system/bin/svc power reboot recovery || /system/bin/reboot recovery"); reply("@@OK") }
                    "QUIT" -> { syncAll(); reply("@@OK"); return }
                    else -> reply("@@ERR unknown command ${a[0]}")
                }
            } catch (e: Throwable) {
                reply("@@ERR " + (e.message ?: e.javaClass.simpleName).replace('\n', ' '))
            }
        }
        syncAll()
    }

    // ---------------- protocol ----------------

    private fun reply(s: String) = synchronized(out) { out.println(s); out.flush() }
    private fun log(s: String) = reply("@@L " + s.replace('\n', ' '))
    private var lastProg = 0L
    private fun prog(p: Float) {
        val now = System.currentTimeMillis()
        if (now - lastProg > 200 || p >= 1f) { lastProg = now; reply("@@P $p") }
    }

    private fun readLine(i: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
            if (sb.length > 4096) throw IOException("line too long")
        }
    }

    private fun readBlob(i: DataInputStream, len: Int): ByteArray {
        if (len < 0 || len > 64 * 1024 * 1024) throw IOException("bad length")
        return ByteArray(len).also { i.readFully(it) }
    }

    // ---------------- shell helpers ----------------

    class ShResult(val code: Int, val out: String)

    private fun sh(cmd: String): ShResult {
        val p = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val text = p.inputStream.bufferedReader().readText()
        return ShResult(p.waitFor(), text.trim())
    }

    private fun prop(name: String): String = sh("getprop $name").out.lines().firstOrNull()?.trim() ?: ""

    // ---------------- INFO ----------------

    private fun snapshotState(): String {
        val r = sh("snapshotctl dump 2>&1")
        val m = Regex("Update state:\\s*([A-Za-z-]+)").find(r.out)
        if (m != null) return m.groupValues[1].lowercase()
        // no snapshotctl: no snapshot files means nothing pending
        val snaps = File("/metadata/ota/snapshots").list()
        return if (snaps == null || snaps.isEmpty()) "none" else "unknown"
    }

    /** dm devices that belong to the other slot (would mean something is using it right now). */
    private fun busyOnOtherSlot(): List<String> =
        (File("/sys/block").listFiles() ?: emptyArray())
            .filter { it.name.startsWith("dm-") }
            .mapNotNull { runCatching { File(it, "dm/name").readText().trim() }.getOrNull() }
            .filter { it.endsWith(target) || it.contains("$target-") }

    private fun bootctlMode(): String = when {
        File("/system/bin/bootctl").exists() -> "bootctl"
        sh("service check android.hardware.boot.IBootControl/default").out.let { it.contains(": found") } -> "aidl"
        else -> "none"
    }

    private fun info() {
        val kv = linkedMapOf(
            "slot" to current,
            "target" to target,
            "device" to device,
            "sdk" to prop("ro.build.version.sdk"),
            "build" to prop("ro.build.display.id"),
            "snapshot" to snapshotState(),
            "busy" to busyOnOtherSlot().joinToString(","),
            "slotctl" to bootctlMode(),
            "super" to (if (File(SUPER).exists()) "yes" else "no"),
        )
        reply("@@OK " + b64(kv.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray()))
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    // ---------------- super ----------------

    private fun readSuper(): Pair<Lp.Geometry, List<Lp.Metadata>> {
        val f = open(SUPER, write = false)
        val g = Lp.parseGeometry(readAt(f, Lp.RESERVED_BYTES, Lp.GEOMETRY_SIZE))
        val ms = (0 until g.slotCount).map { s ->
            runCatching { Lp.parseMetadata(readAt(f, g.primaryOffset(s), g.metadataMaxSize)) }
                .recoverCatching { Lp.parseMetadata(readAt(f, g.backupOffset(s), g.metadataMaxSize)) }
                .getOrThrow()
        }
        return g to ms
    }

    private fun superDump() {
        val f = open(SUPER, write = false)
        val (g, ms) = readSuper()
        geo = g; slots = ms
        val parts = mutableListOf(b64(readAt(f, Lp.RESERVED_BYTES, Lp.GEOMETRY_SIZE)))
        for (s in 0 until g.slotCount) {
            // send the copy that parsed
            val prim = readAt(f, g.primaryOffset(s), g.metadataMaxSize)
            parts += b64(if (runCatching { Lp.parseMetadata(prim) }.isSuccess) prim else readAt(f, g.backupOffset(s), g.metadataMaxSize))
        }
        reply("@@OK " + parts.joinToString(" "))
    }

    /** Checks the new table for the other slot. Nothing is written here; COMMIT writes it. */
    private fun planCmd(blob: ByteArray) {
        val (g, ms) = readSuper()
        geo = g; slots = ms
        if (blob.size > g.metadataMaxSize) throw IOException("new table too big")
        val superBytes = devSize(open(SUPER, write = false))
        val (m, targets, forb) = Guard.checkPlan(blob, ms, current, target, superBytes)
        plan = m; planBlob = blob; planTargets = targets; forbidden = forb
        reply("@@OK ${targets.size}")
    }

    private fun commit() {
        val m = plan ?: throw IOException("no plan")
        val blob = planBlob!!
        val g = geo!!
        val slot = Safety.slotIndex(target)
        syncAll()
        val f = open(SUPER, write = true)
        // backup first, then primary (same order as liblp)
        writeAt(f, g.backupOffset(slot), blob)
        writeAt(f, g.primaryOffset(slot), blob)
        f.fd.sync()
        // read back and check
        for (off in listOf(g.primaryOffset(slot), g.backupOffset(slot))) {
            val back = Lp.parseMetadata(readAt(f, off, g.metadataMaxSize))
            if (back.partitions != m.partitions) throw IOException("the partition table didn't read back right")
        }
        reply("@@OK")
    }

    // ---------------- targets ----------------

    private fun plainPath(name: String): String {
        if (name !in Safety.OS_PLAIN || name in Safety.NEVER) throw SecurityException("bitSwap never writes '$name'.")
        val p = "$BY_NAME/$name$target"
        val f = File(p)
        if (!f.exists()) throw IOException("This phone has no $name$target partition.")
        val real = f.canonicalPath
        if (!real.startsWith("/dev/block/")) throw SecurityException("odd path for $name$target")
        val mine = File("$BY_NAME/$name$current")
        if (mine.exists() && mine.canonicalPath == real) throw SecurityException("$name$target is the running slot. Refused.")
        return p
    }

    private fun plainSize(name: String): Long = devSize(open(plainPath(name), write = false))

    /** Maps a byte range inside partition `name` (other slot) to (file, absolute offset, length) pieces. */
    private fun pieces(name: String, off: Long, len: Long, write: Boolean): List<Triple<RandomAccessFile, Long, Long>> {
        if (name in Safety.NEVER) throw SecurityException("bitSwap never writes '$name'.")
        if (off < 0 || len < 0) throw IOException("bad range")
        val ext = planTargets[name]
        if (ext != null) {
            val size = ext.sumOf { it.numSectors } * Lp.SECTOR
            if (off + len > size) throw SecurityException("write past the end of $name$target. Refused.")
            val f = open(SUPER, write)
            val res = mutableListOf<Triple<RandomAccessFile, Long, Long>>()
            var logical = 0L
            var o = off
            var left = len
            for (e in ext) {
                val eLen = e.numSectors * Lp.SECTOR
                if (left > 0 && o < logical + eLen) {
                    val inExt = o - logical
                    val n = minOf(eLen - inExt, left)
                    val phys = e.targetData * Lp.SECTOR + inExt
                    val r = phys until phys + n
                    if (forbidden.any { Lp.overlaps(it, r) }) throw SecurityException("write would hit the running ROM. Refused.")
                    res += Triple(f, phys, n)
                    o += n; left -= n
                }
                logical += eLen
            }
            return res
        }
        val f = open(plainPath(name), write)
        if (off + len > devSize(f)) throw SecurityException("write past the end of $name$target. Refused.")
        return listOf(Triple(f, off, len))
    }

    private fun write(name: String, off: Long, data: ByteArray, len: Int) {
        var done = 0
        for ((f, pos, n) in pieces(name, off, len.toLong(), write = true)) {
            writeAt(f, pos, data, done, n.toInt())
            done += n.toInt()
        }
    }

    private val zeros = ByteArray(1 shl 20)
    private fun zero(name: String, off: Long, len: Long) {
        for ((f, pos, n) in pieces(name, off, len, write = true)) {
            var p = 0L
            while (p < n) {
                val k = minOf(zeros.size.toLong(), n - p).toInt()
                writeAt(f, pos + p, zeros, 0, k)
                p += k
            }
        }
    }

    private fun partIo(name: String): Verity.Io = object : Verity.Io {
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
            var done = off
            for ((f, p, n) in pieces(name, pos, len.toLong(), write = false)) { readInto(f, p, buf, done, n.toInt()); done += n.toInt() }
        }
        override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
            var done = off
            for ((f, p, n) in pieces(name, pos, len.toLong(), write = true)) { writeAt(f, p, buf, done, n.toInt()); done += n.toInt() }
        }
    }

    // VERITY name bs htDataStart htDataCount htStart htCount alg salt fecDataStart fecDataCount fecStart fecCount roots
    private fun verity(a: List<String>) {
        val name = a[1]
        val bs = a[2].toInt()
        val io = partIo(name)
        syncAll()
        if (a[6] != "-") {
            val salt = if (a[8] == "-") ByteArray(0) else hex(a[8])
            log("Building the hash tree for $name…")
            Verity.hashTree(io, bs, a[3].toLong(), a[4].toLong(), a[5].toLong(), a[6].toLong(), a[7], salt) { prog(it * 0.3f) }
        }
        if (a[12] != "-") {
            log("Building error correction (FEC) for $name…")
            Verity.fec(io, bs, a[9].toLong(), a[10].toLong(), a[11].toLong(), a[12].toLong(), a[13].toInt(),
                Runtime.getRuntime().availableProcessors()) { prog(0.3f + it * 0.7f) }
        }
        reply("@@OK")
    }

    private fun hash(name: String, size: Long): String {
        syncAll()
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(4 shl 20)
        val io = partIo(name)
        var p = 0L
        while (p < size) {
            val n = minOf(buf.size.toLong(), size - p).toInt()
            io.read(p, buf, 0, n)
            md.update(buf, 0, n)
            p += n
            prog(p.toFloat() / size)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------- firmware check (read only) ----------------

    /**
     * Compares every A/B partition that isn't an OS partition (bootloader, modem, firmware…) between
     * the two slots. If they differ, booting the other slot could run older firmware: refuse.
     * Read only: nothing is written.
     */
    private fun fwCheck() {
        val names = (File(BY_NAME).list() ?: emptyArray()).toSet()
        val fw = names.filter { it.endsWith("_a") }.map { it.removeSuffix("_a") }
            .filter { "${it}_b" in names && it !in Safety.OS_PLAIN }
            .sorted()
        val total = fw.sumOf { runCatching { devSize(open("$BY_NAME/${it}_a", false)) }.getOrDefault(0L) }.coerceAtLeast(1)
        var done = 0L
        val diff = mutableListOf<String>()
        for (n in fw) {
            val fa = open("$BY_NAME/${n}_a", false)
            val fb = open("$BY_NAME/${n}_b", false)
            val sa = devSize(fa); val sb = devSize(fb)
            if (sa != sb) { diff += n; done += sa; continue }
            val ba = ByteArray(1 shl 20); val bb = ByteArray(1 shl 20)
            var p = 0L
            var same = true
            while (p < sa) {
                val k = minOf(ba.size.toLong(), sa - p).toInt()
                readInto(fa, p, ba, 0, k); readInto(fb, p, bb, 0, k)
                var i = 0
                while (i < k && ba[i] == bb[i]) i++
                if (i < k) { same = false; break }
                p += k
                prog((done + p).toFloat() / total)
            }
            done += sa
            if (!same) diff += n
        }
        reply("@@OK checked=${fw.joinToString(",")} diff=${diff.joinToString(",")}")
    }

    // ---------------- slots ----------------

    private fun svc(code: Int, vararg ints: Int): Int {
        val args = ints.joinToString(" ") { "i32 $it" }
        val r = sh("service call android.hardware.boot.IBootControl/default $code $args")
        val words = Regex("\\b[0-9a-fA-F]{8}\\b").findAll(r.out.substringAfter("Parcel(")).map { it.value }.toList()
        if (r.code != 0 || words.isEmpty() || words[0] != "00000000") throw IOException("boot control call $code failed: ${r.out}")
        return words.getOrNull(1)?.toLong(16)?.toInt() ?: 0
    }

    /** AIDL IBootControl codes: 1 getActiveBootSlot, 2 getCurrentSlot, 3 getNumberSlots, 6 isSlotBootable, 9 setActiveBootSlot, 10 setSlotAsUnbootable */
    private fun aidlSanity() {
        if (svc(3) != 2) throw IOException("boot control doesn't report 2 slots")
        if (svc(2) != Safety.slotIndex(current)) throw IOException("boot control reports a different running slot")
    }

    private fun markOtherUnbootable() {
        val n = Safety.slotIndex(target)
        when (bootctlMode()) {
            "bootctl" -> {
                val cur = sh("bootctl get-current-slot").out.trim()
                if (cur != Safety.slotIndex(current).toString()) throw IOException("bootctl reports slot '$cur', expected ${Safety.slotIndex(current)}")
                val r = sh("bootctl set-slot-as-unbootable $n")
                if (r.code != 0) throw IOException("bootctl failed: ${r.out}")
            }
            "aidl" -> { aidlSanity(); svc(10, n); if (svc(6, n) != 0) throw IOException("other slot still shows bootable") }
            else -> throw IOException("This ROM has no way to switch slots from Android (no bootctl / boot HAL).")
        }
        reply("@@OK")
    }

    private fun setActive(n: Int) {
        when (bootctlMode()) {
            "bootctl" -> {
                val r = sh("bootctl set-active-boot-slot $n")
                if (r.code != 0) throw IOException("bootctl failed: ${r.out}")
                val got = sh("bootctl get-active-boot-slot").out.trim()
                if (got.isNotEmpty() && got != n.toString()) throw IOException("active slot is $got, expected $n")
            }
            "aidl" -> {
                aidlSanity()
                svc(9, n)
                val got = svc(1)
                if (got != n) throw IOException("active slot is $got, expected $n")
            }
            else -> throw IOException("no way to switch slots (no bootctl / boot HAL)")
        }
    }

    // ---------------- wipe message (misc, first 2 KB only) ----------------

    /** bootloader_message: command[32] status[32] recovery[768] stage[32] reserved[1184]. Only the first 864 bytes change. */
    private fun wipeMessage() {
        val f = open(MISC, write = true)
        val old = readAt(f, 0, 2048)
        val msg = old.copyOf()
        java.util.Arrays.fill(msg, 0, 864, 0)
        "boot-recovery".toByteArray().copyInto(msg, 0)
        "recovery\n--wipe_data\n--reason=bitSwap\n".toByteArray().copyInto(msg, 64)
        writeAt(f, 0, msg)
        f.fd.sync()
        if (!readAt(f, 0, 2048).contentEquals(msg)) {
            writeAt(f, 0, old); f.fd.sync()
            throw IOException("the wipe message didn't read back right (put the old one back)")
        }
    }

    // ---------------- raw io ----------------

    private fun open(path: String, write: Boolean): RandomAccessFile {
        val key = "$path|${if (write) "rw" else "r"}"
        return files.getOrPut(key) { RandomAccessFile(path, if (write) "rw" else "r") }
    }

    private fun devSize(f: RandomAccessFile): Long = Os.lseek(f.fd, 0, OsConstants.SEEK_END)

    private fun readAt(f: RandomAccessFile, pos: Long, len: Int): ByteArray = ByteArray(len).also { readInto(f, pos, it, 0, len) }

    private fun readInto(f: RandomAccessFile, pos: Long, b: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(b, off, len)
        var p = pos
        while (bb.hasRemaining()) {
            val r = f.channel.read(bb, p)
            if (r < 0) throw IOException("read past end")
            p += r
        }
    }

    private fun writeAt(f: RandomAccessFile, pos: Long, b: ByteArray, off: Int = 0, len: Int = b.size) {
        val bb = ByteBuffer.wrap(b, off, len)
        var p = pos
        while (bb.hasRemaining()) p += f.channel.write(bb, p)
    }

    private fun syncAll() {
        files.filterKeys { it.endsWith("|rw") }.values.forEach { runCatching { it.fd.sync() } }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
