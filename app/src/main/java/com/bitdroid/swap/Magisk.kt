package com.bitdroid.swap

import android.content.Context
import android.system.Os
import java.io.File
import java.io.IOException

/**
 * Patches a boot image with Magisk, right on this phone (no root needed just to patch).
 *
 * Same as bitFlash's Root button: the Magisk tools are bundled into bitSwap at build time
 * (.github/workflows/build.yml) as lib*.so plus its patch scripts as assets, and this runs
 * Magisk's own boot_patch.sh — the same thing Magisk's "Select and patch a file" does.
 * The env flags match bitFlash exactly, because that patch already booted rooted on this Pixel.
 */
object Magisk {

    private val TOOLS = mapOf(
        "magiskboot" to "libmagiskboot.so",
        "magiskinit" to "libmagiskinit.so",
        "magisk" to "libmagisk.so",
        "init-ld" to "libinit-ld.so",
        "busybox" to "libbusybox.so",
        "magiskpolicy" to "libmagiskpolicy.so",
    )

    fun available(ctx: Context): Boolean =
        File(ctx.applicationInfo.nativeLibraryDir, "libmagiskboot.so").exists() &&
            runCatching { ctx.assets.open("magisk/boot_patch.sh").close(); true }.getOrDefault(false)

    fun version(ctx: Context): String =
        runCatching { ctx.assets.open("magisk/VERSION").bufferedReader().readText().trim() }.getOrDefault("unknown")

    /** Patches [input] and returns the patched image. [log] gets every line Magisk prints. */
    fun patch(ctx: Context, input: File, log: (String) -> Unit): File {
        val lib = File(ctx.applicationInfo.nativeLibraryDir)
        val dir = File(ctx.filesDir, "magisk-work")
        dir.deleteRecursively()
        dir.mkdirs()
        for ((name, so) in TOOLS) {
            val src = File(lib, so)
            if (src.exists()) Os.symlink(src.absolutePath, File(dir, name).absolutePath)
        }
        if (!File(dir, "magiskboot").exists()) throw IOException("Magisk's tools are missing from this bitSwap build.")
        for (a in listOf("boot_patch.sh", "util_functions.sh", "stub.apk")) {
            runCatching {
                ctx.assets.open("magisk/$a").use { i -> File(dir, a).outputStream().use { o -> i.copyTo(o) } }
            }.onFailure { if (a != "stub.apk") throw IOException("Magisk's $a is missing from this bitSwap build.") }
        }
        val boot = File(dir, "boot.img")
        input.copyTo(boot, overwrite = true)

        val busybox = File(dir, "busybox")
        val cmd = if (busybox.exists()) listOf(busybox.absolutePath, "sh", "-o", "standalone", "./boot_patch.sh", "boot.img")
                  else listOf("/system/bin/sh", "./boot_patch.sh", "boot.img")
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            put("KEEPVERITY", "true")
            put("KEEPFORCEENCRYPT", "true")
            put("PATCHVBMETAFLAG", "false")
            put("RECOVERYMODE", "false")
            put("LEGACYSAR", "false")
            put("ASH_STANDALONE", "1")
            put("PATH", "${dir.absolutePath}:/system/bin:/system/xbin")
        }
        val proc = pb.start()
        proc.inputStream.bufferedReader().forEachLine { line -> if (line.isNotBlank()) log("magisk: ${line.trim()}") }
        val code = proc.waitFor()
        val out = File(dir, "new-boot.img")
        if (code != 0 || !out.exists()) throw IOException("Magisk couldn't patch the image (exit $code). Copy the log and send it to Claude.")
        return out
    }
}
