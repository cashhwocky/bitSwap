package com.bitdroid.swap

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.BitSet
import java.util.Date
import java.util.Locale

class PickedFile(val uri: Uri, val name: String, val size: Long)

enum class StepState { WAITING, RUNNING, DONE, FAILED, SKIPPED }

class Step(val title: String) {
    var state by mutableStateOf(StepState.WAITING)
    var detail by mutableStateOf("")
}

/** What we learned from the ROM zip before touching anything. */
class RomSummary(
    val info: Safety.RomInfo,
    val manifest: Payload.Manifest,
    val dynamic: List<Payload.Part>,
    val plain: List<Payload.Part>,
    val skipped: List<String>,
) {
    val totalBytes get() = (dynamic + plain).sumOf { it.size }
}

@SuppressLint("StaticFieldLeak")
object Swapper {
    private lateinit var ctx: Context
    private val main = Handler(Looper.getMainLooper())

    // ---- UI state ----
    var root by mutableStateOf<RootClient.Root?>(null); private set
    var checkingRoot by mutableStateOf(false); private set
    val codename: String = Build.DEVICE ?: ""
    val profile: Safety.DeviceProfile? get() = Safety.DEVICES[codename]
    var slot by mutableStateOf(""); private set
    var rom by mutableStateOf<PickedFile?>(null); private set
    var summary by mutableStateOf<RomSummary?>(null); private set
    var romError by mutableStateOf<String?>(null); private set
    var checkingRom by mutableStateOf(false); private set

