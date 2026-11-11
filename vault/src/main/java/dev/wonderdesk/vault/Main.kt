package dev.wonderdesk.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.PersistableBundle
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : WonderActivity() {
    override val secure = true
    @Composable override fun Content() = VaultApp()
}

private const val ABOUT = "Vault keeps your passwords encrypted on this phone with AES-256-GCM and a key derived from your master password. " +
    "It fills logins in Firefox and other apps, generates strong passwords and passphrases, shows two-factor codes, checks password health, " +
    "and can unlock with your fingerprint. Backups use the same format as Vault on Wonderdesk for Linux."

private fun Context.vaultPrefs() = getSharedPreferences("vault", Context.MODE_PRIVATE)

/** Copies with Android's sensitive flag and clears after [seconds] if nothing else was copied. */
fun Context.copySecret(label: String, value: String, seconds: Int = 30) {
    val cm = getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply { putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
    android.os.Handler(mainLooper).postDelayed({ if (cm.primaryClip?.getItemAt(0)?.text?.toString() == value) runCatching { cm.clearPrimaryClip() } }, seconds * 1000L)
}

@Composable
fun VaultApp() {
    val context = LocalContext.current
    Vault.init(context)
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val lockAfter = context.vaultPrefs().getInt("lockAfter", 0) // seconds in background; 0 = immediately
    var stoppedAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { stoppedAt = System.currentTimeMillis(); if (lockAfter == 0) Vault.lock() }
                Lifecycle.Event.ON_START -> if (Vault.unlocked && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > lockAfter * 1000L) Vault.lock()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    // Idle lock: five minutes without a touch.
    LaunchedEffect(Vault.unlocked) { while (Vault.unlocked) { delay(15_000); if (Vault.idleMs() > 5 * 60_000L) Vault.lock() } }
    if (!Vault.unlocked) {
        WonderApp(appName = "Vault", about = ABOUT) { UnlockPane(onUnlocked = {}) }
        return
    }
    UnlockedVault()
}

/* ------------------------------------------------- unlock ------------------------------------------ */

@Composable
fun UnlockPane(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    val creating = !Vault.exists
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recovery by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importUri = uri }
    }
    fun attempt(withPassword: String? = null, block: () -> Unit) {
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching(block) }
            busy = false
            r.onSuccess {
                if (withPassword != null && !BioKey.enabled(context) && BioKey.available(context) && !context.vaultPrefs().getBoolean("bioDeclined", false)) offerBio.value = withPassword
                onUnlocked()
            }.onFailure { error = it.message ?: "That didn't work" }
        }
    }
    fun bio() {
        val cipher = BioKey.decryptCipher(context) ?: kotlin.run { error = "Fingerprint unlock was reset. Unlock with your password and turn it on again."; return }
        BiometricPrompt.Builder(context).setTitle("Unlock Vault").setSubtitle("Confirm it's you").setNegativeButton("Use password", context.mainExecutor) { _, _ -> }.build()
            .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher ?: return
                    attempt { Vault.unlock(BioKey.read(context, c)) }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) error = errString.toString()
                }
            })
    }
    LaunchedEffect(Unit) { if (!creating && BioKey.enabled(context)) bio() }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(12.dp))
        context.appArt(256)?.let { androidx.compose.foundation.Image(it, null, Modifier.size(120.dp)) }
        Text(if (creating) "Create your vault" else "Vault is locked", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
        Text(if (creating) "Choose a master password. It encrypts everything and is never stored. Write it down somewhere safe." else "Enter your master password.",
            style = MaterialTheme.typography.bodyMedium, color = p.secondaryText, textAlign = TextAlign.Center)
        if (recovery) {
            Field(code, { code = it.uppercase() }, "Recovery code", supporting = "The 24-letter code you saved, dashes optional")
            WButton("Unlock with recovery code", {
                val master = Recovery.master(context, code)
                if (master == null) error = "That recovery code doesn't match" else attempt { Vault.unlock(master) }
            }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !busy)
            WButton("Use password instead", { recovery = false }, kind = ButtonKind.Plain)
        } else {
            Field(pw, { pw = it; error = null }, "Master password", visual = if (show) VisualTransformation.None else PasswordVisualTransformation(), keyboard = KeyboardType.Password,
                trailing = { IconButton(onClick = { show = !show }) { Icon(if (show) WonderIcons.VisibilityOff else WonderIcons.Visibility, if (show) "Hide" else "Show") } },
                onDone = { if (!creating && pw.isNotEmpty()) attempt(pw) { Vault.unlock(pw) } })
            if (creating) {
                val (s, label) = Generator.strength(pw)
                StrengthBar(s, label)
                Field(confirm, { confirm = it }, "Confirm master password", visual = PasswordVisualTransformation(), keyboard = KeyboardType.Password,
                    isError = confirm.isNotEmpty() && confirm != pw)
                WButton("Create vault", { attempt(pw) { Vault.create(pw) } }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary,
                    enabled = !busy && pw.length >= 12 && pw == confirm)
                if (pw.isNotEmpty() && pw.length < 12) Text("Use at least 12 characters. A few random words work well.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                WButton("Import a Vault backup instead", { importer.launch(arrayOf("application/json", "*/*")) }, kind = ButtonKind.Plain, icon = WonderIcons.UploadFile)
            } else {
                WButton(if (busy) "Unlocking…" else "Unlock", { attempt(pw) { Vault.unlock(pw) } }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !busy && pw.isNotEmpty())
                if (BioKey.enabled(context)) WButton("Use fingerprint or face", { bio() }, Modifier.fillMaxWidth(), icon = WonderIcons.Lock)
                if (Recovery.enabled(context)) WButton("Forgot password? Use recovery code", { recovery = true }, kind = ButtonKind.Plain)
            }
        }
        if (busy) AquaProgress(null, Modifier.fillMaxWidth())
        error?.let { Text(it, color = p.danger, textAlign = TextAlign.Center) }
    }
    importUri?.let { uri ->
        var master by remember { mutableStateOf("") }
        WonderDialog(onDismissRequest = { importUri = null }, title = { Text("Import backup") }, text = {
            Field(master, { master = it }, "Backup's master password", visual = PasswordVisualTransformation())
        }, confirmButton = {
            TextButton(onClick = {
                val u = uri; importUri = null
                attempt {
                    val raw = context.contentResolver.openInputStream(u)!!.use { it.readBytes() }
                    Vault.engineRef.importNew(raw, master); Vault.lock(); Vault.unlock(master)
                }
            }) { Text("Import") }
        }, dismissButton = { TextButton(onClick = { importUri = null }) { Text("Cancel") } })
    }
}

private var importUri by mutableStateOf<Uri?>(null)

@Composable
private fun StrengthBar(level: Int, label: String) {
    val p = wonderPalette()
    val color = listOf(p.danger, Color(0xFFE67E22), p.warning, p.success, Color(0xFF1E9E5A))[level]
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (0..4).forEach { i -> Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i <= level) color else p.separator)) } }
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun LetterTile(item: Item, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val name = item.title.ifBlank { item.domain }.ifBlank { "?" }
    val hue = (name.lowercase().hashCode() and 0xFFFFFF) % 360
    val c = Color.hsv(hue.toFloat(), .55f, .78f)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .26f)).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.lighten(.25f), c, c.darken(.15f)))),
        contentAlignment = Alignment.Center) {
        Text(name.first().uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = (size.value * .45f).sp))
    }
}

/* ------------------------------------------------- unlocked ---------------------------------------- */

/** Set after a password unlock when fingerprint unlock could be turned on; the unlocked screen asks once. */
private val offerBio = mutableStateOf<String?>(null)

@Composable
private fun BioOffer() {
    val context = LocalContext.current
    val master = offerBio.value ?: return
    WonderDialog(onDismissRequest = { offerBio.value = null }, title = { Text("Unlock with your fingerprint?") }, text = {
        Text("Next time, open Vault with your fingerprint or face instead of typing the master password. The password stays encrypted with a key only your fingerprint can unlock.")
    }, confirmButton = { TextButton(onClick = {
        offerBio.value = null
        val cipher = runCatching { BioKey.encryptCipher() }.getOrNull() ?: return@TextButton
        BiometricPrompt.Builder(context).setTitle("Turn on fingerprint unlock").setNegativeButton("Cancel", context.mainExecutor) { _, _ -> }.build()
            .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { result.cryptoObject?.cipher?.let { BioKey.store(context, it, master) } }
            })
    }) { Text("Turn on") } }, dismissButton = { TextButton(onClick = { context.vaultPrefs().edit().putBoolean("bioDeclined", true).apply(); offerBio.value = null }) { Text("Not now") } })
}

@Composable
private fun UnlockedVault() {
    val context = LocalContext.current
    BioOffer()
    var tab by rememberSaveable { mutableStateOf("items") }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var browsers by showBrowsers
    if (browsers) { BrowsersScreen(onBack = { browsers = false }); return }
    val item = open?.let { id -> Vault.items.find { it.id == id } }
    editing?.let { e -> EditItem(e, onClose = { editing = null }, onSaved = { saved -> editing = null; open = saved.id }); return }
    if (item != null) { ItemDetail(item, onBack = { open = null }, onEdit = { editing = item }); return }
    WonderApp(
        appName = "Vault", about = ABOUT,
        tabs = listOf(WonderTab("items", "Passwords", WonderIcons.Key), WonderTab("gen", "Generator", WonderIcons.Password), WonderTab("health", "Health", WonderIcons.Shield)),
        selectedTab = tab, onTab = { tab = it; Vault.touch() },
        title = when (tab) { "gen" -> "Generator"; "health" -> "Password health"; else -> "Vault" },
        actions = {
            if (tab == "items") ToolbarButton(WonderIcons.Add, "Add login", { editing = Item() })
            ToolbarButton(WonderIcons.Lock, "Lock now", { Vault.lock() })
        },
        settings = { VaultSettings() },
        menu = listOf(WonderMenuItem("Browser and Google passwords", WonderIcons.Language) { browsers = true }),
    ) {
        when (tab) {
            "gen" -> GeneratorPane(onUse = { pw -> editing = Item(pw = pw) })
            "health" -> HealthPane(onOpen = { open = it.id })
            else -> ItemsPane(onOpen = { open = it.id }, onNew = { editing = Item() })
        }
    }
}

@Composable
private fun ItemsPane(onOpen: (Item) -> Unit, onNew: () -> Unit) {
    val p = wonderPalette()
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val cats = listOf("All", "Favorites") + Vault.items.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    val shown = Vault.items.filter { i ->
        (q.isBlank() || i.title.contains(q, true) || i.user.contains(q, true) || i.url.contains(q, true) || i.notes.contains(q, true)) &&
            when (filter) { "All" -> true; "Favorites" -> i.favorite; else -> i.category == filter }
    }
    if (Vault.items.isEmpty()) {
        EmptyState("Your vault is empty", "Add your first login, or let Vault save logins as you sign in to websites and apps.",
            action = { WButton("Add login", onNew, icon = WonderIcons.Add, kind = ButtonKind.Primary) })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(q, { q = it; Vault.touch() }, "Search ${Vault.items.size} logins")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c -> Chip(c, filter == c, { filter = c }, if (c == "Favorites") WonderIcons.Star else null) }
            }
        }
        val favorites = if (filter == "All" && q.isBlank()) shown.filter { it.favorite } else emptyList()
        val grouped = shown.groupBy { it.title.firstOrNull()?.uppercaseChar()?.takeIf { c -> c.isLetter() } ?: '#' }.toSortedMap()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            if (favorites.isNotEmpty()) {
                item { SectionLabel("Favorites", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                itemsIndexed(favorites, key = { _, i -> "f" + i.id }) { idx, i -> ItemRow(i, Modifier.panelItem(idx, favorites.size)) { onOpen(i) } }
                item { Spacer(Modifier.height(14.dp)) }
            }
            grouped.forEach { (letter, list) ->
                item(key = "h$letter") { SectionLabel(letter.toString(), Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp)) }
                itemsIndexed(list, key = { _, i -> i.id }) { idx, i -> ItemRow(i, Modifier.panelItem(idx, list.size)) { onOpen(i) } }
            }
            if (shown.isEmpty()) item { Text("No logins match.", color = p.secondaryText, modifier = Modifier.padding(6.dp)) }
        }
    }
}

