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
    val fields: List<Pair<AutofillId, String>> = emptyList(), val forceLogin: Boolean = false,
    /** The field you are typing in, when Android says which. */
    val focused: AutofillId? = null,
    /** Fields the app itself marked for autofill (Android hints or a web page's autocomplete), as opposed to guesses from their labels. */
    val declared: Set<AutofillId> = emptySet(),
    /** Search boxes, message boxes and the like: never offered anything. */
    val ignored: Set<AutofillId> = emptySet()) {
    val hasLogin get() = user != null || pass != null || forceLogin
    val hasDetails get() = fields.any { it.second in AutofillProfile.PERSONAL_KEYS }
    val hasCard get() = fields.any { it.second in AutofillProfile.CARD_KEYS }
}

/** Lower-case words of a field's hints and labels: "searchContactsEditText" and "search_contacts" both become "search contacts edit text". */
internal fun fieldWords(raw: String): String =
    raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/** Words that mean a box is for searching, chatting or writing, so Vault keeps out of it. */
private val NOT_A_FORM = listOf("search", "query", "find", "filter", "lookup", "message", "reply", "compose", "comment", "chat", "caption", "note", "notes",
    "url", "omnibox", "location bar", "address bar", "subject", "body", "description", "title", "tag", "tags", "prompt", "translate", "calculator")

/** True when this phrase appears as whole words ("tel" matches "tel" or "tel number", never "hotel"); long phrases also match glued together ("creditcardnumber"). */
internal fun hasWords(words: String, phrase: String): Boolean {
    val p = fieldWords(phrase)
    if (Regex("(^| )" + Regex.escape(p) + "( |$)").containsMatchIn(words)) return true
    return p.length >= 8 && p.replace(" ", "") in words.replace(" ", "")
}

/** Which kind of field this is, from Android's hints and the page's own field names. Null when it is not one we fill. */
fun classifyField(hints: List<String>, text: String, variation: Int): String? {
    val t = fieldWords(hints.joinToString(" ") + " " + text)
    fun has(vararg k: String) = k.any { hasWords(t, it) }
    return when {
        has("creditcardnumber", "cc-number", "cardnumber", "card number", "ccnumber") -> "cardNumber"
        has("creditcardsecuritycode", "cc-csc", "cvc", "cvv", "security code", "securitycode") -> "cardCvc"
        has("creditcardexpiration", "creditcardexpirationdate", "cc-exp", "expiry", "expiration", "exp-date", "expdate") -> "cardExp"
        has("cc-name", "name on card", "cardholder", "card holder") -> "cardName"
        has("postaladdress", "street-address", "address-line1", "addressline1", "address line 1", "address1", "streetaddress", "street address") -> "line1"
        has("address-line2", "addressline2", "address line 2", "address2", "apartment", "apt") -> "line2"
        has("postalcode", "postal code", "postcode", "zipcode", "zip code", "zip") -> "postcode"
        has("address-level2", "city", "town", "locality") -> "city"
        has("address-level1", "state", "province", "region") && !has("password") -> "region"
        has("country") && !has("code") -> "country"
        has("given-name", "givenname", "first name", "firstname", "fname") -> "first"
        has("family-name", "familyname", "last name", "lastname", "surname", "lname") -> "last"
        has("organization", "company", "employer") -> "org"
        has("phone", "tel", "telephone", "mobile", "phonenumber", "phone number") -> "phone"
        has("emailaddress", "email", "e-mail") || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "email"
        hints.any { it == View.AUTOFILL_HINT_NAME.lowercase() } || has("full name", "fullname", "your name", "name") && !has("user", "username", "login", "company", "file", "card", "display", "nickname", "group", "folder", "album", "device") -> "fullName"
        else -> null
    }
}

