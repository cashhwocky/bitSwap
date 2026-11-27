package com.bitdroid.swap

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Unpacks one payload operation's data (same decoders as bitFlash's Payload.kt). */
object OpData {

    fun decode(op: Payload.Op, raw: ByteArray, expected: Int): ByteArray {
        op.sha256?.let { want ->
            val got = MessageDigest.getInstance("SHA-256").digest(raw)
            if (!got.contentEquals(want)) throw IOException("The ROM zip is damaged (a data block's checksum is wrong). Download it again.")
        }
        val data = when (op.type) {
            Payload.REPLACE -> raw
            Payload.REPLACE_XZ -> inflate(org.tukaani.xz.XZInputStream(ByteArrayInputStream(raw)), expected)
            Payload.REPLACE_BZ -> inflate(org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(ByteArrayInputStream(raw)), expected)
            Payload.ZSTD -> inflate(com.github.luben.zstd.ZstdInputStream(ByteArrayInputStream(raw)), expected)
            else -> throw IOException("unexpected operation type ${op.type}")
        }
        if (data.size != expected) throw IOException("A data block unpacked to ${data.size} bytes, expected $expected. Bad ROM zip?")
        return data
    }

    private fun inflate(s: InputStream, expected: Int): ByteArray = s.use { st ->
        val out = ByteArray(expected)
        var got = 0
        while (got < expected) {
            val n = st.read(out, got, expected - got)
            if (n < 0) break
            got += n
        }
        if (got < expected) return out.copyOf(got)
        if (st.read() >= 0) throw IOException("A data block unpacked bigger than expected. Bad ROM zip?")
        out
    }
}
