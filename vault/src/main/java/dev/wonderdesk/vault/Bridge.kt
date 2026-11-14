package dev.wonderdesk.vault

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*

/**
 * Lets other Wonderdesk apps keep their passwords in Vault (the Wayfinder Safe folder, for one).
 *
 *  - `dev.wonderdesk.vault.PICK` with extra "title": after you unlock Vault, pick a login; its password goes back to the app.
 *  - `dev.wonderdesk.vault.SAVE` with "title" and "password": after you unlock, the login is saved (or updated).
 *
 * Only Wonderdesk apps (dev.wonderdesk.*) are answered; anything else is refused.
 */
class BridgeActivity : WonderActivity() {
    override val secure = true
    @Composable override fun Content() {
        val caller = callingActivity?.packageName.orEmpty()
        if (!caller.startsWith("dev.wonderdesk.")) { LaunchedEffect(Unit) { setResult(Activity.RESULT_CANCELED); finish() }; return }
        val context = LocalContext.current
        Vault.init(context)
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "Wonderdesk" }
        val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(caller, 0)).toString() }.getOrDefault("A Wonderdesk app")
        if (!Vault.unlocked) {
            WonderApp(appName = "Vault", about = "Unlock Vault for $appName", title = "Unlock Vault", onBack = { finish() }) {
                Column {
                    Text("$appName wants to ${if (intent.action == ACTION_SAVE) "save" else "use"} the password for “$title”.", Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp),
                        style = MaterialTheme.typography.bodyMedium)
                    UnlockPane(onUnlocked = {})
                }
            }
            return
        }
        if (intent.action == ACTION_SAVE) {
            LaunchedEffect(Unit) {
                val pw = intent.getStringExtra("password").orEmpty()
                val existing = Vault.items.find { it.title.equals(title, true) && it.category == "Wonderdesk" }
                Vault.upsert((existing ?: Item(title = title, category = "Wonderdesk", notes = "Saved by $appName")).copy(pw = pw, updated = System.currentTimeMillis()))
                setResult(Activity.RESULT_OK); finish()
            }
            return
        }
        val matches = remember { Vault.items.sortedByDescending { it.title.contains(title, true) || it.category == "Wonderdesk" } }
        WonderApp(appName = "Vault", about = "Choose a password", title = "Password for $appName", onBack = { finish() }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose the login to use for “$title”. Only its password is sent, to $appName alone.", style = MaterialTheme.typography.bodyMedium)
                if (matches.isEmpty()) Text("Vault has no logins yet. Go back, type the password once, and keep it in Vault: next time it's one tap.", style = MaterialTheme.typography.bodyMedium, color = wonderPalette().secondaryText)
                else Panel {
                    matches.forEachIndexed { i, it ->
                        if (i > 0) PanelDivider()
                        ListRow(it.title.ifBlank { it.domain }, subtitle = it.user.ifBlank { it.category }, leading = { LetterTile(it) }, onClick = {
                            setResult(Activity.RESULT_OK, Intent().putExtra("password", it.pw)); finish()
                        })
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_PICK = "dev.wonderdesk.vault.PICK"
        const val ACTION_SAVE = "dev.wonderdesk.vault.SAVE"
    }
}
