package com.bitdroid.swap

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Builds the dm-verity hash tree and FEC (error correction) for a partition, right on the phone.
 *
 * ROM payloads usually leave these out to save space; update_engine computes them after writing.
 * bitSwap has to do the same or the partition won't match the ROM's hash (and won't boot).
 * This follows update_engine's HashTreeBuilder / VerityWriterAndroid::EncodeFEC and avbtool,
 * so the result is byte-for-byte what they produce.
 */
object Verity {

    /** Random access to one partition, offsets are bytes inside the partition. */
    interface Io {
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int)
        fun write(pos: Long, buf: ByteArray, off: Int, len: Int)
    }

    // ---------------- hash tree ----------------

    fun hashTree(io: Io, bs: Int, dataStartBlock: Long, dataBlocks: Long, treeStartBlock: Long, treeBlocks: Long,
                 algorithm: String, salt: ByteArray, progress: (Float) -> Unit) {
        val alg = when (algorithm.lowercase()) {
            "sha256" -> "SHA-256"
            else -> throw IOException("Hash tree algorithm '$algorithm' isn't supported (only sha256).")
        }
        val md = MessageDigest.getInstance(alg)
        val ds = md.digestLength // 32: a power of two, so no digest padding
        val dataSize = dataBlocks * bs
        if (dataSize <= bs) throw IOException("partition too small for a hash tree")

        // level 0: one digest per data block
        val leafCount = dataBlocks
        val l0Size = roundUp(leafCount * ds, bs.toLong())
        if (l0Size > Int.MAX_VALUE) throw IOException("hash tree too big")
        var level = ByteArray(l0Size.toInt())
        val chunkBlocks = 1024
        val buf = ByteArray(chunkBlocks * bs)
        var b = 0L
        while (b < dataBlocks) {
            val n = minOf(chunkBlocks.toLong(), dataBlocks - b).toInt()
            io.read((dataStartBlock + b) * bs, buf, 0, n * bs)
            for (i in 0 until n) {
                md.reset(); md.update(salt); md.update(buf, i * bs, bs)
                md.digest(level, ((b + i) * ds).toInt(), ds)
            }
            b += n
            progress(0.9f * b / dataBlocks)
        }
        val levels = mutableListOf(level)
        while (level.size > bs) {
            val blocks = level.size / bs
            val next = ByteArray(roundUp(blocks.toLong() * ds, bs.toLong()).toInt())
            for (i in 0 until blocks) {
                md.reset(); md.update(salt); md.update(level, i * bs, bs)
                md.digest(next, i * ds, ds)
            }
            level = next
            levels += level
        }
        // stored top level first, leaves last
        val total = levels.sumOf { it.size.toLong() }
        if (total != treeBlocks * bs) {
            throw IOException("Hash tree size doesn't match the ROM (built $total bytes, ROM expects ${treeBlocks * bs}).")
        }
        var pos = treeStartBlock * bs
        for (l in levels.asReversed()) {
            var o = 0
            while (o < l.size) {
                val n = minOf(1 shl 20, l.size - o)
                io.write(pos + o, l, o, n)
                o += n
            }
            pos += l.size
        }
        progress(1f)
    }

    // ---------------- FEC (Reed-Solomon, libfec parameters) ----------------

    /** Reed-Solomon over GF(2^8), poly 0x11d, fcr 0, prim 1 (libfec's FEC_PARAMS). Phil Karn's encoder. */
    class Rs(val nroots: Int) {
        val nn = 255
        val alphaTo = IntArray(256)
        val indexOf = IntArray(256)
        val genpoly = IntArray(nroots + 1)
        private val mod = IntArray(512) { it % 255 }

        init {
            require(nroots in 1..254)
            indexOf[0] = nn
            alphaTo[nn] = 0
            var sr = 1
            for (i in 0 until nn) {
                indexOf[sr] = i
                alphaTo[i] = sr
                sr = sr shl 1
                if (sr and 0x100 != 0) sr = sr xor 0x11d
                sr = sr and nn
            }
            if (sr != 1) throw IllegalStateException("bad field polynomial")
            genpoly[0] = 1
            var root = 0 // fcr * prim
            for (i in 0 until nroots) {
                genpoly[i + 1] = 1
                for (j in i downTo 1) {
                    genpoly[j] = if (genpoly[j] != 0) genpoly[j - 1] xor alphaTo[modnn(indexOf[genpoly[j]] + root)] else genpoly[j - 1]
                }
                genpoly[0] = alphaTo[modnn(indexOf[genpoly[0]] + root)]
                root += 1 // prim
            }
            for (i in 0..nroots) genpoly[i] = indexOf[genpoly[i]]
        }

        private fun modnn(x0: Int): Int {
            var x = x0
            while (x >= nn) { x -= nn; x = (x shr 8) + (x and nn) }
            return x
        }

        /**
         * Encodes one codeword. Data symbol j is `data[base + j * stride]` for j in 0 until (255 - nroots).
         * Parity goes to out[outOff until outOff + nroots]. `bb` is scratch of size nroots.
         */
        fun encode(data: Array<ByteArray>, pos: Int, out: ByteArray, outOff: Int, bb: IntArray) {
            val k = nn - nroots
            java.util.Arrays.fill(bb, 0)
            for (i in 0 until k) {
                val feedback = indexOf[(data[i][pos].toInt() and 0xff) xor bb[0]]
                if (feedback != nn) {
                    for (j in 1 until nroots) bb[j] = bb[j] xor alphaTo[mod[feedback + genpoly[nroots - j]]]
                }
                System.arraycopy(bb, 1, bb, 0, nroots - 1)
                bb[nroots - 1] = if (feedback != nn) alphaTo[mod[feedback + genpoly[0]]] else 0
            }
            for (j in 0 until nroots) out[outOff + j] = bb[j].toByte()
        }
    }

    fun fec(io: Io, bs: Int, dataStartBlock: Long, dataBlocks: Long, fecStartBlock: Long, fecBlocks: Long, roots: Int,
            threads: Int, progress: (Float) -> Unit) {
        if (roots !in 2..24) throw IOException("FEC roots $roots isn't supported")
        val rsn = 255 - roots
        val rounds = (dataBlocks + rsn - 1) / rsn
        if (rounds * roots != fecBlocks) {
            throw IOException("FEC size doesn't match the ROM (expects $fecBlocks blocks, works out to ${rounds * roots}).")
        }
        val dataSize = dataBlocks * bs
        val rs = Rs(roots)
        val batch = 16
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 8))
        try {
            var i0 = 0L
            while (i0 < rounds) {
                val r = minOf(batch.toLong(), rounds - i0).toInt()
                // chunk[j] holds blocks (j * rounds + i0) .. +r of the input (data + hash tree)
                val chunks = Array(rsn) { ByteArray(r * bs) }
                for (j in 0 until rsn) {
                    val start = (j * rounds + i0) * bs
                    if (start >= dataSize) continue // past the end: zeros
                    val len = minOf(r.toLong() * bs, dataSize - start).toInt()
                    io.read(dataStartBlock * bs + start, chunks[j], 0, len)
                }
                val out = ByteArray(r * bs * roots)
                val futures = mutableListOf<Future<*>>()
                for (rr in 0 until r) {
                    futures += pool.submit {
                        val bb = IntArray(roots)
                        val base = rr * bs
                        for (k in 0 until bs) rs.encode(chunks, base + k, out, (base + k) * roots, bb)
                    }
                }
                futures.forEach { it.get() }
                io.write((fecStartBlock + i0 * roots) * bs, out, 0, out.size)
                i0 += r
                progress(i0.toFloat() / rounds)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun roundUp(x: Long, m: Long) = (x + m - 1) / m * m
}
