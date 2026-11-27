package com.bitdroid.swap

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // keep the screen on while it works
        Swapper.init(applicationContext)
        setContent { BitSwapTheme { App() } }
    }
}

@Composable
fun BitSwapTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val CardShape = RoundedCornerShape(32.dp)

/** Bloom "cookie": a circle with soft scallops. */
private val CookieShape = GenericShape { size, _ ->
    val cx = size.width / 2; val cy = size.height / 2
    val r = min(cx, cy)
    val n = 9
    val steps = 180
    for (i in 0..steps) {
        val t = 2 * PI * i / steps
        val rr = r * (0.9 + 0.1 * cos(n * t))
        val x = (cx + rr * cos(t)).toFloat(); val y = (cy + rr * sin(t)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
fun App() {
    val s = Swapper
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) s.pickRom(uri) }
    var confirm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = { if (s.busy) BusyBar() }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header() }
            if (s.root == RootClient.Root.NO_SU || s.root == RootClient.Root.DENIED) {
                item { NoRootCard() }
            }
            item { StatusCard() }
            item { RomCard(onPick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) }
            item { RootNewRomCard() }
            item { StepsCard() }
            item {
                val ready = s.root == RootClient.Root.OK && s.summary != null && s.profile != null && !s.busy && !s.finished
                Button(
                    onClick = { confirm = true },
                    enabled = ready,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.fillMaxWidth().height(60.dp)
                ) { Text("Switch", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            }
            item { LogCard() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    s.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { s.error = null },
            title = { Text("Stopped") },
            text = { Text(msg) },
            confirmButton = { Button(onClick = { s.error = null }) { Text("OK") } }
        )
    }

    if (confirm) EraseDialog(onCancel = { confirm = false }, onGo = { confirm = false; s.switch() })
}

@Composable
private fun Header() {
    Row(Modifier.padding(top = 24.dp, bottom = 8.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CookieShape), contentAlignment = Alignment.Center) {
            Text("⇄", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("bitSwap", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Switch ROMs from the phone itself. No PC.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NoRootCard() {
    val s = Swapper
    val ctx = LocalContext.current
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(20.dp)) {
            Text(
                if (s.root == RootClient.Root.DENIED) "Magisk said no" else "bitSwap needs root",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (s.root == RootClient.Root.DENIED)
                    "su is there but didn't allow bitSwap. Open Magisk → Superuser and turn bitSwap on, then tap Check again."
                else
                    "Android only lets rooted apps write partitions, so there's no way around this. " +
                        "Root this Pixel with bitFlash: put the Pixel in fastboot, plug it into your other phone, and tap " +
                        "\"Root this Pixel\" (one tap, nothing is wiped). Then open the Magisk icon on the Pixel and come back here.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Pill("Open bitFlash", enabled = true) {
                val i = ctx.packageManager.getLaunchIntentForPackage("com.bitdroid.flash")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cashhwocky/bitFlash/releases/latest"))
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            TextButton(onClick = { s.checkRoot() }, enabled = !s.checkingRoot, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.checkingRoot) "Checking…" else "Check again")
            }
        }
    }
}

@Composable
private fun StatusCard() {
    val s = Swapper
    val ok = s.root == RootClient.Root.OK && s.profile != null
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(if (ok) Color(0xFF34C759) else Color.Gray, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(s.profile?.name ?: "Unsupported phone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            InfoRow("Rooted", when {
                s.checkingRoot -> "checking…"
                s.root == RootClient.Root.OK -> "yes ✓"
                s.root == RootClient.Root.DENIED -> "denied"
                s.root == RootClient.Root.NO_SU -> "no"
                else -> "?"
            })
            InfoRow("Device", s.codename.ifBlank { "?" } + if (s.profile == null) " (not supported)" else "")
            InfoRow("Running slot", s.slot.ifBlank { "?" })
            InfoRow("New ROM goes to", if (s.slot.isNotBlank()) runCatching { Safety.otherSuffix(s.slot) }.getOrDefault("?") else "?")
            InfoRow("Running now", Build.DISPLAY ?: "?")
        }
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun RomCard(onPick: () -> Unit) {
    val s = Swapper
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Text("ROM zip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(22.dp))
                    .clickable(enabled = !s.busy) { onPick() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.rom?.name ?: "Pick ROM zip", fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        s.rom?.let { human(it.size) } ?: "A full A/B ROM zip for this phone, Android 17+",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (s.rom != null && !s.busy) TextButton(onClick = { s.clearRom() }) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
            when {
                s.checkingRom -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("Checking the zip…")
                }
                s.romError != null -> Text("✕ ${s.romError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                s.summary != null -> {
                    val r = s.summary!!
                    Text("✓ Safe to install on this phone", color = Color(0xFF34C759), fontWeight = FontWeight.SemiBold)
                    InfoRow("Built for", r.info.device)
                    InfoRow("Android", "SDK ${r.info.sdk}" + (r.info.android.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
                    if (r.info.patch.isNotBlank()) InfoRow("Security patch", r.info.patch)
                    InfoRow("Partitions", "${r.dynamic.size} system + ${r.plain.size} boot")
                    InfoRow("Size", human(r.totalBytes))
                    if (r.skipped.isNotEmpty()) Hint("Never touched: ${r.skipped.joinToString(", ")}")
                }
                else -> Hint("bitSwap checks the zip before anything happens: right phone, Android 17 or newer, full ROM (not an update).")
            }
            Spacer(Modifier.height(4.dp))
            Hint("Back up first: photos, chats, 2FA codes, files. Switching formats the phone.")
        }
    }
}

@Composable
private fun StepsCard() {
    val s = Swapper
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Text("What bitSwap does", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            s.steps.forEachIndexed { i, st ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    val (mark, col) = when (st.state) {
                        StepState.DONE -> "✓" to Color(0xFF34C759)
                        StepState.RUNNING -> "•" to MaterialTheme.colorScheme.primary
                        StepState.FAILED -> "✕" to MaterialTheme.colorScheme.error
                        StepState.SKIPPED -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
                        StepState.WAITING -> "${i + 1}" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(Modifier.size(28.dp).background(col.copy(alpha = 0.18f), CookieShape), contentAlignment = Alignment.Center) {
                        Text(mark, color = col, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(st.title, fontWeight = if (st.state == StepState.RUNNING) FontWeight.Bold else FontWeight.Medium)
                        if (st.detail.isNotEmpty()) Text(st.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Nothing is installed until \"Install with Android's updater\" succeeds. If anything fails before that, your phone still boots the ROM you're on now.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RootNewRomCard() {
    val s = Swapper
    val ready = s.magiskReady
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Root the new ROM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (ready)
                        "bitSwap patches the new ROM's boot image with Magisk ${s.magiskVersion} before writing it, so you switch straight into a rooted ROM. Root once, stay rooted across switches."
                    else
                        "This bitSwap build has no Magisk bundled, so the new ROM will be installed unrooted. Update bitSwap to get this.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = s.rootNewRom && ready, enabled = ready && !s.busy, onCheckedChange = { s.rootNewRom = it })
        }
    }
}

@Composable
private fun AdvancedCard() {
    val s = Swapper
    var open by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf(false) }
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (s.ignoreFirmware) Text("⚠ on", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(if (open) "Hide" else "Show")
            }
            if (open) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ignore firmware differences", fontWeight = FontWeight.Medium)
                        Text(
                            "Switch even if the bootloader, modem or other firmware on the other slot isn't the same as yours. " +
                                "bitSwap still never writes firmware, and still refuses ROMs older than Android 17. Turns itself off when you close bitSwap.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = s.ignoreFirmware,
                        enabled = !s.busy,
                        onCheckedChange = { on -> if (on) warn = true else s.ignoreFirmware = false }
                    )
                }
            }
        }
    }
    if (warn) {
        AlertDialog(
            onDismissRequest = { warn = false },
            containerColor = MaterialTheme.colorScheme.errorContainer,
            title = { Text("Ignore firmware differences?", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Black) },
            text = {
                Text(
                    "The other slot will boot with whatever bootloader and firmware it has now, not yours. " +
                        "If that firmware is older than what this Pixel has already run, anti-rollback can refuse to boot it, and that can hard-brick the phone " +
                        "(no fastboot, no fix without Google). Only turn this on if you know the other slot's firmware isn't older.",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            },
            confirmButton = {
                Button(
                    onClick = { warn = false; s.ignoreFirmware = true; s.log("⚠ Ignore firmware differences: ON") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    shape = RoundedCornerShape(50)
                ) { Text("I understand, turn it on") }
            },
            dismissButton = { TextButton(onClick = { warn = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EraseDialog(onCancel: () -> Unit, onGo: () -> Unit) {
    var backedUp by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        title = { Text("ALL DATA WILL BE ERASED", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Black) },
        text = {
            Column {
                Text(
                    "Switching installs ${Swapper.rom?.name} on the other slot and formats this phone: apps, photos, messages, accounts and files are all deleted.",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Back up first: Google Photos / Drive backup, export chats, move 2FA codes, copy Downloads off the phone.",
                    color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.clickable { backedUp = !backedUp }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = backedUp, onCheckedChange = { backedUp = it })
                    Text("I backed up what I need", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onGo, enabled = backedUp,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                shape = RoundedCornerShape(50)
            ) { Text("Erase and switch", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Pill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BusyBar() {
    val s = Swapper
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
            val p = s.progress
            Text(s.busyLabel + (p?.let { "  ${(it * 100).toInt()}%" } ?: "…"), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
            if (s.progressDetail.isNotEmpty()) Text(s.progressDetail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Text("Keep bitSwap open. The screen stays on.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun LogCard() {
    val lines = Swapper.logLines
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp)) {
            val clip = LocalClipboardManager.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Log", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { clip.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("Copy log") }
            }
            Spacer(Modifier.height(8.dp))
            lines.takeLast(60).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp) }
        }
    }
}

private fun human(b: Long): String = when {
    b < 0 -> "?"
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}