fun parseForm(structure: AssistStructure): LoginForm {
    var user: AutofillId? = null; var pass: AutofillId? = null; var web: String? = null
    var userVal: String? = null; var passVal: String? = null; var focused: AutofillId? = null
    val fields = ArrayList<Pair<AutofillId, String>>()
    val declared = HashSet<AutofillId>(); val ignored = HashSet<AutofillId>()
    fun hintText(n: AssistStructure.ViewNode) = listOfNotNull(n.hint, n.idEntry, n.contentDescription?.toString(),
        n.htmlInfo?.attributes?.joinToString(" ") { "${it.first} ${it.second}" }).joinToString(" ")
    fun visit(n: AssistStructure.ViewNode) {
        if (!n.webDomain.isNullOrBlank()) web = n.webDomain
        val id = n.autofillId
        if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT) {
            if (n.isFocused) focused = id
            val hints = n.autofillHints?.map { it.lowercase() }.orEmpty()
            // What you have typed is not a label: "Paris" in a search box must not make it a city field.
            val text = hintText(n)
            val words = fieldWords(hints.joinToString(" ") + " " + text)
            val cls = n.inputType and InputType.TYPE_MASK_CLASS
            val variation = n.inputType and InputType.TYPE_MASK_VARIATION
            val multiLine = cls == InputType.TYPE_CLASS_TEXT && (n.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            val html = n.htmlInfo?.attributes.orEmpty().associate { it.first.lowercase() to it.second.lowercase() }
            val isPass = hints.any { "password" in it } || variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD || html["type"] == "password" || (hasWords(words, "password") || hasWords(words, "passcode") || hasWords(words, "pass")) && !hasWords(words, "passport")
            val noise = !isPass && hints.isEmpty() && (html["type"] == "search" || variation == InputType.TYPE_TEXT_VARIATION_URI || multiLine || NOT_A_FORM.any { hasWords(words, it) })
            val fromApp = hints.any { it != "off" && it != "on" } || html["autocomplete"].let { it != null && it != "off" && it != "on" }
            if (noise) ignored.add(id)
            else {
                if (fromApp) declared.add(id)
                val isUser = !isPass && (hints.any { it in setOf("username", "emailaddress", "email", "phone") || "user" in it || "email" in it } ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    html["type"] == "email" || listOf("username", "user", "user name", "user id", "email", "e-mail", "login", "sign in", "account").any { hasWords(words, it) })
                if (isPass && pass == null) { pass = id; passVal = n.autofillValue?.takeIf { it.isText }?.textValue?.toString() }
                else if (isUser && user == null) { user = id; userVal = n.autofillValue?.takeIf { it.isText }?.textValue?.toString() }
                if (!isPass) classifyField(hints, text, variation)?.let { k -> fields.add(id to k) }
            }
        }
        for (i in 0 until n.childCount) visit(n.getChildAt(i))
    }
    for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
    return LoginForm(user, pass, web, structure.activityComponent.packageName, userVal, passVal, fields, focused = focused, declared = declared, ignored = ignored)
}

private val PEOPLE_APPS = setOf("com.google.android.contacts", "com.android.contacts", "com.samsung.android.contacts", "com.google.android.dialer",
    "com.android.dialer", "com.samsung.android.dialer", "com.google.android.apps.messaging", "com.android.mms", "dev.wonderdesk.beacon")

/** What Vault knows about you for this screen, readable while locked. */
data class Saved(val login: Boolean, val details: Boolean, val card: Boolean)

/**
 * Whether Vault should offer itself at all. Android asks on every text box, so Vault keeps quiet unless it is really needed:
 *  - a sign-in (a password box) and you are in its user name or password box;
 *  - a user name or email box on its own (two-step sign-ins) only when a login is saved for this site or app;
 *  - an address or card form only when you saved details or a card, and only in one of those boxes.
 * When Android does not say which box you are in, it only offers on a sign-in it has a saved login for.
 * Asking by hand (the keyboard's or the menu's Autofill) always shows it.
 */
fun worthOffering(form: LoginForm, manual: Boolean, saved: Saved): Boolean {
    if (manual) return form.hasLogin || form.hasDetails || form.hasCard || form.focused != null
    val focus = form.focused
    val kind = when {
        focus == null -> null
        focus in form.ignored -> "ignored"
        focus == form.pass -> "pass"
        focus == form.user -> "user"
        form.fields.any { it.first == focus && it.second in AutofillProfile.CARD_KEYS } -> "card"
        form.fields.any { it.first == focus && it.second in AutofillProfile.PERSONAL_KEYS } -> "detail"
        else -> "other"
    }
    val kinds = form.fields.map { it.second }.toSet()
    return offerDecision(kind, form.pass != null, form.user != null, saved,
        declaredDetails = form.fields.count { it.first in form.declared && it.second in AutofillProfile.PERSONAL_KEYS }, detailKinds = kinds.count { it in AutofillProfile.PERSONAL_KEYS },
        cardFields = kinds.count { it in AutofillProfile.CARD_KEYS }, peopleApp = form.pkg in PEOPLE_APPS)
}

