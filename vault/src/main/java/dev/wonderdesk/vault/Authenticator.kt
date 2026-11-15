package dev.wonderdesk.vault

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import dev.wonderdesk.shared.*
import java.util.concurrent.Executors

/** A 2FA account read from a QR code, a link or a typed key. */
data class OtpAccount(val issuer: String, val account: String, val uri: String)

object Otp {
    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun base32(b: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (x in b) { buf = (buf shl 8) or (x.toInt() and 255); bits += 8; while (bits >= 5) { sb.append(ALPHA[(buf shr (bits - 5)) and 31]); bits -= 5 } }
        if (bits > 0) sb.append(ALPHA[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** otpauth:// link, a raw setup key, or a Google Authenticator export (otpauth-migration://). */
    fun parse(text: String): List<OtpAccount> {
        val t = text.trim()
        return when {
            t.startsWith("otpauth-migration://") -> migration(t)
            t.startsWith("otpauth://") -> listOfNotNull(single(t))
            else -> runCatching { Totp.base32(t); listOf(OtpAccount("", "", t.replace(" ", ""))) }.getOrDefault(emptyList())
        }
    }

    private fun single(t: String): OtpAccount? {
        val u = Uri.parse(t); if (u.getQueryParameter("secret").isNullOrBlank()) return null
        val label = Uri.decode(u.path.orEmpty().trimStart('/'))
        val issuer = u.getQueryParameter("issuer") ?: label.substringBefore(':', "")
        return OtpAccount(issuer, label.substringAfter(':', label).trim(), t)
    }

    private fun migration(t: String): List<OtpAccount> = runCatching {
        val data = Uri.parse(t).getQueryParameter("data")!!
        val bytes = android.util.Base64.decode(data.replace(' ', '+'), android.util.Base64.DEFAULT)
        val out = ArrayList<OtpAccount>()
        var p = 0
        fun varint(b: ByteArray, at: IntArray): Long { var r = 0L; var s = 0; while (true) { val c = b[at[0]++].toInt() and 255; r = r or ((c and 127).toLong() shl s); if (c < 128) return r; s += 7 } }
        val pos = intArrayOf(0)
        while (pos[0] < bytes.size) {
            val tag = varint(bytes, pos).toInt(); val field = tag shr 3; val wire = tag and 7
            if (wire == 2) {
                val len = varint(bytes, pos).toInt(); val body = bytes.copyOfRange(pos[0], pos[0] + len); pos[0] += len
                if (field == 1) {
                    var secret = ByteArray(0); var name = ""; var issuer = ""; var algo = 1; var digits = 1; var type = 2
                    val q = intArrayOf(0)
                    while (q[0] < body.size) {
                        val tg = varint(body, q).toInt(); val f = tg shr 3; val w = tg and 7
                        if (w == 2) { val l = varint(body, q).toInt(); val v = body.copyOfRange(q[0], q[0] + l); q[0] += l
                            when (f) { 1 -> secret = v; 2 -> name = v.decodeToString(); 3 -> issuer = v.decodeToString() } }
                        else { val v = varint(body, q).toInt(); when (f) { 4 -> algo = v; 5 -> digits = v; 6 -> type = v } }
                    }
                    if (type == 2 || type == 0) out += OtpAccount(issuer.ifBlank { name.substringBefore(':', "") }, name.substringAfter(':', name), buildString {
                        append("otpauth://totp/").append(Uri.encode(if (issuer.isBlank()) name else "$issuer:$name")).append("?secret=").append(base32(secret))
                        if (issuer.isNotBlank()) append("&issuer=").append(Uri.encode(issuer))
                        append("&algorithm=").append(when (algo) { 2 -> "SHA256"; 3 -> "SHA512"; else -> "SHA1" }).append("&digits=").append(if (digits == 2) 8 else 6)
                    })
                }
            } else varint(bytes, pos)
        }
        out
    }.getOrDefault(emptyList())

    private val reader get() = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }

    fun decodeImage(c: android.content.Context, uri: Uri): String? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1; while (opts.outWidth / sample > 2200) sample *= 2
        val bmp = c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px)))).text
    }.getOrNull()

    @Composable
    fun Scanner(modifier: Modifier, onCode: (String) -> Unit) {
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
        val p = wonderPalette()
        Box(modifier.clip(RoundedCornerShape(16.dp)).border(2.dp, p.accent, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            if (!granted) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                    Text("Vault needs the camera to read the QR code. Nothing is recorded or kept.", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp)); WButton("Allow camera", { ask.launch(Manifest.permission.CAMERA) }, kind = ButtonKind.Primary)
                }
                return@Box
            }
            val rd = remember { reader }; val executor = remember { Executors.newSingleThreadExecutor() }
            var done by remember { mutableStateOf(false) }
            DisposableEffect(Unit) { onDispose { executor.shutdown() } }
            AndroidView(factory = { ctx ->
                val view = PreviewView(ctx); val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { img ->
                        try {
                            if (!done) {
                                val plane = img.planes[0]; val buf = plane.buffer; val data = ByteArray(buf.remaining()).also { buf.get(it) }
                                val src = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
                                val r = runCatching { rd.decodeWithState(BinaryBitmap(HybridBinarizer(src))) }.getOrNull(); rd.reset()
                                if (r != null && (r.text.startsWith("otpauth") )) { done = true; view.post { onCode(r.text) } }
                            }
                        } finally { img.close() }
                    }
                    runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                }, ContextCompat.getMainExecutor(ctx))
                view
            }, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize(.62f).border(3.dp, Color.White.copy(.85f), RoundedCornerShape(18.dp)))
        }
    }
}

