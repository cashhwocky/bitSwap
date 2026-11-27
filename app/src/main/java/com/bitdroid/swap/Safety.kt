package com.bitdroid.swap

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/**
 * The rules that keep the phone safe. Nothing here can be turned off from the UI.
 * The root helper (RootMain) checks these again on its side before every write.
 *
 * Zip reading is taken from bitFlash (Safety.kt).
 */
object Safety {

    class DeviceProfile(val codename: String, val name: String)

    /** Phones bitSwap will work on. Anything else is refused. */
    val DEVICES: Map<String, DeviceProfile> = listOf(
        DeviceProfile("frankel", "Pixel 10"),
        DeviceProfile("blazer", "Pixel 10 Pro"),
        DeviceProfile("mustang", "Pixel 10 Pro XL"),
        DeviceProfile("bluejay", "Pixel 6a"),
    ).associateBy { it.codename }

    /** Android 17. Anything older is refused (anti-rollback brick). */
    const val MIN_SDK = 37

    /**
     * The only partitions outside `super` that bitSwap will ever write (on the other slot only).
     * Everything else in a ROM zip (bootloader, modem, other firmware) is skipped.
     */
    val OS_PLAIN: Set<String> = setOf(
        "boot", "init_boot", "vendor_boot", "vendor_kernel_boot", "dtbo",
        "vbmeta", "vbmeta_system", "vbmeta_vendor",
        // protected-VM firmware: not part of the boot chain; written from the ROM like recovery does
        "pvmfw",
    )

    /**
     * Per-slot data the bootloader writes itself (RAM calibration), so it's expected to differ
     * between slots. Never written by bitSwap, just not counted in the firmware comparison.
     */
    val SLOT_DATA: Set<String> = setOf("dram_train")

    /** Never written, never wiped, whatever a zip says. Listed so the log can say why. */
    val NEVER: Set<String> = setOf(
        "abl", "bl1", "bl2", "bl31", "pbl", "gsa", "gsa_bl1", "ldfw", "tzsw", "gcf", "dram_train",
        "modem", "radio", "bootloader", "xbl", "xbl_config", "aop", "tz", "hyp", "keymaster",
        "misc", "devinfo", "frp", "persist", "efs", "efs_backup", "metadata", "userdata", "super",
        "fips", "dpm", "ufs", "ufsfw",
    )

    fun slotIndex(suffix: String): Int = when (suffix) {
        "_a" -> 0
        "_b" -> 1
        else -> throw IOException("Unknown slot '$suffix'")
    }

    fun otherSuffix(suffix: String): String = when (suffix) {
        "_a" -> "_b"
        "_b" -> "_a"
        else -> throw IOException("Unknown slot '$suffix'")
    }

    /** What the ROM zip's metadata says. */
    class RomInfo(val props: Map<String, String>) {
        val device get() = props["pre-device"] ?: ""
        val sdk get() = props["post-sdk-level"]?.toIntOrNull()
        val build get() = props["post-build"] ?: ""
        val patch get() = props["post-security-patch-level"] ?: ""
        val android get() = props["post-build"]?.split('/')?.getOrNull(2)?.substringAfter(':') ?: ""
    }

