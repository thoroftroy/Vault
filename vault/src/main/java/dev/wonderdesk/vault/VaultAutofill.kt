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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What we found on a form: the login fields, and any name, address, phone or card fields. */
data class LoginForm(val user: AutofillId?, val pass: AutofillId?, val webDomain: String?, val pkg: String, val userValue: String? = null, val passValue: String? = null,
    val fields: List<Pair<AutofillId, String>> = emptyList()) {
    val hasLogin get() = user != null || pass != null
    val hasDetails get() = fields.any { it.second in AutofillProfile.PERSONAL_KEYS }
    val hasCard get() = fields.any { it.second in AutofillProfile.CARD_KEYS }
}

/** Which kind of field this is, from Android's hints and the page's own field names. Null when it is not one we fill. */
fun classifyField(hints: List<String>, text: String, variation: Int): String? {
    val h = hints.joinToString(" ").lowercase()
    val t = (h + " " + text)
    fun has(vararg k: String) = k.any { it in t }
    return when {
        has("creditcardnumber", "cc-number", "cardnumber", "card number", "card-number", "ccnumber") -> "cardNumber"
        has("creditcardsecuritycode", "cc-csc", "cvc", "cvv", "security code", "securitycode") -> "cardCvc"
        has("creditcardexpiration", "cc-exp", "expiry", "expiration", "exp-date", "expdate") -> "cardExp"
        has("cc-name", "name on card", "cardholder", "card holder") -> "cardName"
        has("postaladdress", "street-address", "address-line1", "addressline1", "address line 1", "address1", "streetaddress", "street address") -> "line1"
        has("address-line2", "addressline2", "address line 2", "address2", "apartment", "apt", "suite") -> "line2"
        has("postalcode", "postal-code", "postcode", "zipcode", "zip code", "zip-code", "\bzip") -> "postcode"
        has("address-level2", "city", "town", "locality") -> "city"
        has("address-level1", "state", "province", "region") && !has("password") -> "region"
        has("country") -> "country"
        has("given-name", "givenname", "first name", "firstname", "first-name", "fname") -> "first"
        has("family-name", "familyname", "last name", "lastname", "last-name", "surname", "lname") -> "last"
        has("organization", "company", "employer") -> "org"
        has("phone", "tel", "mobile") && !has("hotel") -> "phone"
        has("emailaddress", "email", "e-mail") || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "email"
        hints.any { it == View.AUTOFILL_HINT_NAME } || Regex("""(^|[^a-z])(full ?name|name)($|[^a-z])""").containsMatchIn(t) && !has("user", "login", "company", "file", "card", "display") -> "fullName"
        else -> null
    }
}

fun parseForm(structure: AssistStructure): LoginForm {
    var user: AutofillId? = null; var pass: AutofillId? = null; var web: String? = null
    var userVal: String? = null; var passVal: String? = null
    val fields = ArrayList<Pair<AutofillId, String>>()
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
            if (!isPass) classifyField(hints, text, variation)?.let { k -> fields.add(id to k) }
        }
        for (i in 0 until n.childCount) visit(n.getChildAt(i))
    }
    for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
    return LoginForm(user, pass, web, structure.activityComponent.packageName, userVal, passVal, fields)
}

private fun presentation(c: Context, title: String, sub: String): RemoteViews = RemoteViews(c.packageName, R.layout.autofill_item).apply {
    setTextViewText(R.id.af_title, title); setTextViewText(R.id.af_sub, sub)
}

fun dataset(c: Context, form: LoginForm, item: Item): Dataset {
    val b = Dataset.Builder(presentation(c, item.title.ifBlank { item.domain }, item.user.ifBlank { "Saved login" }))
    form.user?.let { b.setValue(it, AutofillValue.forText(item.user)) }
    form.pass?.let { b.setValue(it, AutofillValue.forText(item.pw)) }
    return b.build()
}