@Composable
private fun ItemRow(i: Item, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    ListRow(i.title.ifBlank { i.domain.ifBlank { "Untitled" } }, modifier, subtitle = i.user.ifBlank { i.domain }, leading = { LetterTile(i) }, onClick = { Vault.touch(); onClick() },
        trailing = {
            IconButton(onClick = { context.copySecret("Password", i.pw); toast("Password copied. Clears in 30 seconds"); Vault.touch() }) {
                Icon(WonderIcons.ContentCopy, "Copy password", tint = wonderPalette().secondaryText)
            }
        })
}

@Composable
private fun ItemDetail(i: Item, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var reveal by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    WonderApp(appName = "Vault", about = ABOUT, title = i.title.ifBlank { "Login" }, onBack = onBack,
        actions = {
            ToolbarButton(if (i.favorite) WonderIcons.Star else WonderIcons.StarBorder, "Favorite", { Vault.upsert(i.copy(favorite = !i.favorite)) }, tint = if (i.favorite) Color(0xFFF2A516) else null)
            ToolbarButton(WonderIcons.Edit, "Edit", onEdit)
        },
        menu = listOf(WonderMenuItem("Move to trash", WonderIcons.Delete, danger = true) { confirmDelete = true })
    ) {
        val toast = LocalToast.current
        fun copy(label: String, v: String) { context.copySecret(label, v); toast("$label copied"); Vault.touch() }
        Page {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LetterTile(i, 64.dp); Spacer(Modifier.width(14.dp))
                Column {
                    Text(i.title.ifBlank { "Login" }, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(i.category + if (i.domain.isNotBlank()) " · ${i.domain}" else "", color = p.secondaryText, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Panel {
                if (i.user.isNotBlank()) {
                    ListRow("Username", subtitle = i.user, icon = WonderIcons.Person, tint = Color(0xFF1E7FE8), trailing = { IconButton(onClick = { copy("Username", i.user) }) { Icon(WonderIcons.ContentCopy, "Copy username") } })
                    PanelDivider()
                }
                ListRow("Password", subtitle = if (reveal) i.pw else "•".repeat(i.pw.length.coerceIn(8, 16)), icon = WonderIcons.Key, tint = Color(0xFFE5483C), trailing = {
                    Row {
                        IconButton(onClick = { reveal = !reveal; Vault.touch() }) { Icon(if (reveal) WonderIcons.VisibilityOff else WonderIcons.Visibility, if (reveal) "Hide" else "Show") }
                        IconButton(onClick = { copy("Password", i.pw) }) { Icon(WonderIcons.ContentCopy, "Copy password") }
                    }
                })
                val (lvl, label) = Generator.strength(i.pw)
                Box(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 10.dp)) { StrengthBar(lvl, label) }
                if (i.totp.isNotBlank()) {
                    PanelDivider()
                    val now = rememberNow(1000)
                    val code = Totp.code(i.totp, now)
                    ListRow("Two-factor code", subtitle = code?.first?.chunked(3)?.joinToString(" ") ?: "Invalid key", icon = WonderIcons.Timer, tint = Color(0xFF2E9E44), trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            code?.let { Text("${it.second}s", color = if (it.second <= 5) p.danger else p.secondaryText, style = MaterialTheme.typography.labelMedium) }
                            IconButton(onClick = { code?.let { copy("Code", it.first) } }) { Icon(WonderIcons.ContentCopy, "Copy code") }
                        }
                    })
                }
                if (i.url.isNotBlank()) {
                    PanelDivider()
                    ListRow("Website", subtitle = i.url, icon = WonderIcons.Public, tint = Color(0xFF16A085), chevron = true, onClick = {
                        context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(if (i.url.contains("://")) i.url else "https://${i.url}"))) {}
                    })
                }
            }
            if (i.notes.isNotBlank()) Panel(title = "Notes") { SelectionContainer { Text(i.notes, Modifier.padding(16.dp)) } }
            if (i.history.isNotEmpty()) Panel(title = "Previous passwords", trailing = { TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide" else "Show") } }) {
                if (showHistory) i.history.forEachIndexed { idx, (old, at) ->
                    if (idx > 0) PanelDivider(16.dp)
                    ListRow(old, subtitle = "Replaced " + relativeTime(at), trailing = { IconButton(onClick = { copy("Old password", old) }) { Icon(WonderIcons.ContentCopy, "Copy") } })
                } else ListRow("${i.history.size} saved", subtitle = "Recover a password you changed by mistake", icon = WonderIcons.History, tint = Color(0xFF7A7F87))
            }
            Text("Created ${relativeTime(i.created)} · edited ${relativeTime(i.updated)}", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
        }
    }
    if (confirmDelete) ConfirmDialog("Move to trash?", "You can restore it from Settings › Trash for 30 days.", "Move to trash", onConfirm = { Vault.delete(i); onBack() }, onDismiss = { confirmDelete = false }, danger = true)
}