    /**
     * Reads META-INF/com/android/metadata without unpacking the zip. Returns the info, or throws
     * SecurityException with the reason in plain words.
     */
    fun checkRomZip(ch: FileChannel, size: Long, codename: String): RomInfo {
        val meta = try { readZipEntry(ch, size, "META-INF/com/android/metadata") }
                   catch (e: Exception) { throw SecurityException("Couldn't read this zip (${e.message}). Is it a ROM zip?") }
            ?: throw SecurityException("This zip has no ROM metadata, so bitSwap can't prove it's safe. Is it a ROM zip?")
        val props = String(meta, Charsets.UTF_8).lines()
            .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }
            .toMap()
        val info = RomInfo(props)
        val dev = DEVICES[codename] ?: throw SecurityException("This phone ($codename) isn't supported.")
        if (info.device.split(',').none { it.trim() == dev.codename }) {
            throw SecurityException("This ROM is for '${info.device.ifBlank { "unknown" }}', not your ${dev.name} (${dev.codename}). Blocked.")
        }
        val sdk = info.sdk ?: throw SecurityException("This ROM doesn't say its Android version, so bitSwap won't risk it.")
        if (sdk < MIN_SDK) {
            throw SecurityException("This ROM is Android SDK $sdk, older than Android 17 (SDK $MIN_SDK). That's the anti-rollback brick. Blocked.")
        }
        props["ota-type"]?.let { if (it != "AB") throw SecurityException("This zip is a '$it' update, not an A/B ROM zip. Blocked.") }
        if (props.containsKey("pre-build") || props.containsKey("pre-build-incremental")) {
            throw SecurityException("This zip is an incremental update (it needs a specific build already installed). Use the full ROM zip.")
        }
        return info
    }

    // ---- zip reading (from bitFlash) ----

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteBuffer {
        val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        var p = pos
        while (b.hasRemaining()) {
            val r = ch.read(b, p)
            if (r < 0) throw IOException("unexpected end of file")
            p += r
        }
        b.flip()
        return b
    }

    /** Where one file sits inside a zip, without unpacking anything. */
    class ZipEntryInfo(val dataPos: Long, val compSize: Long, val size: Long, val method: Int)

    fun findZipEntry(ch: FileChannel, size: Long, name: String): ZipEntryInfo? {
        // End of central directory: in the last 64 KB + 22 bytes.
        val tailLen = minOf(size, 65_557L).toInt()
        val tailPos = size - tailLen
        val tail = readAt(ch, tailPos, tailLen)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) {
            if (tail.getInt(i) == 0x06054b50) { eocd = i; break }
        }
        if (eocd < 0) throw IOException("not a zip")
        var cdSize = tail.getInt(eocd + 12).toLong() and 0xffffffffL
        var cdOff = tail.getInt(eocd + 16).toLong() and 0xffffffffL
        var entries = tail.getShort(eocd + 10).toInt() and 0xffff
        if (cdOff == 0xffffffffL || cdSize == 0xffffffffL || entries == 0xffff) {
            val locPos = eocd - 20
            if (locPos >= 0 && tail.getInt(locPos) == 0x07064b50) {
                val z64EocdOff = tail.getLong(locPos + 8)
                val z = readAt(ch, z64EocdOff, 56)
                if (z.getInt(0) != 0x06064b50) throw IOException("bad zip64 header")
                entries = z.getLong(32).toInt()
                cdSize = z.getLong(40)
                cdOff = z.getLong(48)
            }
        }
        if (cdSize > 64L * 1024 * 1024) throw IOException("zip directory too large")
        val cd = readAt(ch, cdOff, cdSize.toInt())
        var p = 0
        repeat(entries) {
            if (cd.getInt(p) != 0x02014b50) throw IOException("bad zip directory")
            val method = cd.getShort(p + 10).toInt() and 0xffff
            var compSize = cd.getInt(p + 20).toLong() and 0xffffffffL
            var uncompSize = cd.getInt(p + 24).toLong() and 0xffffffffL
            val nameLen = cd.getShort(p + 28).toInt() and 0xffff
            val extraLen = cd.getShort(p + 30).toInt() and 0xffff
            val commentLen = cd.getShort(p + 32).toInt() and 0xffff
            var localOff = cd.getInt(p + 42).toLong() and 0xffffffffL
            val nameBytes = ByteArray(nameLen)
            cd.position(p + 46); cd.get(nameBytes)
            val entryName = String(nameBytes, Charsets.UTF_8)
            if (entryName == name) {
                var e = p + 46 + nameLen
                val eEnd = e + extraLen
                while (e + 4 <= eEnd) {
                    val id = cd.getShort(e).toInt() and 0xffff
                    val sz = cd.getShort(e + 2).toInt() and 0xffff
                    if (id == 0x0001) {
                        var q = e + 4
                        if (uncompSize == 0xffffffffL) { uncompSize = cd.getLong(q); q += 8 }
                        if (compSize == 0xffffffffL) { compSize = cd.getLong(q); q += 8 }
                        if (localOff == 0xffffffffL) { localOff = cd.getLong(q) }
                    }
                    e += 4 + sz
                }
                val lh = readAt(ch, localOff, 30)
                if (lh.getInt(0) != 0x04034b50) throw IOException("bad local header")
                val lNameLen = lh.getShort(26).toInt() and 0xffff
                val lExtraLen = lh.getShort(28).toInt() and 0xffff
                return ZipEntryInfo(localOff + 30 + lNameLen + lExtraLen, compSize, uncompSize, method)
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        return null
    }

    private fun readZipEntry(ch: FileChannel, size: Long, name: String): ByteArray? {
        val e = findZipEntry(ch, size, name) ?: return null
        if (e.size > 1_000_000 || e.compSize > 1_000_000) throw IOException("metadata too large")
        val raw = ByteArray(e.compSize.toInt())
        readAt(ch, e.dataPos, e.compSize.toInt()).get(raw)
        return when (e.method) {
            0 -> raw
            8 -> {
                val inf = Inflater(true)
                inf.setInput(raw)
                val out = ByteArray(e.size.toInt())
                var got = 0
                while (got < out.size && !inf.finished()) {
                    val n = inf.inflate(out, got, out.size - got)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    got += n
                }
                inf.end()
                out.copyOf(got)
            }
            else -> throw IOException("unsupported compression")
        }
    }

    /** Reads a (small) text entry like payload_properties.txt out of a zip, or null if it's not there. */
    fun readTextEntry(ch: FileChannel, size: Long, name: String): String? =
        readZipEntry(ch, size, name)?.let { String(it, Charsets.UTF_8) }

    /** Reads a (small) binary entry like META-INF/CERT.RSA out of a zip, or null if it's not there. */
    fun readEntryBytes(ch: FileChannel, size: Long, name: String): ByteArray? = readZipEntry(ch, size, name)

    /** Reads `len` bytes at `pos`. */
    fun readBytes(ch: FileChannel, pos: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        readAt(ch, pos, len).get(b)
        return b
    }
}
