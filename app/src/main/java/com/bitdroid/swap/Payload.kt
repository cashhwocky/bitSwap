package com.bitdroid.swap

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads a ROM zip's payload.bin (Android's update_engine format) straight out of the zip.
 * Started from bitFlash's Payload.kt; this version keeps the whole manifest (every partition,
 * hashes, verity settings and the dynamic-partition groups) because bitSwap installs all of it.
 *
 * Layout: "CrAU", version (u64 BE), manifest size (u64 BE), metadata signature size (u32 BE, v2+),
 * the protobuf manifest, the signature, then the data blobs that operations point into.
 */
object Payload {

    const val REPLACE = 0
    const val REPLACE_BZ = 1
    const val ZERO = 6
    const val DISCARD = 7
    const val REPLACE_XZ = 8
    const val ZSTD = 14
    val FULL_OPS = setOf(REPLACE, REPLACE_BZ, REPLACE_XZ, ZSTD, ZERO, DISCARD)

    class Extent(val start: Long, val count: Long) {
        val end get() = start + count
        override fun toString() = "[$start+$count]"
    }

    class Op(val type: Int, val offset: Long, val length: Long, val dst: List<Extent>, val sha256: ByteArray?)

    class Part(
        val name: String,
        val size: Long,
        val hash: ByteArray?,
        val ops: List<Op>,
        val hashTreeData: Extent?,
        val hashTree: Extent?,
        val hashTreeAlgorithm: String?,
        val hashTreeSalt: ByteArray?,
        val fecData: Extent?,
        val fec: Extent?,
        val fecRoots: Int,
    ) {
        val hasVerity get() = hashTree != null && hashTree.count > 0
        val hasFec get() = fec != null && fec.count > 0
    }

    class Group(val name: String, val size: Long, val partitions: List<String>)

    class Manifest(
        val blockSize: Long,
        val minorVersion: Long,
        val partialUpdate: Boolean,
        val parts: List<Part>,
        val groups: List<Group>,
        /** absolute position (in the zip) of the first data blob */
        val dataStart: Long,
        /** absolute position (in the zip) where payload.bin ends */
        val payloadEnd: Long,
    ) {
        val dynamicNames: Set<String> get() = groups.flatMap { it.partitions }.toSet()
    }

    /** Reads and checks the manifest. Throws with a plain-words reason. */
    fun read(ch: FileChannel, zipSize: Long): Manifest {
        val entry = Safety.findZipEntry(ch, zipSize, "payload.bin")
            ?: throw IOException("This ROM zip has no payload.bin. bitSwap needs an A/B ROM zip.")
        if (entry.method != 0) throw IOException("payload.bin is compressed inside this zip, which bitSwap can't read.")
        val base = entry.dataPos
        val head = ByteBuffer.wrap(Safety.readBytes(ch, base, 24)) // big-endian by default
        val magic = ByteArray(4).also { head.get(it) }
        if (String(magic, Charsets.US_ASCII) != "CrAU") throw IOException("payload.bin doesn't look right (bad magic).")
        val version = head.long
        val manifestSize = head.long
        val sigSize = if (version >= 2) head.int.toLong() and 0xffffffffL else 0L
        val headerLen = if (version >= 2) 24L else 20L
        if (manifestSize <= 0 || manifestSize > 32L * 1024 * 1024) throw IOException("payload manifest is an odd size.")
        val manifest = Safety.readBytes(ch, base + headerLen, manifestSize.toInt())
        return parse(manifest, base + headerLen + manifestSize + sigSize, base + entry.size)
    }

    fun parse(manifest: ByteArray, dataStart: Long, payloadEnd: Long): Manifest {
        var blockSize = 4096L
        var minor = 0L
        var partial = false
        val parts = mutableListOf<Part>()
        val groups = mutableListOf<Group>()
        Pb(manifest).forEach { field, wire, pb ->
            when {
                field == 3 && wire == 0 -> blockSize = pb.varint()
                field == 12 && wire == 0 -> minor = pb.varint()
                field == 13 && wire == 2 -> parts += readPart(pb.bytes())
                field == 15 && wire == 2 -> Pb(pb.bytes()).forEach { f, w, p ->
                    if (f == 1 && w == 2) groups += readGroup(p.bytes()) else p.skip(w)
                }
                field == 16 && wire == 0 -> partial = pb.varint() != 0L
                else -> pb.skip(wire)
            }
        }
        return Manifest(blockSize, minor, partial, parts, groups, dataStart, payloadEnd)
    }