@Composable
private fun EditItem(initial: Item, onClose: () -> Unit, onSaved: (Item) -> Unit) {
    val p = wonderPalette()
    var i by remember { mutableStateOf(initial) }
    var show by remember { mutableStateOf(initial.pw.isNotEmpty() && Vault.items.none { it.id == initial.id }) }
    var gen by remember { mutableStateOf(false) }
    val isNew = Vault.items.none { it.id == initial.id }
    val reusedBy = Vault.items.filter { it.id != i.id && it.pw == i.pw && i.pw.isNotEmpty() }
    WonderApp(appName = "Vault", about = ABOUT, title = if (isNew) "New login" else "Edit login", onBack = onClose, actions = {
        val toast = LocalToast.current
        TextButton(enabled = i.title.isNotBlank() || i.url.isNotBlank(), onClick = {
            val fixed = i.copy(title = i.title.ifBlank { rootDomain(domainOf(i.url)).substringBefore('.').replaceFirstChar(Char::uppercase) }.trim(), user = i.user.trim(), url = i.url.trim())
            runCatching { Vault.upsert(fixed) }.onSuccess { onSaved(fixed) }.onFailure { toast(it.message ?: "Couldn't save") }
        }) { Text("Save", color = p.toolbarText, style = MaterialTheme.typography.titleMedium) }
    }) {
        Page {
            Panel {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(i.title, { i = i.copy(title = it) }, "Name (e.g. Netflix)")
                    Field(i.url, { i = i.copy(url = it) }, "Website", keyboard = KeyboardType.Uri)
                    Field(i.user, { i = i.copy(user = it) }, "Username or email", keyboard = KeyboardType.Email)
                    Field(i.pw, { i = i.copy(pw = it) }, "Password", visual = if (show) VisualTransformation.None else PasswordVisualTransformation(), keyboard = KeyboardType.Password,
                        trailing = { Row {
                            IconButton(onClick = { show = !show }) { Icon(if (show) WonderIcons.VisibilityOff else WonderIcons.Visibility, if (show) "Hide" else "Show") }
                            IconButton(onClick = { gen = true }) { Icon(WonderIcons.Autorenew, "Generate") }
                        } })
                    if (i.pw.isNotEmpty()) { val (l, s) = Generator.strength(i.pw); StrengthBar(l, s) }
                    if (reusedBy.isNotEmpty()) Text("Also used for ${reusedBy.joinToString { it.title }}. A unique password is safer.", style = MaterialTheme.typography.bodySmall, color = p.warning)
                }
            }
            Panel(title = "More") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(i.totp, { i = i.copy(totp = it.trim()) }, "Two-factor key (optional)", supporting = "The setup key or otpauth:// link a site shows when you turn on 2FA")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Login", "Email", "Social", "Banking", "Shopping", "Work", "Games", "Wi-Fi", "Other").forEach { c -> Chip(c, i.category == c, { i = i.copy(category = c) }) }
                    }
                    Field(i.notes, { i = i.copy(notes = it) }, "Notes", singleLine = false, minLines = 3)
                }
            }
        }
    }
    if (gen) WonderDialog(onDismissRequest = { gen = false }, title = { Text("Generate password") }, text = { GeneratorPane(compact = true, onUse = { i = i.copy(pw = it); show = true; gen = false }) },
        confirmButton = { TextButton(onClick = { gen = false }) { Text("Close") } })
}