/** Name, address and phone (or card) values for the fields found on a form. */
fun detailsDataset(c: Context, form: LoginForm, values: Map<String, String>, keys: Set<String>, title: String, sub: String): Dataset? {
    val b = Dataset.Builder(presentation(c, title, sub)); var any = false
    val seen = HashSet<String>()
    for ((id, k) in form.fields) {
        if (k !in keys) continue
        val v = AutofillProfile.valueFor(values, k) ?: continue
        if (k !in setOf("line2") && !seen.add(k)) continue   // one field per kind
        b.setValue(id, AutofillValue.forText(v)); any = true
    }
    return if (any) b.build() else null
}

class VaultAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        Vault.init(this)
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val form = parseForm(structure)
        if (form.pkg == packageName || (!form.hasLogin && !form.hasDetails && !form.hasCard)) return callback.onSuccess(null)
        val ids = (listOfNotNull(form.user, form.pass) + form.fields.map { it.first }).distinct().toTypedArray()
        val response = FillResponse.Builder()
        val open = Vault.unlocked && Vault.idleMs() < 5 * 60_000L
        if (open) {
            if (form.hasLogin) Vault.matches(form.webDomain, form.pkg).take(4).forEach { response.addDataset(dataset(this, form, it)) }
            val prof = AutofillProfile.get()
            if (prof.isNotEmpty()) {
                if (form.hasDetails) detailsDataset(this, form, prof, AutofillProfile.PERSONAL_KEYS, "My details", listOf(prof["first"], prof["last"], prof["line1"]).filterNot { it.isNullOrBlank() }.joinToString(" · ").ifBlank { "Name and address" })?.let { response.addDataset(it) }
                if (form.hasCard) detailsDataset(this, form, prof, AutofillProfile.CARD_KEYS, "My card", prof["cardNumber"]?.takeLast(4)?.let { "•••• $it" } ?: "Saved card")?.let { response.addDataset(it) }
            }
        }
        // One tap to unlock (or search) in a small sheet; it fills straight away when only one login fits.
        val pick = Intent(this, AutofillPickActivity::class.java).putExtra("web", form.webDomain).putExtra("pkg", form.pkg)
            .putExtra("user", form.user).putExtra("pass", form.pass)
            .putParcelableArrayListExtra("fids", ArrayList(form.fields.map { it.first })).putStringArrayListExtra("fkeys", ArrayList(form.fields.map { it.second }))
        val sender = PendingIntent.getActivity(this, (form.pkg + form.webDomain).hashCode(), pick, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
        val label = if (Vault.unlocked) "Search Vault…" else "Unlock Vault"
        val matched = form.hasLogin && Vault.unlocked && Vault.matches(form.webDomain, form.pkg).isNotEmpty()
        if (!open || !matched) response.addDataset(Dataset.Builder(presentation(this, label, form.webDomain ?: form.pkg)).apply { ids.forEach { setValue(it, null) } }.setAuthentication(sender).build())
        if (form.pass != null) response.setSaveInfo(SaveInfo.Builder(if (form.user != null) SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME else SaveInfo.SAVE_DATA_TYPE_PASSWORD, listOfNotNull(form.user, form.pass).toTypedArray()).build())
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

/** A small sheet over whatever you were doing: unlock if needed, then pick. It never opens the whole Vault. */
class AutofillPickActivity : WonderActivity() {
    override val secure = true

    @Suppress("DEPRECATION")
    @Composable override fun Content() {
        Vault.init(this)
        val web = intent.getStringExtra("web"); val pkg = intent.getStringExtra("pkg").orEmpty()
        val saving = intent.getBooleanExtra("save", false)
        val fids = intent.getParcelableArrayListExtra<AutofillId>("fids").orEmpty(); val fkeys = intent.getStringArrayListExtra("fkeys").orEmpty()
        val form = LoginForm(intent.getParcelableExtra("user"), intent.getParcelableExtra("pass"), web, pkg, fields = fids.zip(fkeys))
        var unlocked by remember { mutableStateOf(Vault.unlocked) }
        val appName = remember { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
        val p = wonderPalette()
        fun cancel() { setResult(RESULT_CANCELED); finish() }
        fun done(ds: Dataset?) { if (ds == null) cancel() else { setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, ds)); finish() } }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .45f)).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { cancel() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(p.window)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}.navigationBarsPadding().imePadding().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    applicationContext.appArt(96)?.let { androidx.compose.foundation.Image(it, null, Modifier.size(34.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text(if (saving) "Save to Vault" else "Vault", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold); Text(web ?: appName, style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 1) }
                }
                when {
                    !unlocked -> CompactUnlock { unlocked = true }
                    saving -> SavePane(web, pkg, appName, intent.getStringExtra("userValue").orEmpty(), intent.getStringExtra("passValue").orEmpty()) { finish() }
                    else -> PickSheet(form) { item, ds ->
                        if (item != null && pkg.isNotEmpty() && web == null && pkg !in item.apps) runCatching { Vault.upsert(item.copy(apps = item.apps + pkg)) }
                        done(ds ?: item?.let { dataset(this@AutofillPickActivity, form, it) })
                    }
                }
            }
        }
    }
}

