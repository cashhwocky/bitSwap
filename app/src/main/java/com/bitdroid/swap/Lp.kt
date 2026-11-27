package com.bitdroid.swap

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Reads and writes the partition table inside `super` (Android's "liblp" format,
 * system/core/fs_mgr/liblp/include/liblp/metadata_format.h). Same job as lptools,
 * done in Kotlin so bitSwap doesn't need to ship extra binaries.
 *
 * Layout of super:
 *   0      .. 4096   reserved
 *   4096   .. 8192   geometry (primary)
 *   8192   .. 12288  geometry (backup)
 *   12288  ..        metadata slot 0, 1, … (primary), then the same again (backup)
 */
object Lp {
    const val GEOMETRY_MAGIC = 0x616c4467
    const val HEADER_MAGIC = 0x414C5030
    const val RESERVED_BYTES = 4096L
    const val GEOMETRY_SIZE = 4096
    const val SECTOR = 512L
    const val TARGET_LINEAR = 0
    const val ATTR_READONLY = 1

    private const val PART_SIZE = 52
    private const val EXTENT_SIZE = 24
    private const val GROUP_SIZE = 48
    private const val BLOCKDEV_SIZE = 64
    private const val NAME_LEN = 36

    class Geometry(val metadataMaxSize: Int, val slotCount: Int, val logicalBlockSize: Int) {
        fun primaryOffset(slot: Int): Long {
            require(slot in 0 until slotCount)
            return RESERVED_BYTES + GEOMETRY_SIZE * 2L + metadataMaxSize.toLong() * slot
        }
        fun backupOffset(slot: Int): Long {
            require(slot in 0 until slotCount)
            return RESERVED_BYTES + GEOMETRY_SIZE * 2L + metadataMaxSize.toLong() * slotCount + metadataMaxSize.toLong() * slot
        }
    }

    data class Extent(val numSectors: Long, val targetType: Int, val targetData: Long, val targetSource: Int) {
        val end get() = targetData + numSectors
    }
    data class Partition(val name: String, val attributes: Int, val extents: List<Extent>, val groupIndex: Int) {
        val sizeBytes get() = extents.sumOf { it.numSectors } * SECTOR
    }
    data class Group(val name: String, val flags: Int, val maxSize: Long)
    data class BlockDevice(val firstLogicalSector: Long, val alignment: Int, val alignmentOffset: Int, val size: Long, val name: String, val flags: Int)

    class Metadata(
        val major: Int,
        val minor: Int,
        val headerSize: Int,
        /** header bytes after the fixed fields (flags + reserved for v10.2), kept as-is */
        val headerTail: ByteArray,
        val partitions: List<Partition>,
        val groups: List<Group>,
        val blockDevices: List<BlockDevice>,
    )

