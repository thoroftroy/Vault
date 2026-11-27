package dev.wonderdesk.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.delay

/**
 * Vault's quick look, opened from the Launcher's floating button: unlock in a small sheet and copy a user name, password or
 * 2FA code without opening Vault itself. Logins for the app you were in come first. Same-signature Wonderdesk apps only.
 */
class QuickLookActivity : WonderActivity() {
    override val secure = true

    @Composable override fun Content() {
        Vault.init(this)
        val pkg = intent.getStringExtra("pkg").orEmpty()
        val appName = remember { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
        var unlocked by remember { mutableStateOf(Vault.unlocked) }
        val p = wonderPalette()
        val none = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)).clickable(interactionSource = none, indication = null) { finish() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(p.window)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.navigationBarsPadding().imePadding().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    applicationContext.appArt(96)?.let { androidx.compose.foundation.Image(it, null, Modifier.size(34.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Vault", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (unlocked) "Tap a login to copy from it" else "Unlock to look something up", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                    }
                    if (unlocked) TextButton(onClick = { Vault.lock(); finish() }) { Text("Lock") }
                }
                if (!unlocked) CompactUnlock { unlocked = true } else QuickList(pkg, appName)
            }
        }
    }
}

@Composable
private fun QuickList(pkg: String, appName: String?) {
    val context = LocalContext.current
    val p = wonderPalette()
    // Read live, so a login just remembered for this app shows that straight away.
    val logins = Vault.items.filter { it.category != AutofillProfile.CAT }
    val here = remember { if (pkg.isEmpty()) emptyList() else Vault.matches(null, pkg) }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(here.singleOrNull()?.id) }
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { delay(2500); copied = null } }
    fun copy(what: String, label: String, value: String) { context.copySecret(label, value); Vault.touch(); copied = what }
    val shown = if (q.isBlank()) here.ifEmpty { logins.sortedByDescending { it.updated }.take(6) }
        else logins.filter { it.title.contains(q, true) || it.user.contains(q, true) || it.url.contains(q, true) }.take(12)
    SearchField(q, { q = it }, "Search logins")
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when { q.isNotBlank() -> if (shown.isEmpty()) "Nothing matches “$q”" else "Matches"; here.isNotEmpty() -> "For ${appName ?: "this app"}"; logins.isEmpty() -> "Your vault is empty"; else -> "Recently changed" },
            style = MaterialTheme.typography.labelLarge, color = p.secondaryText)
        if (shown.isNotEmpty()) Panel {
            shown.forEachIndexed { i, it ->
                if (i > 0) PanelDivider()
                ListRow(it.title.ifBlank { it.domain }, subtitle = it.user.ifBlank { it.domain }, leading = { LetterTile(it) }, onClick = { open = if (open == it.id) null else it.id })
                if (open == it.id) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (it.user.isNotEmpty()) WButton(if (copied == "user") "User name copied" else "Copy user name", { copy("user", "User name", it.user) }, Modifier.fillMaxWidth(), icon = WonderIcons.Person)
                    if (it.pw.isNotEmpty()) WButton(if (copied == "pw") "Password copied" else "Copy password", { copy("pw", "Password", it.pw) }, Modifier.fillMaxWidth(), icon = WonderIcons.Key, kind = ButtonKind.Primary)
                    if (it.totp.isNotEmpty()) {
                        val now = rememberNow(1000)
                        Totp.code(it.totp, now)?.let { (code, left) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.panel).clickable { copy("code", "2FA code", code) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(code.chunked(3).joinToString(" "), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                                Text(if (copied == "code") "Copied" else "${left}s · tap to copy", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                            }
                        }
                    }
                    // So it is offered straight away next time in this app.
                    if (pkg.isNotEmpty() && pkg !in it.apps && here.none { h -> h.id == it.id }) Text("Remember for ${appName ?: "this app"}", Modifier.clickable { runCatching { Vault.upsert(it.copy(apps = it.apps + pkg)) }; copied = "remember" }.padding(vertical = 4.dp),
                        color = p.accent, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (copied == "remember") Text("Saved. Vault will suggest it in ${appName ?: "this app"}.", color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
        Text("Copied items clear from the clipboard by themselves.", color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
    }
}