/* ------------------------------------------------ generator ---------------------------------------- */

@Composable
private fun GeneratorPane(compact: Boolean = false, onUse: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.vaultPrefs() }
    val p = wonderPalette()
    var mode by rememberSaveable { mutableIntStateOf(prefs.getInt("genMode", 0)) }
    var length by rememberSaveable { mutableFloatStateOf(prefs.getFloat("genLen", 20f)) }
    var upper by rememberSaveable { mutableStateOf(prefs.getBoolean("genUpper", true)) }
    var digits by rememberSaveable { mutableStateOf(prefs.getBoolean("genDigits", true)) }
    var symbols by rememberSaveable { mutableStateOf(prefs.getBoolean("genSymbols", true)) }
    var avoid by rememberSaveable { mutableStateOf(prefs.getBoolean("genAvoid", true)) }
    var words by rememberSaveable { mutableFloatStateOf(prefs.getFloat("genWords", 5f)) }
    var tick by remember { mutableIntStateOf(0) }
    val pw = remember(mode, length.toInt(), upper, digits, symbols, avoid, words.toInt(), tick) {
        prefs.edit().putInt("genMode", mode).putFloat("genLen", length).putBoolean("genUpper", upper).putBoolean("genDigits", digits).putBoolean("genSymbols", symbols)
            .putBoolean("genAvoid", avoid).putFloat("genWords", words).apply()
        if (mode == 0) Generator.password(length.toInt(), upper, digits, symbols, avoid) else Generator.passphrase(words.toInt(), "-", true, true)
    }
    val toast = LocalToast.current
    Column(if (compact) Modifier else Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer { Text(pw, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 22.sp)) }
                val (l, s) = Generator.strength(pw); StrengthBar(l, s)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WButton("New", { tick++ }, icon = WonderIcons.Refresh)
                    WButton("Copy", { context.copySecret("Password", pw); toast("Copied") }, icon = WonderIcons.ContentCopy)
                    WButton(if (compact) "Use" else "Save as login", { onUse(pw) }, kind = ButtonKind.Primary)
                }
            }
        }
        Segmented(listOf("Password", "Passphrase"), mode) { mode = it }
        Panel {
            Column(Modifier.padding(12.dp)) {
                if (mode == 0) {
                    Text("Length: ${length.toInt()}", style = MaterialTheme.typography.titleSmall)
                    Slider(length, { length = it }, valueRange = 8f..64f, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
                    listOf(Triple("Capital letters", upper) { v: Boolean -> upper = v }, Triple("Numbers", digits) { v: Boolean -> digits = v },
                        Triple("Symbols", symbols) { v: Boolean -> symbols = v }, Triple("Avoid look-alikes (l, I, O, 0, 1)", avoid) { v: Boolean -> avoid = v }).forEach { (label, on, set) ->
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = p.accent)) }
                    }
                } else {
                    Text("Words: ${words.toInt()}", style = MaterialTheme.typography.titleSmall)
                    Slider(words, { words = it }, valueRange = 3f..10f, steps = 6, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
                    Text("Passphrases are easy to type and remember, and very hard to guess.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                }
            }
        }
    }
}

