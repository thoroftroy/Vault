package dev.wonderdesk.vault

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.*
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*

/** What we found on a login screen. */
data class LoginForm(val user: AutofillId?, val pass: AutofillId?, val webDomain: String?, val pkg: String, val userValue: String? = null, val passValue: String? = null)

fun parseForm(structure: AssistStructure): LoginForm {
    var user: AutofillId? = null; var pass: AutofillId? = null; var web: String? = null
    var userVal: String? = null; var passVal: String? = null
    fun hintText(n: AssistStructure.ViewNode) = listOfNotNull(n.hint, n.idEntry, n.text?.toString(), n.contentDescription?.toString(),
        n.htmlInfo?.attributes?.joinToString(" ") { "${it.first}=${it.second}" }).joinToString(" ").lowercase()
    fun visit(n: AssistStructure.ViewNode) {
        if (!n.webDomain.isNullOrBlank()) web = n.webDomain
        val id = n.autofillId
        if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT) {
            val hints = n.autofillHints?.map { it.lowercase() }.orEmpty()
            val text = hintText(n)
            val variation = n.inputType and InputType.TYPE_MASK_VARIATION
            val isPass = hints.any { "password" in it } || variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD || "type=password" in text || ("pass" in text && "passport" !in text)
            val isUser = !isPass && (hints.any { it in setOf("username", "emailaddress", "email", "phone") || "user" in it || "email" in it } ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                listOf("user", "email", "e-mail", "login", "account", "type=email").any { it in text })
            if (isPass && pass == null) { pass = id; passVal = n.autofillValue?.takeIf { it.isText }?.textValue?.toString() }
            else if (isUser && user == null) { user = id; userVal = n.autofillValue?.takeIf { it.isText }?.textValue?.toString() }
        }
        for (i in 0 until n.childCount) visit(n.getChildAt(i))
    }
    for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
    // A text field just above the password field is almost always the username.
    return LoginForm(user, pass, web, structure.activityComponent.packageName, userVal, passVal)
}

private fun presentation(c: Context, title: String, sub: String): RemoteViews = RemoteViews(c.packageName, android.R.layout.simple_list_item_2).apply {
    setTextViewText(android.R.id.text1, title); setTextViewText(android.R.id.text2, sub)
}

fun dataset(c: Context, form: LoginForm, item: Item): Dataset {
    val b = Dataset.Builder(presentation(c, item.title.ifBlank { item.domain }, item.user.ifBlank { "Saved login" }))
    form.user?.let { b.setValue(it, AutofillValue.forText(item.user)) }
    form.pass?.let { b.setValue(it, AutofillValue.forText(item.pw)) }
    return b.build()
}

class VaultAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        Vault.init(this)
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val form = parseForm(structure)
        if (form.pkg == packageName || (form.user == null && form.pass == null)) return callback.onSuccess(null)
        val ids = listOfNotNull(form.user, form.pass).toTypedArray()
        val response = FillResponse.Builder()
        if (Vault.unlocked && Vault.idleMs() < 5 * 60_000L) {
            Vault.matches(form.webDomain, form.pkg).take(5).forEach { response.addDataset(dataset(this, form, it)) }
        }
        // Always offer "open Vault" so any login can be picked, and the vault can unlock.
        val pick = Intent(this, AutofillPickActivity::class.java).putExtra("web", form.webDomain).putExtra("pkg", form.pkg)
            .putExtra("user", form.user).putExtra("pass", form.pass)
        val sender = PendingIntent.getActivity(this, (form.pkg + form.webDomain).hashCode(), pick, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
        val label = if (Vault.unlocked) "Search Vault…" else "Unlock Vault"
        response.addDataset(Dataset.Builder(presentation(this, label, form.webDomain ?: form.pkg)).apply { ids.forEach { setValue(it, null) } }.setAuthentication(sender).build())
        if (form.pass != null) response.setSaveInfo(SaveInfo.Builder(if (form.user != null) SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME else SaveInfo.SAVE_DATA_TYPE_PASSWORD, ids).build())
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onFailure("Nothing to save")
        val form = parseForm(structure)
        if (form.passValue.isNullOrEmpty()) return callback.onFailure("No password entered")
        val intent = Intent(this, AutofillPickActivity::class.java).putExtra("save", true).putExtra("web", form.webDomain).putExtra("pkg", form.pkg)
            .putExtra("userValue", form.userValue).putExtra("passValue", form.passValue).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        callback.onSuccess(PendingIntent.getActivity(this, 77, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE).intentSender)
    }
}