/** The Codes tab: every 2FA code in one place, refreshing by themselves; tap one to copy. */
@Composable
fun CodesPane(onOpen: (Item) -> Unit, onAdd: () -> Unit) {
    val p = wonderPalette()
    val context = LocalContext.current
    val toast = LocalToast.current
    var q by remember { mutableStateOf("") }
    val withCodes = Vault.items.filter { it.totp.isNotBlank() && (q.isBlank() || it.title.contains(q, true) || it.user.contains(q, true)) }
    val now = rememberNow(1000)
    if (Vault.items.none { it.totp.isNotBlank() }) {
        EmptyState("No codes yet", "Add the two-factor key of a website and Vault shows its 6-digit code here, refreshing every 30 seconds, like Google Authenticator. Scan the QR code a site shows you, or paste its setup key.", icon = WonderIcons.Timer,
            action = { WButton("Add a code", onAdd, icon = WonderIcons.Add, kind = ButtonKind.Primary) })
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SearchField(q, { q = it }, "Search codes") }
        items(withCodes, key = { it.id }) { i ->
            val code = Totp.code(i.totp, now)
            Panel {
                Row(Modifier.fillMaxWidth().clickable { code?.let { context.copySecret("Code", it.first); toast("Code copied"); Vault.touch() } }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(i.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        if (i.user.isNotBlank()) Text(i.user, color = p.secondaryText, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Text(code?.first?.let { it.take(it.length / 2) + " " + it.drop(it.length / 2) } ?: "Invalid key", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 30.sp,
                            color = if (code != null && code.second <= 5) p.danger else p.accent)
                    }
                    code?.let { c ->
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(progress = { c.second / 30f }, Modifier.size(40.dp), color = if (c.second <= 5) p.danger else p.accent, trackColor = p.secondaryText.copy(alpha = .2f), strokeWidth = 4.dp)
                            Text("${c.second}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    IconButton(onClick = { onOpen(i) }) { Icon(WonderIcons.ChevronRight, "Open login") }
                }
            }
        }
    }
}

/** Add a code: scan the QR, read it from a screenshot, or type the key. Saves into a new or an existing login. */
@Composable
fun AddCodeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val p = wonderPalette()
    var mode by remember { mutableStateOf("choose") }
    var typed by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<OtpAccount>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun got(text: String) { val r = Otp.parse(text); if (r.isEmpty()) error = "That doesn't look like a 2FA code or key." else { found = r; error = null; mode = "save" } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { val t = Otp.decodeImage(context, uri); if (t == null) error = "No QR code found in that picture. Try a sharper screenshot." else got(t) }
    }
    WonderDialog(onDismissRequest = onDismiss, title = { Text(if (mode == "save") "Save ${found.size.let { if (it > 1) "$it codes" else "code" }}" else "Add a code") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            error?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodySmall) }
            when (mode) {
                "choose" -> Panel {
                    ListRow("Scan a QR code", subtitle = "Point the camera at the code a website shows", icon = WonderIcons.PhotoCamera, tint = Color(0xFF1E7FE8), chevron = true, onClick = { mode = "scan" }); PanelDivider()
                    ListRow("Read a screenshot", subtitle = "Pick a picture that contains the QR code", icon = WonderIcons.Image, tint = Color(0xFF8E44AD), chevron = true, onClick = { pick.launch("image/*") }); PanelDivider()
                    ListRow("Type a setup key or paste a link", subtitle = "The text under the QR code, or an otpauth:// link", icon = WonderIcons.Key, tint = Color(0xFFE5483C), chevron = true, onClick = { mode = "type" })
                }
                "scan" -> { Otp.Scanner(Modifier.fillMaxWidth().aspectRatio(1f)) { got(it) }; Text("Works with Google Authenticator export codes too.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
                "type" -> {
                    OutlinedTextField(typed, { typed = it }, label = { Text("Setup key or otpauth:// link") }, modifier = Modifier.fillMaxWidth())
                    WButton("Continue", { got(typed) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = typed.isNotBlank())
                }
                else -> {
                    found.forEachIndexed { idx, a ->
                        var name by remember { mutableStateOf(a.issuer.ifBlank { "New login" }) }
                        var user by remember { mutableStateOf(a.account) }
                        Panel(Modifier.padding(bottom = 4.dp)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(user, { user = it }, label = { Text("Account") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            val existing = Vault.items.filter { it.totp.isBlank() && (it.title.contains(a.issuer.ifBlank { "\u0000" }, true) || (a.account.isNotBlank() && it.user.equals(a.account, true))) }
                            existing.take(3).forEach { e -> TextButton(onClick = { Vault.upsert(e.copy(totp = a.uri)); toast("Added to ${e.title}"); if (idx == found.lastIndex) onDismiss() }) { Text("Add to my “${e.title}” login") } }
                            WButton("Save as new login", { Vault.upsert(Item(title = name.trim(), user = user.trim(), totp = a.uri)); toast("Saved ${name.trim()}"); if (idx == found.lastIndex) onDismiss() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = name.isNotBlank())
                        } }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }, dismissButton = if (mode != "choose") ({ TextButton(onClick = { mode = "choose"; error = null }) { Text("Back") } }) else null)
}
