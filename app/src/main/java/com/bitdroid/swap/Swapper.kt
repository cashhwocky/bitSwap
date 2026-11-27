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
        Step("Check root"),                         // 0
        Step("Check this phone"),                   // 1
        Step("Check the ROM zip"),                  // 2
        Step("Check firmware on both slots"),       // 3
        Step("Make room in super"),                 // 4
        Step("Mark the other slot as not ready"),   // 5
        Step("Write the boot images"),              // 6
        Step("Write the system partitions"),        // 7
        Step("Save the new partition table"),       // 8
        Step("Switch to the new slot"),             // 9
        Step("Schedule format data"),               // 10
        Step("Reboot"),                             // 11
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
                val snap = info["snapshot"].orEmpty()
                if (snap != "none" && snap != "cancelled") {
                    throw SecurityException("An Android update is still pending on this phone (state: $snap). Reboot, let it finish, then try again.")
                }
                info["busy"]?.takeIf { it.isNotBlank() }?.let {
                    throw SecurityException("The other slot is in use right now ($it). Reboot and try again.")
                }
                if (info["slotctl"] == "none") throw SecurityException("This ROM has no way to switch slots from Android (no bootctl or boot HAL). Nothing was changed.")
                if (info["super"] != "yes") throw IOException("This phone has no super partition.")
                step(1, StepState.DONE, "${Safety.DEVICES[dev]!!.name}, running slot $current → installing to slot $target")

                openZip(f) { ch, size ->
                    // 2. zip
                    cur = 2; step(2, StepState.RUNNING, "Checking ${f.name}…")
                    val s = analyse(ch, size)
                    for (p in s.plain) {
                        val dsz = client.cmd("PLAINSIZE ${p.name}").toLong()
                        if (dsz < p.size) throw IOException("${p.name} from this ROM (${p.size} bytes) is bigger than the phone's ${p.name}$target ($dsz).")
                    }
                    step(2, StepState.DONE, "For ${s.info.device}, SDK ${s.info.sdk}" +
                        (if (s.skipped.isNotEmpty()) ". Skipping ${s.skipped.joinToString(", ")} (never touched)." else ". ✓"))

                    // 3. firmware
                    cur = 3; step(3, StepState.RUNNING, "Comparing bootloader, modem and firmware on both slots (read only)…")
                    client.onProgress = { p -> setProgress(p * 0.02f, "Comparing firmware") }
                    val fw = client.cmd("FWCHECK " + s.plain.joinToString(" ") { it.name })
                    val checked = fw.substringAfter("checked=").substringBefore(" ").split(',').filter { it.isNotBlank() }
                    val diff = fw.substringAfter("diff=").split(',').filter { it.isNotBlank() }
                    if (diff.isNotEmpty() && ignoreFirmware) {
                        log("⚠ Firmware differs between slots (${diff.joinToString(", ")}). Continuing because \"Ignore firmware differences\" is on.")
                        step(3, StepState.DONE, "⚠ Different on the other slot: ${diff.joinToString(", ")}. Ignored (your setting).")
                    } else if (diff.isNotEmpty()) {
                        throw SecurityException("The other slot has different firmware than the slot you're running (${diff.joinToString(", ")}). " +
                            "Booting it could run older firmware, the anti-rollback brick. bitSwap never writes firmware, so it stopped. Nothing was changed.")
                    }
                    else step(3, StepState.DONE, "Both slots have the same firmware (${checked.size} partitions) ✓")

                    // 4. super
                    cur = 4; step(4, StepState.RUNNING, "Reading super's partition table…")
                    val dump = client.superDump()
                    val geo = Lp.parseGeometry(dump[0])
                    val slots = dump.drop(1).map { Lp.parseMetadata(it) }
                    val curMeta = slots[Safety.slotIndex(current)]
                    val plan = Planner.plan(geo, curMeta, target, s.dynamic.associate { it.name to it.size }, s.manifest.groups)
                    client.cmd("PLAN ${plan.blob.size}", plan.blob)
                    step(4, StepState.DONE, "${plan.newParts.size} partitions, ${Planner.gb(plan.needed)} of ${Planner.gb(plan.freeBefore)} free ✓ (not saved yet)")

                    // 5. unbootable
                    cur = 5; step(5, StepState.RUNNING, "So a half-written slot can never boot…")
                    client.cmd("UNBOOTABLE")
                    step(5, StepState.DONE, "Slot $target marked not ready. Your running slot is untouched.")

                    // 6 + 7. write
                    val patchName = patchTarget(s.plain)
                    val doRoot = rootNewRom && Magisk.available(ctx)
                    if (rootNewRom && !doRoot) log("⚠ This bitSwap build has no Magisk bundled, so the new ROM will be installed unrooted.")
                    if (doRoot) log("Will root the new ROM: $patchName gets patched with Magisk ${Magisk.version(ctx)} before it's written.")
                    val total = s.totalBytes.toDouble().coerceAtLeast(1.0)
                    var doneBytes = 0L
                    fun writeAll(stepIdx: Int, parts: List<Payload.Part>) {
                        cur = stepIdx; step(stepIdx, StepState.RUNNING, "")
                        for ((i, p) in parts.withIndex()) {
                            val base = 0.03 + 0.95 * doneBytes / total
                            val span = 0.95 * p.size / total
                            main.post { steps[stepIdx].detail = "${p.name} (${i + 1}/${parts.size})…" }
                            if (doRoot && p.name == patchName) {
                                writeRooted(client, ch, s.manifest, p) { frac, what ->
                                    setProgress((base + span * frac).toFloat(), "${p.name}: $what")
                                }
                            } else {
                                writePart(client, ch, s.manifest, p) { frac, what ->
                                    setProgress((base + span * frac).toFloat(), "${p.name}: $what")
                                }
                            }
                            doneBytes += p.size
                        }
                        val extra = if (stepIdx == 6 && doRoot) " ($patchName rooted)" else ""
                        step(stepIdx, StepState.DONE, parts.joinToString(" ") { it.name } + " ✓ (all verified)$extra")
                    }
                    writeAll(6, s.plain)
                    writeAll(7, s.dynamic)

                    // 8. commit
                    cur = 8; step(8, StepState.RUNNING, "Writing slot $target's partition table…")
                    client.cmd("SYNC")
                    client.cmd("COMMIT")
                    step(8, StepState.DONE, "Saved and read back ✓")
                }

                // 9. switch
                cur = 9; step(9, StepState.RUNNING, "Setting slot $target active…")
                client.cmd("SWITCH")
                switched = true
                step(9, StepState.DONE, "Next boot uses slot $target ✓")

                // 10. wipe
                cur = 10; step(10, StepState.RUNNING, "Telling recovery to format data on next boot…")
                try {
                    client.cmd("WIPE")
                } catch (e: Exception) {
                    log("✕ Couldn't schedule the wipe (${e.message}). Switching back to slot $current…")
                    client.cmd("REVERT")
                    switched = false
                    throw IOException("Couldn't schedule format data, so bitSwap switched back to slot $current. Nothing on your running ROM changed.")
                }
                step(10, StepState.DONE, "Recovery will format data, then start the new ROM ✓")

                // 11. reboot
                cur = 11; step(11, StepState.RUNNING, "Rebooting…")
                setProgress(1f, "Rebooting")
                main.post { finished = true }
                Thread.sleep(1500)
                client.cmd("REBOOT")
                step(11, StepState.DONE, "Rebooting into recovery: it formats data, then the new ROM's setup starts. If the phone doesn't restart, restart it yourself.")
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                step(cur, StepState.FAILED, why)
                val safe = if (switched) "" else "\n\nYour phone is still on the ROM you're running now (slot ${slot.ifBlank { "?" }}) and will boot it normally."
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