/** Unlocks the vault for autofill, then fills a chosen login or saves a new one. */
class AutofillPickActivity : WonderActivity() {
    override val secure = true

    @Suppress("DEPRECATION")
    @Composable override fun Content() {
        Vault.init(this)
        val web = intent.getStringExtra("web"); val pkg = intent.getStringExtra("pkg").orEmpty()
        val saving = intent.getBooleanExtra("save", false)
        val form = LoginForm(intent.getParcelableExtra("user"), intent.getParcelableExtra("pass"), web, pkg)
        var unlocked by remember { mutableStateOf(Vault.unlocked) }
        val appName = remember { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
        WonderApp(appName = "Vault", about = "", title = if (saving) "Save to Vault" else "Fill from Vault", subtitle = web ?: appName, onBack = { setResult(RESULT_CANCELED); finish() }) {
            if (!unlocked) UnlockPane(onUnlocked = { unlocked = true })
            else if (saving) SavePane(web, pkg, appName, intent.getStringExtra("userValue").orEmpty(), intent.getStringExtra("passValue").orEmpty()) { finish() }
            else PickPane(form) { item ->
                if (pkg.isNotEmpty() && web == null && pkg !in item.apps) runCatching { Vault.upsert(item.copy(apps = item.apps + pkg)) }
                setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset(this@AutofillPickActivity, form, item)))
                finish()
            }
        }
    }
}

@Composable
private fun PickPane(form: LoginForm, onPick: (Item) -> Unit) {
    val p = wonderPalette()
    var q by remember { mutableStateOf("") }
    val suggested = remember { Vault.matches(form.webDomain, form.pkg) }
    val all = Vault.items.filter { q.isBlank() || it.title.contains(q, true) || it.user.contains(q, true) || it.url.contains(q, true) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchField(q, { q = it }, "Search all logins")
        LazyColumn {
            if (suggested.isNotEmpty() && q.isBlank()) {
                item { SectionLabel("Suggested for ${form.webDomain ?: "this app"}", Modifier.padding(6.dp)) }
                itemsIndexed(suggested) { i, it -> ListRow(it.title, Modifier.panelItem(i, suggested.size), subtitle = it.user, leading = { LetterTile(it) }, onClick = { onPick(it) }) }
                item { Spacer(Modifier.height(16.dp)); SectionLabel("All logins", Modifier.padding(6.dp)) }
            }
            itemsIndexed(all) { i, it -> ListRow(it.title, Modifier.panelItem(i, all.size), subtitle = it.user.ifBlank { it.domain }, leading = { LetterTile(it) }, onClick = { onPick(it) }) }
        }
        if (Vault.items.isEmpty()) Text("Your vault is empty.", color = p.secondaryText)
    }
}

@Composable
private fun SavePane(web: String?, pkg: String, appName: String, user: String, pass: String, done: () -> Unit) {
    val existing = remember { Vault.matches(web, pkg).firstOrNull { it.user == user } }
    var title by remember { mutableStateOf(existing?.title ?: web?.let { rootDomain(it).substringBefore('.').replaceFirstChar(Char::uppercase) } ?: appName) }
    var u by remember { mutableStateOf(user) }
    val toast = LocalToast.current
    Page {
        Panel {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (existing != null) "Update the saved password for ${existing.title}?" else "Save this login so Vault can fill it next time?", style = MaterialTheme.typography.bodyLarge)
                Field(title, { title = it }, "Name")
                Field(u, { u = it }, "Username or email")
                Text("Password: " + "•".repeat(pass.length.coerceAtMost(16)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WButton("Not now", done, Modifier.weight(1f))
            WButton(if (existing != null) "Update" else "Save", {
                runCatching {
                    Vault.upsert((existing ?: Item()).copy(title = title.trim(), user = u.trim(), pw = pass, url = web?.let { "https://$it" } ?: existing?.url.orEmpty(),
                        apps = if (web == null && pkg.isNotEmpty()) ((existing?.apps ?: emptyList()) + pkg).distinct() else existing?.apps ?: emptyList()))
                }.onFailure { toast(it.message ?: "Couldn't save") }.onSuccess { done() }
            }, Modifier.weight(1f), kind = ButtonKind.Primary)
        }
    }
}