/* -------------------------------------------------- health ----------------------------------------- */

@Composable
private fun HealthPane(onOpen: (Item) -> Unit) {
    val p = wonderPalette()
    val h = remember(Vault.items) { health(Vault.items) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { h.score / 100f }, modifier = Modifier.fillMaxSize(), strokeWidth = 8.dp,
                            color = when { h.score >= 80 -> p.success; h.score >= 50 -> p.warning; else -> p.danger }, trackColor = p.separator)
                        Text("${h.score}", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(when { h.score >= 80 -> "Looking good"; h.score >= 50 -> "Room to improve"; else -> "Needs attention" }, style = MaterialTheme.typography.titleLarge)
                        Text("${Vault.items.size} logins checked on this phone. Nothing is sent anywhere.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        fun section(title: String, list: List<Item>, why: String) {
            if (list.isEmpty()) return
            item { SectionLabel("$title (${list.size})", Modifier.padding(start = 6.dp, top = 8.dp, bottom = 6.dp)) }
            itemsIndexed(list, key = { _, i -> title + i.id }) { idx, i -> ListRow(i.title, Modifier.panelItem(idx, list.size), subtitle = why, leading = { LetterTile(i) }, chevron = true, onClick = { onOpen(i) }) }
        }
        section("Weak", h.weak, "Easy to guess. Generate a stronger one")
        section("Reused", h.reused.values.flatten().distinctBy { it.id }, "Same password as another login")
        section("Not changed in a year", h.old, "Consider updating it")
        if (h.weak.isEmpty() && h.reused.isEmpty() && h.old.isEmpty()) item { EmptyState("No problems found", "Every password is strong and unique.", icon = WonderIcons.Verified) }
    }
}