    private fun sha256(b: ByteArray, off: Int = 0, len: Int = b.size): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(b, off, len) }.digest()

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun name(bb: ByteBuffer, pos: Int): String {
        val raw = ByteArray(NAME_LEN)
        for (i in 0 until NAME_LEN) raw[i] = bb.get(pos + i)
        val n = raw.indexOf(0).let { if (it < 0) NAME_LEN else it }
        return String(raw, 0, n, Charsets.US_ASCII)
    }

    fun parseGeometry(b: ByteArray): Geometry {
        if (b.size < 52) throw IOException("super geometry too short")
        val bb = le(b)
        if (bb.getInt(0) != GEOMETRY_MAGIC) throw IOException("super has no partition table (bad geometry magic)")
        val structSize = bb.getInt(4)
        if (structSize < 52 || structSize > b.size) throw IOException("odd super geometry size")
        val copy = b.copyOf(structSize)
        val want = copy.copyOfRange(8, 40)
        for (i in 8 until 40) copy[i] = 0
        if (!sha256(copy).contentEquals(want)) throw IOException("super geometry checksum is wrong")
        val g = Geometry(bb.getInt(40), bb.getInt(44), bb.getInt(48))
        if (g.metadataMaxSize <= 0 || g.metadataMaxSize % 512 != 0 || g.slotCount !in 1..3 || g.logicalBlockSize % 512 != 0 || g.logicalBlockSize <= 0) {
            throw IOException("odd super geometry values")
        }
        return g
    }

    fun parseMetadata(b: ByteArray): Metadata {
        val bb = le(b)
        if (b.size < 128 || bb.getInt(0) != HEADER_MAGIC) throw IOException("super metadata has a bad header")
        val major = bb.getShort(4).toInt() and 0xffff
        val minor = bb.getShort(6).toInt() and 0xffff
        if (major != 10 || minor > 2) throw IOException("super metadata version $major.$minor isn't supported")
        val headerSize = bb.getInt(8)
        if (headerSize < 128 || headerSize > b.size) throw IOException("odd super header size")
        val hdr = b.copyOf(headerSize)
        val wantH = hdr.copyOfRange(12, 44)
        for (i in 12 until 44) hdr[i] = 0
        if (!sha256(hdr).contentEquals(wantH)) throw IOException("super header checksum is wrong")
        val tablesSize = bb.getInt(44)
        if (tablesSize < 0 || headerSize.toLong() + tablesSize > b.size) throw IOException("super tables out of range")
        val wantT = b.copyOfRange(48, 80)
        if (!sha256(b, headerSize, tablesSize).contentEquals(wantT)) throw IOException("super tables checksum is wrong")

        fun desc(at: Int) = Triple(bb.getInt(at), bb.getInt(at + 4), bb.getInt(at + 8))
        val (pOff, pNum, pSz) = desc(80)
        val (eOff, eNum, eSz) = desc(92)
        val (gOff, gNum, gSz) = desc(104)
        val (dOff, dNum, dSz) = desc(116)
        fun check(off: Int, num: Int, sz: Int, min: Int) {
            if (num < 0 || sz < min || off < 0 || off.toLong() + num.toLong() * sz > tablesSize) throw IOException("super table out of range")
        }
        check(pOff, pNum, pSz, PART_SIZE); check(eOff, eNum, eSz, EXTENT_SIZE)
        check(gOff, gNum, gSz, GROUP_SIZE); check(dOff, dNum, dSz, BLOCKDEV_SIZE)

        val extents = (0 until eNum).map { i ->
            val at = headerSize + eOff + i * eSz
            Extent(bb.getLong(at), bb.getInt(at + 8), bb.getLong(at + 12), bb.getInt(at + 20))
        }
        val parts = (0 until pNum).map { i ->
            val at = headerSize + pOff + i * pSz
            val first = bb.getInt(at + 40)
            val num = bb.getInt(at + 44)
            if (first < 0 || num < 0 || first + num > extents.size) throw IOException("super partition extents out of range")
            Partition(name(bb, at), bb.getInt(at + 36), extents.subList(first, first + num).toList(), bb.getInt(at + 48))
        }
        val groups = (0 until gNum).map { i ->
            val at = headerSize + gOff + i * gSz
            Group(name(bb, at), bb.getInt(at + 36), bb.getLong(at + 40))
        }
        val devs = (0 until dNum).map { i ->
            val at = headerSize + dOff + i * dSz
            BlockDevice(bb.getLong(at), bb.getInt(at + 8), bb.getInt(at + 12), bb.getLong(at + 16), name(bb, at + 24), bb.getInt(at + 60))
        }
        parts.forEach { if (it.groupIndex !in groups.indices) throw IOException("super partition ${it.name} has a bad group") }
        return Metadata(major, minor, headerSize, b.copyOfRange(128, headerSize), parts, groups, devs)
    }

    /** Same byte layout liblp writes: header, then partitions, extents, groups, block devices. */
    fun serialize(m: Metadata): ByteArray {
        val extents = m.partitions.flatMap { it.extents }
        val tablesSize = m.partitions.size * PART_SIZE + extents.size * EXTENT_SIZE +
            m.groups.size * GROUP_SIZE + m.blockDevices.size * BLOCKDEV_SIZE
        val out = ByteArray(m.headerSize + tablesSize)
        val bb = le(out)
        fun putName(at: Int, s: String) {
            val raw = s.toByteArray(Charsets.US_ASCII)
            if (raw.size >= NAME_LEN) throw IOException("name too long: $s")
            for (i in raw.indices) out[at + i] = raw[i]
        }
        var t = m.headerSize
        val pOff = 0
        var first = 0
        for (p in m.partitions) {
            putName(t, p.name)
            bb.putInt(t + 36, p.attributes)
            bb.putInt(t + 40, first)
            bb.putInt(t + 44, p.extents.size)
            bb.putInt(t + 48, p.groupIndex)
            first += p.extents.size
            t += PART_SIZE
        }
        val eOff = t - m.headerSize
        for (e in extents) {
            bb.putLong(t, e.numSectors); bb.putInt(t + 8, e.targetType); bb.putLong(t + 12, e.targetData); bb.putInt(t + 20, e.targetSource)
            t += EXTENT_SIZE
        }
        val gOff = t - m.headerSize
        for (g in m.groups) {
            putName(t, g.name); bb.putInt(t + 36, g.flags); bb.putLong(t + 40, g.maxSize)
            t += GROUP_SIZE
        }
        val dOff = t - m.headerSize
        for (d in m.blockDevices) {
            bb.putLong(t, d.firstLogicalSector); bb.putInt(t + 8, d.alignment); bb.putInt(t + 12, d.alignmentOffset)
            bb.putLong(t + 16, d.size); putName(t + 24, d.name); bb.putInt(t + 60, d.flags)
            t += BLOCKDEV_SIZE
        }
        // header
        bb.putInt(0, HEADER_MAGIC)
        bb.putShort(4, m.major.toShort())
        bb.putShort(6, m.minor.toShort())
        bb.putInt(8, m.headerSize)
        bb.putInt(44, tablesSize)
        sha256(out, m.headerSize, tablesSize).copyInto(out, 48)
        fun putDesc(at: Int, off: Int, num: Int, sz: Int) { bb.putInt(at, off); bb.putInt(at + 4, num); bb.putInt(at + 8, sz) }
        putDesc(80, pOff, m.partitions.size, PART_SIZE)
        putDesc(92, eOff, extents.size, EXTENT_SIZE)
        putDesc(104, gOff, m.groups.size, GROUP_SIZE)
        putDesc(116, dOff, m.blockDevices.size, BLOCKDEV_SIZE)
        m.headerTail.copyInto(out, 128)
        // header checksum last, over the header with the checksum field zeroed
        sha256(out, 0, m.headerSize).copyInto(out, 12)
        return out
    }

    /** Byte ranges [start, end) on super taken by `parts`. */
    fun usedRanges(parts: Iterable<Partition>): List<LongRange> =
        parts.flatMap { p -> p.extents.filter { it.targetType == TARGET_LINEAR && it.numSectors > 0 }.map { (it.targetData * SECTOR) until (it.end * SECTOR) } }

    fun overlaps(a: LongRange, b: LongRange) = a.first <= b.last && b.first <= a.last
}