/** Fingerprint straight away if it is set up, otherwise just the master password. */
@Composable
private fun CompactUnlock(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun attempt(block: () -> Unit) { busy = true; error = null; scope.launch { val r = withContext(Dispatchers.Default) { runCatching(block) }; busy = false; r.onSuccess { onUnlocked() }.onFailure { error = it.message ?: "That didn't work" } } }
    fun bio() {
        val cipher = BioKey.decryptCipher(context) ?: return
        android.hardware.biometrics.BiometricPrompt.Builder(context).setTitle("Unlock Vault").setNegativeButton("Use password", context.mainExecutor) { _, _ -> }.build()
            .authenticate(android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher), CancellationSignal(), context.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { val c = result.cryptoObject?.cipher ?: return; attempt { Vault.unlock(BioKey.read(context, c)) } }
            })
    }
    LaunchedEffect(Unit) { if (Vault.exists && BioKey.enabled(context)) bio() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(pw, { pw = it; error = null }, "Master password", visual = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboard = androidx.compose.ui.text.input.KeyboardType.Password,
            onDone = { if (pw.isNotEmpty()) attempt { Vault.unlock(pw) } })
        error?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (BioKey.enabled(context)) WButton("Fingerprint", { bio() }, Modifier.weight(1f), icon = WonderIcons.Lock)
            WButton(if (busy) "Unlocking…" else "Unlock", { attempt { Vault.unlock(pw) } }, Modifier.weight(1f), kind = ButtonKind.Primary, enabled = !busy && pw.isNotEmpty())
        }
    }
}