/* ------------------------------------------------- settings ---------------------------------------- */

@Composable
private fun VaultSettings() {
    val context = LocalContext.current
    val prefs = remember { context.vaultPrefs() }
    val p = wonderPalette()
    var bio by remember { mutableStateOf(BioKey.enabled(context)) }
    var lockAfter by remember { mutableIntStateOf(prefs.getInt("lockAfter", 0)) }
    var askMaster by remember { mutableStateOf<String?>(null) } // "bio" | "recovery" | "rotate"
    var newCode by remember { mutableStateOf<String?>(null) }
    var trash by remember { mutableStateOf(false) }
    val af = remember { context.getSystemService(AutofillManager::class.java) }
    val autofillOn = af?.hasEnabledAutofillServices() == true
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Vault.engineRef.exportEncrypted()) } }
    }
    Panel(title = "Unlocking") {
        val bioReady = remember { BioKey.available(context) }
        SwitchRow("Unlock with fingerprint or face", bio, { on -> if (on) { if (bioReady) askMaster = "bio" else context.launchSafely(BioKey.enrollIntent()) {} } else { BioKey.disable(context); bio = false } },
            subtitle = if (bioReady) (if (bio) "On: Vault opens with your fingerprint" else "Off") else "Add a fingerprint or face in Android settings first (tap to set up)", icon = WonderIcons.Lock, tint = Color(0xFF2E9E44))
        PanelDivider()
        var menu by remember { mutableStateOf(false) }
        Box {
            ListRow("Lock when I leave the app", subtitle = when (lockAfter) { 0 -> "Immediately"; 60 -> "After 1 minute"; 300 -> "After 5 minutes"; else -> "After ${lockAfter / 60} minutes" },
                icon = WonderIcons.Timer, tint = Color(0xFF1E7FE8), chevron = true, onClick = { menu = true })
            DropdownMenu(menu, { menu = false }, Modifier.background(p.panel)) {
                listOf(0 to "Immediately", 60 to "After 1 minute", 300 to "After 5 minutes", 900 to "After 15 minutes").forEach { (s, l) ->
                    DropdownMenuItem({ Text(l) }, { lockAfter = s; prefs.edit().putInt("lockAfter", s).apply(); menu = false })
                }
            }
        }
        PanelDivider()
        ListRow("Change master password", icon = WonderIcons.Password, tint = Color(0xFF8E44AD), chevron = true, onClick = { askMaster = "rotate" })
    }
    Panel(title = "Autofill", footer = "Vault fills usernames and passwords in Firefox and other apps, and offers to save new logins when you sign in.") {
        ListRow(if (autofillOn) "Vault is your autofill service" else "Use Vault for autofill", subtitle = if (autofillOn) "On" else "Tap to choose Vault in Android settings",
            icon = WonderIcons.Bolt, tint = Color(0xFFF2A516), chevron = !autofillOn, onClick = {
                context.launchSafely(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${context.packageName}"))) {}
            })
    }
    Panel(title = "Browsers") {
        ListRow("Browser and Google passwords", subtitle = "Bring in passwords from Chrome, Firefox and Google, or send Vault's to them", icon = WonderIcons.Language, tint = Color(0xFF1E7FE8), chevron = true,
            onClick = { showBrowsers.value = true })
    }
    Panel(title = "Recovery", footer = "Without a recovery code, a forgotten master password can't be recovered. Nobody, including Wonderdesk, can open your vault.") {
        ListRow(if (Recovery.enabled(context)) "New recovery code" else "Create a recovery code", subtitle = if (Recovery.enabled(context)) "A code is set up" else "Unlocks Vault if you forget your password",
            icon = WonderIcons.Key, tint = Color(0xFFE5483C), chevron = true, onClick = { askMaster = "recovery" })
        PanelDivider()
        ListRow("Export encrypted backup", subtitle = "Same format as Vault for Linux", icon = WonderIcons.Download, tint = Color(0xFF16A085), chevron = true, onClick = { exporter.launch("vault-backup.json") })
        PanelDivider()
        ListRow("Trash", subtitle = "${Vault.trash.size} deleted login${if (Vault.trash.size == 1) "" else "s"}", icon = WonderIcons.Delete, tint = Color(0xFF7A7F87), chevron = true, onClick = { trash = true })
    }
    askMaster?.let { purpose ->
        var pw by remember { mutableStateOf("") }
        var next by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        WonderDialog(onDismissRequest = { askMaster = null }, title = { Text(when (purpose) { "rotate" -> "Change master password"; "bio" -> "Turn on fingerprint unlock"; else -> "Recovery code" }) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(pw, { pw = it; err = null }, "Current master password", visual = PasswordVisualTransformation(), isError = err != null, supporting = err)
                if (purpose == "rotate") { Field(next, { next = it }, "New master password", visual = PasswordVisualTransformation()); if (next.isNotEmpty()) Generator.strength(next).let { (l, s) -> StrengthBar(l, s) } }
            }
        }, confirmButton = {
            TextButton(onClick = {
                if (!Vault.verify(pw)) { err = "That isn't your master password"; return@TextButton }
                when (purpose) {
                    "bio" -> {
                        val cipher = runCatching { BioKey.encryptCipher() }.getOrNull()
                        if (cipher == null) { err = "Set up a fingerprint or face unlock in Android settings first"; return@TextButton }
                        val master = pw
                        BiometricPrompt.Builder(context).setTitle("Turn on fingerprint unlock").setNegativeButton("Cancel", context.mainExecutor) { _, _ -> }.build()
                            .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { result.cryptoObject?.cipher?.let { BioKey.store(context, it, master); bio = true } }
                            })
                        askMaster = null
                    }
                    "recovery" -> { val c = Recovery.newCode(); Recovery.save(context, c, pw); newCode = c; askMaster = null }
                    "rotate" -> {
                        if (next.length < 12) { err = "The new password needs at least 12 characters"; return@TextButton }
                        runCatching { Vault.rotate(pw, next) }.onSuccess {
                            // Both shortcuts hold the old password; refresh the recovery code and ask for fingerprint again.
                            if (BioKey.enabled(context)) { BioKey.disable(context); bio = false }
                            if (Recovery.enabled(context)) { val c = Recovery.newCode(); Recovery.save(context, c, next); newCode = c }
                            askMaster = null
                        }.onFailure { err = it.message }
                    }
                }
            }) { Text("Continue") }
        }, dismissButton = { TextButton(onClick = { askMaster = null }) { Text("Cancel") } })
    }
    newCode?.let { code ->
        WonderDialog(onDismissRequest = {}, title = { Text("Save your recovery code") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Write this down and keep it somewhere safe, away from your phone. It's shown only once.")
                SelectionContainer { Text(code, Modifier.fillMaxWidth().background(p.window, RoundedCornerShape(8.dp)).padding(12.dp), style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace), textAlign = TextAlign.Center) }
            }
        }, confirmButton = { TextButton(onClick = { newCode = null }) { Text("I've saved it") } })
    }
    if (trash) WonderDialog(onDismissRequest = { trash = false }, title = { Text("Trash") }, text = {
        if (Vault.trash.isEmpty()) Text("Deleted logins stay here for 30 days.") else LazyColumn(Modifier.heightIn(max = 400.dp)) {
            itemsIndexed(Vault.trash) { _, i ->
                ListRow(i.title, subtitle = "Deleted " + relativeTime(i.updated), leading = { LetterTile(i) }, trailing = {
                    Row { TextButton(onClick = { Vault.restore(i) }) { Text("Restore") }; TextButton(onClick = { Vault.purge(i) }) { Text("Delete", color = p.danger) } }
                })
            }
        }
    }, confirmButton = { TextButton(onClick = { trash = false }) { Text("Done") } })
}