/** The form cut down to the boxes Vault may appear on. Android shows the pop-up on every box named in the answer, so nothing else is named. */
fun trimForm(form: LoginForm, saved: Saved, manual: Boolean): LoginForm {
    if (manual) return form
    val login = form.pass != null || (form.user != null && saved.login)
    val kinds = form.fields.map { it.second }.toSet()
    val details = saved.details && form.pkg !in PEOPLE_APPS &&
        (form.fields.any { it.first in form.declared && it.second in AutofillProfile.PERSONAL_KEYS } || kinds.count { it in AutofillProfile.PERSONAL_KEYS } >= 3)
    val card = saved.card && kinds.any { it in AutofillProfile.CARD_KEYS }
    return form.copy(user = form.user.takeIf { login }, pass = form.pass.takeIf { login }, forceLogin = form.forceLogin && login,
        fields = form.fields.filter { (_, k) -> (details && k in AutofillProfile.PERSONAL_KEYS) || (card && k in AutofillProfile.CARD_KEYS) })
}

/** The rule itself, on plain values so it can be tested. [focus] is "pass", "user", "detail", "card", "ignored", "other" or null (unknown). */
internal fun offerDecision(focus: String?, hasPass: Boolean, hasUser: Boolean, saved: Saved, declaredDetails: Int, detailKinds: Int, cardFields: Int, peopleApp: Boolean): Boolean {
    val login = hasPass || (hasUser && saved.login)
    // Contacts and dialler apps hold other people's details, so "My details" is never offered there.
    val details = saved.details && !peopleApp && (declaredDetails >= 1 || detailKinds >= 3)
    val card = saved.card && cardFields >= 1
    return when (focus) {
        "pass", "user" -> login
        "detail" -> details
        "card" -> card
        null -> hasPass && saved.login
        else -> false
    }
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
        val form0 = parseForm(structure)
        val manual = (request.flags and FillRequest.FLAG_MANUAL_REQUEST) != 0
        if (form0.pkg == packageName || (!form0.hasLogin && !form0.hasDetails && !form0.hasCard)) return callback.onSuccess(null)
        val saved = Saved(Vault.hasSavedFor(form0.webDomain, form0.pkg), Vault.hasSavedDetails(), Vault.hasSavedCard())
        if (!worthOffering(form0, manual, saved)) return callback.onSuccess(null)
        val form = trimForm(form0, saved, manual)
        val ids = (listOfNotNull(form.user, form.pass) + form.fields.map { it.first }).distinct().toTypedArray()
        if (ids.isEmpty()) return callback.onSuccess(null)
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
open class AutofillPickActivity : WonderActivity() {
    override val secure = true

    @Suppress("DEPRECATION")
    @Composable override fun Content() {
        Vault.init(this)
        val web = intent.getStringExtra("web"); val pkg = intent.getStringExtra("pkg").orEmpty()
        val saving = intent.getBooleanExtra("save", false)
        val fids = intent.getParcelableArrayListExtra<AutofillId>("fids").orEmpty(); val fkeys = intent.getStringArrayListExtra("fkeys").orEmpty()
        // Polaris asks for a login (or offers to save one) through this same sheet; the answer goes back as a result instead of into a form.
        val loginMode = intent.getBooleanExtra("login", false)
        val form = LoginForm(intent.getParcelableExtra("user"), intent.getParcelableExtra("pass"), web, pkg, fields = fids.zip(fkeys), forceLogin = loginMode)
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
                        if (loginMode) { if (item == null) cancel() else { setResult(RESULT_OK, Intent().putExtra("user", item.user).putExtra("pass", item.pw)); finish() }; return@PickSheet }
                        if (item != null && pkg.isNotEmpty() && web == null && pkg !in item.apps) runCatching { Vault.upsert(item.copy(apps = item.apps + pkg)) }
                        done(ds ?: item?.let { dataset(this@AutofillPickActivity, form, it) })
                    }
                }
            }
        }
    }
}

/** The same sheet, reachable by Polaris (same-signature apps only) to pick or save a browser login. */
class LoginPickActivity : AutofillPickActivity()

/** Fingerprint straight away if it is set up, otherwise just the master password. */
@Composable
internal fun CompactUnlock(onUnlocked: () -> Unit) {
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