/** Only the logins that fit this site or app, plus your details if the form asks for them. A search finds anything else. */
@Composable
private fun PickSheet(form: LoginForm, onPick: (Item?, Dataset?) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val suggested = remember { if (form.hasLogin) Vault.matches(form.webDomain, form.pkg) else emptyList() }
    val prof = remember { AutofillProfile.get() }
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(suggested.isEmpty() && form.hasLogin) }
    // One matching login and nothing else to choose: fill it without asking.
    var auto by remember { mutableStateOf(suggested.size == 1 && !(form.hasDetails && prof.isNotEmpty())) }
    LaunchedEffect(auto) { if (auto) onPick(suggested[0], null) }
    if (auto) { Text("Filling…", color = p.secondaryText); return }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (suggested.isNotEmpty()) {
            Panel { suggested.forEachIndexed { i, it -> if (i > 0) PanelDivider(); ListRow(it.title.ifBlank { it.domain }, subtitle = it.user + if (suggested.count { o -> o.user == it.user } > 1) " · saved " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it.created)) else "", leading = { LetterTile(it) }, onClick = { onPick(it, null) }) } }
        }
        if (form.hasDetails && prof.isNotEmpty()) detailsDataset(context, form, prof, AutofillProfile.PERSONAL_KEYS, "My details", "")?.let { ds ->
            Panel { ListRow("Fill my details", subtitle = listOf(prof["first"], prof["last"], prof["line1"]).filterNot { it.isNullOrBlank() }.joinToString(" · "), icon = WonderIcons.Person, tint = androidx.compose.ui.graphics.Color(0xFF1E7FE8), onClick = { onPick(null, ds) }) }
        }
        if (form.hasCard && prof.isNotEmpty()) detailsDataset(context, form, prof, AutofillProfile.CARD_KEYS, "My card", "")?.let { ds ->
            Panel { ListRow("Fill my card", subtitle = prof["cardNumber"]?.takeLast(4)?.let { "•••• $it" }, icon = WonderIcons.Shield, tint = androidx.compose.ui.graphics.Color(0xFF2E9E44), onClick = { onPick(null, ds) }) }
        }
        if (form.hasLogin) {
            if (!searching) Text(if (suggested.isEmpty()) "No saved login for this site" else "Not the one? Search all logins", Modifier.clickable { searching = true }.padding(vertical = 6.dp), color = p.accent, style = MaterialTheme.typography.bodyMedium)
            else {
                SearchField(q, { q = it }, "Search all logins")
                val all = Vault.items.filter { it.category != AutofillProfile.CAT && (q.isBlank() && false || q.isNotBlank() && (it.title.contains(q, true) || it.user.contains(q, true) || it.url.contains(q, true))) }.take(8)
                if (all.isNotEmpty()) Panel { all.forEachIndexed { i, it -> if (i > 0) PanelDivider(); ListRow(it.title, subtitle = it.user.ifBlank { it.domain }, leading = { LetterTile(it) }, onClick = { onPick(it, null) }) } }
                else if (q.isBlank()) Text("Type a name, site or username", color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
                else Text("Nothing matches “$q”", color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (Vault.items.none { it.category != AutofillProfile.CAT } && prof.isEmpty()) Text("Your vault is empty.", color = p.secondaryText)
    }
}

@Composable
private fun SavePane(web: String?, pkg: String, appName: String, user: String, pass: String, done: () -> Unit) {
    val sameUser = remember { Vault.matches(web, pkg).filter { it.user == user } }
    val existing = sameUser.firstOrNull()
    val identical = sameUser.any { it.pw == pass }
    var title by remember { mutableStateOf(existing?.title ?: web?.let { rootDomain(it).substringBefore('.').replaceFirstChar(Char::uppercase) } ?: appName) }
    var u by remember { mutableStateOf(user) }
    val toast = LocalToast.current
    fun save(base: Item?) {
        runCatching {
            Vault.upsert((base ?: Item()).copy(title = title.trim(), user = u.trim(), pw = pass, url = web?.let { "https://$it" } ?: base?.url.orEmpty(),
                apps = if (web == null && pkg.isNotEmpty()) ((base?.apps ?: emptyList()) + pkg).distinct() else base?.apps ?: emptyList()))
        }.onFailure { toast(it.message ?: "Couldn't save") }.onSuccess { done() }
    }
    Page {
        if (identical) {
            Panel { Text("This login is already saved in Vault.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
            WButton("Close", done, Modifier.fillMaxWidth())
            return@Page
        }
        Panel {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (existing != null) "The password for ${existing.title} (${existing.user.ifBlank { "this login" }}) is different from the one saved." else "Save this login so Vault can fill it next time?", style = MaterialTheme.typography.bodyLarge)
                Field(title, { title = it }, "Name")
                Field(u, { u = it }, "Username or email")
                Text("Password: " + "•".repeat(pass.length.coerceAtMost(16)), style = MaterialTheme.typography.bodyMedium)
                if (existing != null) Text("Update replaces the saved password and keeps the old one in that login's history. Keep both saves this as a second login for the same site.", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WButton("Not now", done, Modifier.weight(1f))
            if (existing != null) WButton("Keep both", { save(null) }, Modifier.weight(1f))
            WButton(if (existing != null) "Update" else "Save", { save(existing) }, Modifier.weight(1f), kind = ButtonKind.Primary)
        }
    }
}