/* ------------------------------------------- browsers and Google ------------------------------------------- */

private val showBrowsers = mutableStateOf(false)

@Composable
private fun BrowsersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val toast = remember { mutableStateOf<(String) -> Unit>({}) }
    var preview by remember { mutableStateOf<Pair<Uri, BrowserPasswords.Parsed>?>(null) }
    var done by remember { mutableStateOf<Pair<Uri, BrowserPasswords.Merge>?>(null) }
    var exportWarn by remember { mutableStateOf(false) }
    val browsers = remember { BrowserPasswords.installed(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.openInputStream(uri)!!.use { BrowserPasswords.read(it.readBytes().decodeToString()) } }
            .onSuccess { preview = uri to it }.onFailure { toast.value(it.message ?: "Couldn't read that file") }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(BrowserPasswords.export(Vault.items).toByteArray()) } }
            .onSuccess { toast.value("Exported ${Vault.items.size} logins. Import the file in your browser, then delete it") }.onFailure { toast.value("Couldn't save the file") }
    }
    WonderApp(appName = "Vault", about = ABOUT, title = "Browser passwords", onBack = onBack) {
        toast.value = LocalToast.current
        Page {
            Text("Android keeps each app's saved passwords private, so Vault can't read them directly. Instead, export them from the browser (a one-time file), then import that file here.",
                style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
            Panel(title = "1. Export from your browser") {
                browsers.forEachIndexed { i, b ->
                    if (i > 0) PanelDivider()
                    ListRow(b.name, subtitle = b.steps, icon = if (b.pkg == "com.google.android.gms") WonderIcons.Shield else WonderIcons.Language,
                        tint = when (b.name) { "Firefox" -> Color(0xFFE66000); "Chrome" -> Color(0xFF1A73E8); "Google Password Manager" -> Color(0xFF34A853); else -> Color(0xFF7A7F87) },
                        trailing = { TextButton(onClick = { BrowserPasswords.open(context, b) }) { Text("Open") } })
                }
            }
            Panel(title = "2. Import the file", footer = "Works with Chrome, Google Password Manager, Firefox, Samsung Internet, Edge, Bitwarden, 1Password, LastPass and other CSV exports. Duplicates are merged; changed passwords keep the old one in history.") {
                ListRow("Choose the exported file", subtitle = "Usually in Downloads, ending in .csv", icon = WonderIcons.UploadFile, tint = Color(0xFF2E9E44), chevron = true,
                    onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "*/*")) })
            }
            Panel(title = "Send Vault's passwords to a browser") {
                ListRow("Export for Chrome, Google or Firefox", subtitle = "A CSV file the browser can import", icon = WonderIcons.Download, tint = Color(0xFF8E44AD), chevron = true, onClick = { exportWarn = true })
            }
            Panel(title = "Let Vault do the filling", footer = "With Vault as your autofill service, browsers ask Vault instead of saving passwords themselves. Then turn off “Save passwords” in each browser so there's one place to manage them.") {
                val af = remember { context.getSystemService(AutofillManager::class.java) }
                ListRow(if (af?.hasEnabledAutofillServices() == true) "Vault fills passwords" else "Use Vault for autofill", icon = WonderIcons.Bolt, tint = Color(0xFFF2A516), chevron = true,
                    onClick = { context.launchSafely(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${context.packageName}"))) {} })
            }
        }
    }
    preview?.let { (uri, parsed) ->
        val newCount = parsed.items.count { n -> Vault.items.none { e -> e.user.equals(n.user, true) && rootDomain(e.domain) == rootDomain(n.domain) } }
        WonderDialog(onDismissRequest = { preview = null }, title = { Text("Import ${parsed.items.size} logins?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("From ${parsed.source}. $newCount are new; the rest match logins already in Vault and will be updated if the password changed.")
                Panel { parsed.items.take(5).forEachIndexed { i, it -> if (i > 0) PanelDivider(); ListRow(it.title.ifBlank { it.domain }, subtitle = it.user) } }
                if (parsed.items.size > 5) Text("and ${parsed.items.size - 5} more", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            }
        }, confirmButton = { TextButton(onClick = { val m = BrowserPasswords.merge(parsed.items); preview = null; done = uri to m }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } })
    }
    done?.let { (uri, m) ->
        WonderDialog(onDismissRequest = { done = null }, title = { Text("Imported") }, text = {
            Text("${m.added} added, ${m.updated} updated, ${m.same} already up to date.\n\nThe exported file still has every password in plain text. Delete it now?")
        }, confirmButton = { TextButton(onClick = {
            val ok = runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            toast.value(if (ok) "Export file deleted" else "Couldn't delete it here. Delete it in Wayfinder"); done = null
        }) { Text("Delete file", color = p.danger) } }, dismissButton = { TextButton(onClick = { done = null }) { Text("Keep") } })
    }
    if (exportWarn) ConfirmDialog("Export ${Vault.items.size} passwords?", "The file isn't encrypted. Anyone who opens it can read every password. Import it into your browser right away, then delete it.",
        "Export", onConfirm = { saver.launch("vault-passwords.csv") }, onDismiss = { exportWarn = false })
}