    private fun readGroup(buf: ByteArray): Group {
        var name = ""
        var size = 0L
        val names = mutableListOf<String>()
        Pb(buf).forEach { f, w, p ->
            when {
                f == 1 && w == 2 -> name = String(p.bytes(), Charsets.UTF_8)
                f == 2 && w == 0 -> size = p.varint()
                f == 3 && w == 2 -> names += String(p.bytes(), Charsets.UTF_8)
                else -> p.skip(w)
            }
        }
        return Group(name, size, names)
    }

    private fun readExtent(buf: ByteArray): Extent {
        var s = 0L; var c = 0L
        Pb(buf).forEach { f, w, p ->
            when {
                f == 1 && w == 0 -> s = p.varint()
                f == 2 && w == 0 -> c = p.varint()
                else -> p.skip(w)
            }
        }
        return Extent(s, c)
    }

    private fun readPart(buf: ByteArray): Part {
        var name = ""
        var size = 0L
        var hash: ByteArray? = null
        val ops = mutableListOf<Op>()
        var htData: Extent? = null
        var ht: Extent? = null
        var htAlg: String? = null
        var htSalt: ByteArray? = null
        var fecData: Extent? = null
        var fec: Extent? = null
        var fecRoots = 2
        Pb(buf).forEach { field, wire, pb ->
            when {
                field == 1 && wire == 2 -> name = String(pb.bytes(), Charsets.UTF_8)
                field == 7 && wire == 2 -> Pb(pb.bytes()).forEach { f, w, p ->
                    when {
                        f == 1 && w == 0 -> size = p.varint()
                        f == 2 && w == 2 -> hash = p.bytes()
                        else -> p.skip(w)
                    }
                }
                field == 8 && wire == 2 -> ops += readOp(pb.bytes())
                field == 10 && wire == 2 -> htData = readExtent(pb.bytes())
                field == 11 && wire == 2 -> ht = readExtent(pb.bytes())
                field == 12 && wire == 2 -> htAlg = String(pb.bytes(), Charsets.UTF_8)
                field == 13 && wire == 2 -> htSalt = pb.bytes()
                field == 14 && wire == 2 -> fecData = readExtent(pb.bytes())
                field == 15 && wire == 2 -> fec = readExtent(pb.bytes())
                field == 16 && wire == 0 -> fecRoots = pb.varint().toInt()
                else -> pb.skip(wire)
            }
        }
        return Part(name, size, hash, ops, htData, ht, htAlg, htSalt, fecData, fec, fecRoots)
    }

    private fun readOp(buf: ByteArray): Op {
        var type = -1
        var off = 0L
        var len = 0L
        var sha: ByteArray? = null
        val dst = mutableListOf<Extent>()
        Pb(buf).forEach { field, wire, pb ->
            when {
                field == 1 && wire == 0 -> type = pb.varint().toInt()
                field == 2 && wire == 0 -> off = pb.varint()
                field == 3 && wire == 0 -> len = pb.varint()
                field == 6 && wire == 2 -> dst += readExtent(pb.bytes())
                field == 8 && wire == 2 -> sha = pb.bytes()
                else -> pb.skip(wire)
            }
        }
        return Op(type, off, len, dst, sha)
    }

    /** A tiny protobuf reader: just enough for the payload manifest. */
    class Pb(private val b: ByteArray) {
        private var p = 0

        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                if (p >= b.size) throw IOException("manifest ended early")
                val x = b[p++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
                if (shift > 63) throw IOException("bad varint")
            }
        }

        fun bytes(): ByteArray {
            val n = varint().toInt()
            if (n < 0 || p + n > b.size) throw IOException("manifest field out of range")
            val out = b.copyOfRange(p, p + n)
            p += n
            return out
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                2 -> { val n = varint().toInt(); if (n < 0 || p + n > b.size) throw IOException("manifest field out of range"); p += n }
                5 -> p += 4
                else -> throw IOException("unknown protobuf wire type $wire")
            }
        }

        fun forEach(block: (field: Int, wire: Int, pb: Pb) -> Unit) {
            while (p < b.size) {
                val key = varint()
                block((key ushr 3).toInt(), (key and 7).toInt(), this)
            }
        }
    }
}