    var busy by mutableStateOf(false); private set
    var busyLabel by mutableStateOf(""); private set
    var progress by mutableStateOf<Float?>(null); private set
    var progressDetail by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null)
    var finished by mutableStateOf(false); private set
    /** Advanced: keep going even if firmware differs between slots. Off every time the app starts. */
    var ignoreFirmware by mutableStateOf(false)
    /** Patch the new ROM's boot image with Magisk so it boots rooted. On by default. */
    var rootNewRom by mutableStateOf(true)
    val magiskReady: Boolean get() = ::ctx.isInitialized && Magisk.available(ctx)
    val magiskVersion: String get() = if (::ctx.isInitialized) Magisk.version(ctx) else "?"
    /** init_boot on the 10s, boot on the 6a (whichever holds the ramdisk Magisk patches). */
    private fun patchTarget(plain: List<Payload.Part>): String =
        if (plain.any { it.name == "init_boot" }) "init_boot" else "boot"
    val logLines = mutableStateListOf<String>()

    val steps = listOf(
        Step("Check root"),                                 // 0
        Step("Check this phone"),                           // 1
        Step("Check the ROM zip"),                          // 2
        Step("Stage the ROM + trust its signing key"),      // 3
        Step("Install with Android's updater"),             // 4
        Step("Root the new ROM"),                           // 5
        Step("Schedule format data"),                       // 6
        Step("Reboot"),                                     // 7
    )

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        slot = runCatching {
            val p = ProcessBuilder("getprop", "ro.boot.slot_suffix").start()
            p.inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")
        log("bitSwap ready. ${profile?.name ?: "Unsupported phone"} ($codename), running slot ${slot.ifBlank { "?" }}.")
        checkRoot()
    }

    fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        main.post { logLines += "$t  $s"; if (logLines.size > 2000) logLines.subList(0, 500).clear() }
    }

    private fun setProgress(p: Float?, detail: String? = null) = main.post {
        progress = p?.coerceIn(0f, 1f)
        if (detail != null) progressDetail = detail
    }

    private fun step(i: Int, s: StepState, detail: String = "") {
        main.post { steps[i].state = s; if (detail.isNotEmpty() || s == StepState.RUNNING) steps[i].detail = detail }
        if (detail.isNotEmpty()) log((if (s == StepState.FAILED) "✕ " else "• ") + "${steps[i].title}: $detail")
    }

    fun checkRoot() {
        if (checkingRoot) return
        checkingRoot = true
        Thread {
            val r = RootClient.check()
            log(when (r) {
                RootClient.Root.OK -> "Root: yes (Magisk allowed bitSwap)."
                RootClient.Root.NO_SU -> "Root: no su found. This phone isn't rooted."
                RootClient.Root.DENIED -> "Root: su is there but said no. Allow bitSwap in Magisk → Superuser."
            })
            main.post { root = r; checkingRoot = false }
        }.start()
    }

    // ---------------- ROM zip ----------------

    fun describe(uri: Uri): PickedFile {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
            }
        }
        return PickedFile(uri, name, size)
    }

    fun pickRom(uri: Uri) {
        val f = describe(uri)
        rom = f; summary = null; romError = null; finished = false
        steps.forEach { it.state = StepState.WAITING; it.detail = "" }
        checkingRom = true
        Thread {
            try {
                val s = openZip(f) { ch, size -> analyse(ch, size) }
                log("ROM: ${f.name}: ${s.info.build.ifBlank { "build ?" }}, SDK ${s.info.sdk}, ${s.dynamic.size} system + ${s.plain.size} boot partitions, ${Planner.gb(s.totalBytes)}.")
                if (s.skipped.isNotEmpty()) log("Will skip (never touched): ${s.skipped.joinToString(", ")}")
                main.post { summary = s }
            } catch (e: Exception) {
                log("✕ ROM zip: ${e.message}")
                main.post { romError = e.message ?: e.javaClass.simpleName }
            } finally {
                main.post { checkingRom = false }
            }
        }.start()
    }

    fun clearRom() { rom = null; summary = null; romError = null }

    private fun <T> openZip(f: PickedFile, block: (FileChannel, Long) -> T): T {
        val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(f.uri, "r") ?: throw IOException("Can't open ${f.name}")
        return pfd.use {
            FileInputStream(it.fileDescriptor).use { fis ->
                val ch = fis.channel
                val size = if (it.statSize > 0) it.statSize else ch.size()
                block(ch, size)
            }
        }
    }

    /** Everything we can check from the zip alone. Throws with a plain reason. */
    private fun analyse(ch: FileChannel, size: Long): RomSummary {
        if (profile == null) throw SecurityException("This phone ($codename) isn't supported. bitSwap works on Pixel 10, 10 Pro, 10 Pro XL and 6a.")
        val info = Safety.checkRomZip(ch, size, codename)
        val m = Payload.read(ch, size)
        if (m.minorVersion != 0L) throw SecurityException("This is an incremental update, not a full ROM. Use the full ROM zip.")
        if (m.partialUpdate) throw SecurityException("This is a partial update (not every partition). Use the full ROM zip.")
        if (m.blockSize != 4096L) throw IOException("Odd payload block size ${m.blockSize}.")
        if (m.groups.isEmpty()) throw IOException("This ROM zip has no dynamic partition info, so bitSwap can't lay out super.")
        val dyn = m.dynamicNames
        val dynamic = mutableListOf<Payload.Part>()
        val plain = mutableListOf<Payload.Part>()
        val skipped = mutableListOf<String>()
        for (p in m.parts) {
            for (op in p.ops) if (op.type !in Payload.FULL_OPS) {
                throw SecurityException("${p.name} in this zip is an incremental update (operation ${op.type}). Use the full ROM zip.")
            }
            when {
                p.name in Safety.NEVER -> skipped += p.name
                p.name in dyn -> dynamic += p
                p.name in Safety.OS_PLAIN -> plain += p
                else -> skipped += p.name
            }
        }
        for (p in dynamic + plain) {
            if (p.size <= 0) throw IOException("${p.name} has no size in this zip.")
            if (p.hash == null || p.hash.size != 32) throw IOException("${p.name} has no checksum in this zip, so bitSwap can't verify it.")
            if (p.hasVerity && p.hashTreeData == null) throw IOException("${p.name} has a hash tree but no data range.")
            if (p.hasFec && p.fecData == null) throw IOException("${p.name} has FEC but no data range.")
            if (p.hasVerity && p.hashTreeAlgorithm?.lowercase() != "sha256") throw IOException("${p.name} uses hash tree '${p.hashTreeAlgorithm}', which bitSwap can't build.")
            if ((p.name + "_b").length > 35) throw IOException("partition name ${p.name} is too long")
        }
        dyn.filter { n -> m.parts.none { it.name == n } }.takeIf { it.isNotEmpty() }?.let {
            throw IOException("The zip lists ${it.joinToString()} but has no data for them.")
        }
        if (dynamic.none { it.name == "system" }) throw IOException("This ROM has no system partition.")
        if (plain.none { it.name == "boot" }) throw IOException("This ROM has no boot image.")
        if (plain.none { it.name == "vbmeta" }) throw IOException("This ROM has no vbmeta.")
        return RomSummary(info, m, dynamic, plain, skipped)
    }

    // ---------------- the switch ----------------

    fun switch() {
        val f = rom ?: return
        if (busy) return
        busy = true; busyLabel = "Switching"; progress = null; finished = false
        steps.forEach { it.state = StepState.WAITING; it.detail = "" }
        Thread {
            var cur = 0
            var rc: RootClient? = null
            var switched = false
            try {
                // 0. root
                cur = 0; step(0, StepState.RUNNING, "Asking Magisk for root…")
                if (RootClient.check() != RootClient.Root.OK) throw SecurityException("No root. bitSwap needs Magisk root to write partitions.")
                val client = RootClient.start(ctx, ::log)
                rc = client
                client.onLog = { log("   $it") }
                step(0, StepState.DONE, "Root helper running ✓")

                // 1. phone
                cur = 1; step(1, StepState.RUNNING, "Reading the phone's slots and state…")
                val info = client.info()
                val dev = info["device"].orEmpty()
                if (dev != codename || Safety.DEVICES[dev] == null) throw SecurityException("This phone ($dev) isn't supported.")
                val current = info["slot"].orEmpty()
                val target = info["target"].orEmpty()
                if (current !in listOf("_a", "_b") || target != Safety.otherSuffix(current)) throw IOException("Couldn't tell which slot the phone is on.")
                if (info["ue"] != "yes") throw SecurityException("This ROM has no update_engine, which bitSwap needs to install. Use bitFlash instead.")
                val ueSt = info["ue_status"].orEmpty()
                if (ueSt != "IDLE" && ueSt != "UPDATED_NEED_REBOOT" && ueSt != "UNKNOWN") {
                    throw SecurityException("Android's updater is busy (state: $ueSt). Reboot and try again.")
                }
                val freeMb = info["data_free_mb"]?.toLongOrNull() ?: -1
                if (freeMb in 0 until 6000) log("⚠ Only ${freeMb} MB free on /data. Staging the ROM needs a few GB; it may run out.")
                step(1, StepState.DONE, "${Safety.DEVICES[dev]!!.name}, running slot $current → installing to slot $target")

                lateinit var sum: RomSummary
                openZip(f) { ch, size ->
                    // 2. zip
                    cur = 2; step(2, StepState.RUNNING, "Checking ${f.name}…")
                    val s = analyse(ch, size)
                    sum = s
                    val props = Safety.readTextEntry(ch, size, "payload_properties.txt")
                        ?: throw IOException("This ROM zip has no payload_properties.txt, so Android's updater can't install it. Is it a full A/B ROM zip?")
                    val payload = Safety.findZipEntry(ch, size, "payload.bin")!!
                    val cert = extractSigningKey(ch, size)
                    step(2, StepState.DONE, "For ${s.info.device}, SDK ${s.info.sdk}, ${Planner.gb(s.totalBytes)}" +
                        (if (cert == null) " — no signing cert found (updater may reject it)" else " ✓"))

                    // 3. stage payload + trust the ROM's signing key
                    cur = 3; step(3, StepState.RUNNING, "Copying payload.bin out of the zip…")
                    val staged = File(ctx.cacheDir, "payload.bin")
                    client.onProgress = {}
                    copyRange(ch, payload.dataPos, payload.size, staged) { fr -> setProgress(0.0f + 0.9f * fr, "staging payload ${(fr * 100).toInt()}%") }
                    main.post { steps[3].detail = "Handing it to the system updater…" }
                    val stagedSize = client.stage(staged.absolutePath)
                    runCatching { staged.delete() }
                    if (cert != null) {
                        val res = client.installKey(cert)
                        log("Trusting the ROM's signing key so update_engine accepts it: $res")
                    }
                    step(3, StepState.DONE, "Staged ${Planner.gb(stagedSize)}" + (if (cert != null) ", ROM's key trusted ✓" else " ✓"))

                    // 4. install via update_engine
                    cur = 4; step(4, StepState.RUNNING, "Android's updater is installing the new ROM to slot $target…")
                    client.onProgress = { p -> setProgress(p, "installing ${(p * 100).toInt()}%") }
                    client.ota(stagedSize, props)
                    switched = true // update_engine marks the new slot active on success
                    runCatching { client.cmd("CLEANUP") }
                    step(4, StepState.DONE, "Installed to slot $target, and the updater set it active ✓")

                    // 5. root the new ROM (patch its init_boot on the target slot)
                    cur = 5
                    val patchName = patchTarget(s.plain)
                    if (rootNewRom && Magisk.available(ctx) && s.plain.any { it.name == patchName }) {
                        step(5, StepState.RUNNING, "Patching $patchName with Magisk so the new ROM boots rooted…")
                        // the install already succeeded, so don't fail the whole switch if rooting trips
                        try {
                            writeRooted(client, ch, s.manifest, s.plain.first { it.name == patchName }) { frac, what ->
                                setProgress(frac.toFloat(), "$patchName: $what")
                            }
                            step(5, StepState.DONE, "$patchName patched ✓ (new ROM will be rooted)")
                        } catch (e: Exception) {
                            log("⚠ Couldn't root the new ROM (${e.message}). Installing it unrooted; you can root it later with bitFlash.")
                            step(5, StepState.SKIPPED, "Rooting skipped (${e.message}). The ROM is installed; it'll be unrooted.")
                        }
                    } else {
                        val why = if (!rootNewRom) "you turned it off" else if (!Magisk.available(ctx)) "no Magisk in this build" else "no $patchName"
                        step(5, StepState.SKIPPED, "Skipped ($why). The new ROM will be unrooted.")
                    }
                }

                // 6. wipe
                cur = 6; step(6, StepState.RUNNING, "Telling recovery to format data on next boot…")
                try {
                    client.cmd("WIPE")
                    step(6, StepState.DONE, "Recovery will format data, then start the new ROM ✓")
                } catch (e: Exception) {
                    // install already done + slot active; without the wipe it'll boot the new ROM with old data
                    log("⚠ Couldn't schedule the format (${e.message}). The new ROM is installed and active; it'll boot with your old data (may need a manual wipe in recovery).")
                    step(6, StepState.SKIPPED, "Format not scheduled (${e.message}); new ROM still installed.")
                }

                // 7. reboot
                cur = 7; step(7, StepState.RUNNING, "Rebooting…")
                setProgress(1f, "Rebooting")
                main.post { finished = true }
                Thread.sleep(1500)
                client.cmd("REBOOT")
                step(7, StepState.DONE, "Rebooting into recovery: it formats data, then the new ROM starts. First boot takes a while (it finishes the install). If it doesn't restart, restart it yourself.")
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                step(cur, StepState.FAILED, why)
                val safe = if (switched) "\n\nThe new ROM was already installed before this, so it may boot on the next restart."
                           else "\n\nNothing was installed. Your phone is still on the ROM you're running now (slot ${slot.ifBlank { "?" }}) and will boot it normally."
                main.post { error = why + safe }
            } finally {
                runCatching { rc?.close() }
                main.post { busy = false; busyLabel = ""; progressDetail = ""; if (!finished) progress = null }
            }
        }.start()
    }

    /**
     * Writes a boot image (init_boot / boot) to the other slot after patching it with Magisk,
     * so the new ROM comes up rooted. The extracted image is checked against the ROM's hash first;
     * the written partition is checked against the patched image's hash (it won't match the ROM's,
     * because Magisk changed it). Boot images have no dm-verity hash tree / FEC, so none is built.
     */
    private fun writeRooted(rc: RootClient, ch: FileChannel, m: Payload.Manifest, p: Payload.Part, prog: (Double, String) -> Unit) {
        if (p.hasVerity || p.hasFec) throw IOException("${p.name} has a hash tree; bitSwap won't Magisk-patch that.")
        rc.onProgress = {}
        prog(0.0, "reading ${p.name} from the ROM")
        val stock = File(ctx.cacheDir, "stock_${p.name}.img")
        try {
            extractImage(ch, m, p) { f -> prog(0.35 * f, "reading ${p.name} from the ROM") }
            // the extracted image must match the ROM before we touch it
            val got = sha256File(stock)
            if (!got.contentEquals(p.hash)) throw IOException("${p.name} came out of the zip wrong (checksum mismatch). Stopped, nothing switched.")
            prog(0.4, "patching with Magisk")
            log("Patching ${p.name} with Magisk ${Magisk.version(ctx)} so the new ROM boots rooted…")
            val patched = Magisk.patch(ctx, stock, ::log)
            val psize = rc.cmd("PLAINSIZE ${p.name}").toLong()
            if (patched.length() > psize) throw IOException("The patched ${p.name} (${patched.length()}) is bigger than the phone's ${p.name} partition ($psize).")
            // write the patched image, then zero the rest of the partition
            prog(0.6, "writing rooted ${p.name}")
            val buf = ByteArray(8 shl 20)
            patched.inputStream().use { ins ->
                var pos = 0L
                while (true) {
                    val n = ins.read(buf); if (n <= 0) break
                    rc.write(p.name, pos, buf, 0, n); pos += n
                    prog(0.6 + 0.25 * pos / patched.length(), "writing rooted ${p.name}")
                }
            }
            if (patched.length() < psize) rc.cmd("ZERO ${p.name} ${patched.length()} ${psize - patched.length()}")
            // verify what landed on the partition == the patched image
            prog(0.9, "verifying")
            rc.onProgress = { f -> prog(0.9 + 0.1 * f, "verifying") }
            val onDisk = rc.cmd("HASH ${p.name} ${patched.length()}")
            val want = sha256File(patched).joinToString("") { "%02x".format(it) }
            if (!onDisk.equals(want, ignoreCase = true)) throw IOException("The rooted ${p.name} didn't verify after writing. Stopped before switching; your running ROM is untouched.")
            rc.onProgress = {}
            log("   ${p.name}: patched, written and verified ✓ (new ROM will be rooted)")
        } finally {
            runCatching { stock.delete() }
            runCatching { File(ctx.filesDir, "magisk-work").deleteRecursively() }
        }
    }

    /** Writes partition [p]'s full image (data ops only — boot images have no verity) to a local file. */
    private fun extractImage(ch: FileChannel, m: Payload.Manifest, p: Payload.Part, prog: (Double) -> Unit) {
        val bs = m.blockSize
        val out = File(ctx.cacheDir, "stock_${p.name}.img")
        java.io.RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0); if (p.size > 0) raf.setLength(p.size)
            for ((i, op) in p.ops.withIndex()) {
                val dstBytes = op.dst.sumOf { it.count } * bs
                for (e in op.dst) if (e.end * bs > p.size) throw IOException("${p.name}: data past the end. Bad ROM zip?")
                when (op.type) {
                    Payload.ZERO, Payload.DISCARD -> {}
                    else -> {
                        val at = m.dataStart + op.offset
                        if (op.length <= 0 || op.length > 256L * 1024 * 1024 || at + op.length > m.payloadEnd) throw IOException("${p.name}: data block out of range.")
                        val data = OpData.decode(op, Safety.readBytes(ch, at, op.length.toInt()), dstBytes.toInt())
                        var off = 0
                        for (e in op.dst) {
                            val n = (e.count * bs).toInt()
                            raf.seek(e.start * bs); raf.write(data, off, n); off += n
                        }
                    }
                }
                prog((i + 1.0) / p.ops.size)
            }
        }
    }

    /** Copies [len] bytes at [pos] in the zip (payload.bin is stored, so this is a raw copy) to [out]. */
    private fun copyRange(ch: FileChannel, pos: Long, len: Long, out: File, prog: (Float) -> Unit) {
        java.io.RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val buf = java.nio.ByteBuffer.allocate(8 shl 20)
            var p = pos; var done = 0L
            while (done < len) {
                buf.clear(); buf.limit(minOf(buf.capacity().toLong(), len - done).toInt())
                var r = 0
                while (buf.hasRemaining()) { val n = ch.read(buf, p + r); if (n < 0) throw IOException("ROM zip ended early"); r += n }
                buf.flip(); raf.channel.write(buf)
                done += r; p += r; prog(done.toFloat() / len)
            }
        }
    }

    /**
     * Pulls the ROM's signing certificate out of the zip and returns its public key as a PEM, so the
     * root side can make update_engine verify this ROM's payload with its own key (a different ROM is
     * signed with a different key, which is exactly why Android's updater would otherwise reject it).
     */
    private fun extractSigningKey(ch: FileChannel, size: Long): ByteArray? {
        val certDer = (Safety.readEntryBytes(ch, size, "META-INF/com/android/otacert")
            ?: Safety.readEntryBytes(ch, size, "META-INF/CERT.RSA")) ?: return null
        return runCatching {
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            // otacert is a bare X.509; CERT.RSA is PKCS#7 — generateCertificates handles both
            val cert = cf.generateCertificates(java.io.ByteArrayInputStream(certDer)).firstOrNull()
                ?: return@runCatching null
            val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.publicKey.encoded)
            ("-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n").toByteArray()
        }.getOrNull()
    }

    private fun sha256File(f: File): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val b = ByteArray(1 shl 20)
        f.inputStream().use { while (true) { val n = it.read(b); if (n <= 0) break; md.update(b, 0, n) } }
        return md.digest()
    }

    /** Writes one partition to the other slot, builds verity if needed, then checks its hash. */
    private fun writePart(rc: RootClient, ch: FileChannel, m: Payload.Manifest, p: Payload.Part, prog: (Double, String) -> Unit) {
        val bs = m.blockSize
        val blocks = ((p.size + bs - 1) / bs)
        if (blocks > Int.MAX_VALUE) throw IOException("${p.name} too big")
        val covered = BitSet(blocks.toInt())
        val verityShare = if (p.hasVerity || p.hasFec) 0.25 else 0.0
        val writeShare = 0.9 - verityShare
        rc.onProgress = {}
        prog(0.0, "writing")
        for ((i, op) in p.ops.withIndex()) {
            val dstBytes = op.dst.sumOf { it.count } * bs
            for (e in op.dst) if (e.end > blocks) throw IOException("${p.name}: data goes past the partition's end. Bad ROM zip?")
            when (op.type) {
                Payload.ZERO, Payload.DISCARD -> for (e in op.dst) rc.cmd("ZERO ${p.name} ${e.start * bs} ${e.count * bs}")
                else -> {
                    val at = m.dataStart + op.offset
                    if (op.length <= 0 || op.length > 256L * 1024 * 1024 || at + op.length > m.payloadEnd) throw IOException("${p.name}: data block out of range. Bad ROM zip?")
                    if (dstBytes > 256L * 1024 * 1024) throw IOException("${p.name}: data block too big")
                    val raw = Safety.readBytes(ch, at, op.length.toInt())
                    val data = OpData.decode(op, raw, dstBytes.toInt())
                    var off = 0
                    for (e in op.dst) {
                        var left = (e.count * bs).toInt()
                        var pos = e.start * bs
                        while (left > 0) {
                            val n = minOf(left, 8 shl 20)
                            rc.write(p.name, pos, data, off, n)
                            off += n; pos += n; left -= n
                        }
                    }
                }
            }
            for (e in op.dst) covered.set(e.start.toInt(), e.end.toInt())
            prog(writeShare * (i + 1) / p.ops.size, "writing")
        }
        // blocks no operation wrote (except where the hash tree / FEC will go): zero them, like a fresh image
        val n = blocks.toInt()
        val need = BitSet(n).apply { set(0, n); andNot(covered) }
        listOfNotNull(p.hashTree, p.fec).forEach { e -> need.clear(minOf(e.start, blocks).toInt(), minOf(e.end, blocks).toInt()) }
        var b = need.nextSetBit(0)
        while (b in 0 until n) {
            val e = need.nextClearBit(b).coerceAtMost(n)
            rc.cmd("ZERO ${p.name} ${b * bs} ${(e - b) * bs}")
            b = need.nextSetBit(e)
        }
        // verity
        if (p.hasVerity || p.hasFec) {
            prog(writeShare, "building verity")
            rc.onProgress = { f -> prog(writeShare + verityShare * f, "building verity (hash tree + FEC)") }
            fun ex(e: Payload.Extent?) = if (e == null || e.count == 0L) listOf("-", "-") else listOf(e.start.toString(), e.count.toString())
            val ht = if (p.hasVerity) ex(p.hashTreeData) + ex(p.hashTree) + listOf(p.hashTreeAlgorithm ?: "sha256",
                p.hashTreeSalt?.takeIf { it.isNotEmpty() }?.joinToString("") { "%02x".format(it) } ?: "-")
                else listOf("-", "-", "-", "-", "-", "-")
            val fec = if (p.hasFec) ex(p.fecData) + ex(p.fec) + listOf(p.fecRoots.toString()) else listOf("-", "-", "-", "-", "2")
            rc.cmd((listOf("VERITY", p.name, bs.toString()) + ht + fec).joinToString(" "))
        }
        // verify
        prog(0.9, "verifying")
        rc.onProgress = { f -> prog(0.9 + 0.1 * f, "verifying") }
        val got = rc.cmd("HASH ${p.name} ${p.size}")
        val want = p.hash!!.joinToString("") { "%02x".format(it) }
        if (!got.equals(want, ignoreCase = true)) {
            throw IOException("${p.name} didn't verify after writing (checksum mismatch). Stopped before switching. Your running ROM is untouched.")
        }
        rc.onProgress = {}
        log("   ${p.name}: written and verified ✓")
    }
}
