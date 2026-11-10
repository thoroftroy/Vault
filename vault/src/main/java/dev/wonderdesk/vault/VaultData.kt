package dev.wonderdesk.vault

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wonderdesk.shared.EncryptedVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** One login. [title], [user] and [pw] live in the Linux-compatible v2 vault; the rest in the encrypted sidecar. */
data class Item(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "", val user: String = "", val pw: String = "",
    val url: String = "", val notes: String = "", val totp: String = "", val category: String = "Login",
    val favorite: Boolean = false, val created: Long = System.currentTimeMillis(), val updated: Long = System.currentTimeMillis(),
    val history: List<Pair<String, Long>> = emptyList(), val apps: List<String> = emptyList(),
) {
    val domain: String get() = domainOf(url)
    fun extrasJson() = JSONObject().put("id", id).put("url", url).put("notes", notes).put("totp", totp).put("category", category).put("favorite", favorite)
        .put("created", created).put("updated", updated).put("apps", JSONArray(apps))
        .put("history", JSONArray(history.map { JSONObject().put("pw", it.first).put("at", it.second) }))
}

fun domainOf(url: String): String {
    val host = url.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').removePrefix("www.")
    return host
}

/** Root of a host for matching, e.g. login.example.co.uk -> example.co.uk, accounts.google.com -> google.com. */
fun rootDomain(host: String): String {
    val parts = host.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
    if (parts.size <= 2) return parts.joinToString(".")
    val twoLevel = setOf("co.uk", "com.au", "co.jp", "co.nz", "com.br", "co.in", "org.uk", "gov.uk", "ac.uk")
    val lastTwo = parts.takeLast(2).joinToString(".")
    return if (lastTwo in twoLevel) parts.takeLast(3).joinToString(".") else lastTwo
}

object Vault {
    private lateinit var engine: EncryptedVault
    private lateinit var sidecar: File
    private lateinit var trashFile: File
    var items by mutableStateOf<List<Item>>(emptyList()); private set
    var trash by mutableStateOf<List<Item>>(emptyList()); private set
    var unlocked by mutableStateOf(false); private set
    private var lastActivity = 0L

    fun init(context: Context) {
        if (::engine.isInitialized) return
        val dir = context.filesDir
        engine = EncryptedVault(File(dir, "vault-v2.json"))
        sidecar = File(dir, "vault-extras.bin")
        trashFile = File(dir, "vault-trash.bin")
    }

    val exists get() = engine.exists
    val engineRef get() = engine

    fun touch() { lastActivity = SystemClock.elapsedRealtime() }
    fun idleMs() = SystemClock.elapsedRealtime() - lastActivity

    fun create(master: String) { engine.create(master); load(); touch() }
    fun unlock(master: String) { engine.unlock(master); load(); touch() }
    fun lock() { engine.lock(); items = emptyList(); trash = emptyList(); unlocked = false }
    fun verify(master: String) = engine.verify(master)

    private fun load() {
        val extras = runCatching { JSONObject(String(engine.openExtra(sidecar.readBytes()))) }.getOrDefault(JSONObject())
        val ids = extras.optJSONArray("order")
        val meta = extras.optJSONObject("items") ?: JSONObject()
        val e = engine.entries()
        items = (0 until e.length()).map { i ->
            val o = e.getJSONObject(i)
            val id = ids?.optString(i)?.takeIf { it.isNotEmpty() && meta.has(it) } ?: UUID.randomUUID().toString()
            val x = meta.optJSONObject(id) ?: JSONObject()
            Item(id, o.getString("title"), o.getString("user"), o.getString("pw"), x.optString("url"), x.optString("notes"), x.optString("totp"),
                x.optString("category", "Login"), x.optBoolean("favorite"), x.optLong("created", System.currentTimeMillis()), x.optLong("updated", System.currentTimeMillis()),
                x.optJSONArray("history")?.let { h -> (0 until h.length()).map { h.getJSONObject(it).let { p -> p.getString("pw") to p.getLong("at") } } }.orEmpty(),
                x.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
        }
        trash = runCatching { JSONArray(String(engine.openExtra(trashFile.readBytes()))).let { a -> (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); Item(o.getString("id"), o.getString("title"), o.getString("user"), o.getString("pw"), o.optString("url"), o.optString("notes"), updated = o.optLong("deleted"))
        } } }.getOrDefault(emptyList()).filter { System.currentTimeMillis() - it.updated < 30L * 86_400_000L }
        unlocked = true
    }

    private fun persist(list: List<Item>) {
        engine.save(JSONArray(list.map { JSONObject().put("title", it.title).put("user", it.user).put("pw", it.pw) }))
        val meta = JSONObject(); list.forEach { meta.put(it.id, it.extrasJson()) }
        val blob = JSONObject().put("order", JSONArray(list.map { it.id })).put("items", meta).toString().toByteArray()
        val tmp = File(sidecar.path + ".tmp"); tmp.writeBytes(engine.sealExtra(blob)); tmp.renameTo(sidecar)
        items = list; touch()
    }

    private fun persistTrash(list: List<Item>) {
        val a = JSONArray(list.map { JSONObject().put("id", it.id).put("title", it.title).put("user", it.user).put("pw", it.pw).put("url", it.url).put("notes", it.notes).put("deleted", it.updated) })
        val tmp = File(trashFile.path + ".tmp"); tmp.writeBytes(engine.sealExtra(a.toString().toByteArray())); tmp.renameTo(trashFile)
        trash = list
    }

    fun upsert(item: Item) {
        val old = items.find { it.id == item.id }
        val withHistory = if (old != null && old.pw != item.pw && old.pw.isNotEmpty()) item.copy(history = (listOf(old.pw to System.currentTimeMillis()) + old.history).take(10)) else item
        val next = if (old == null) items + withHistory.copy(updated = System.currentTimeMillis()) else items.map { if (it.id == item.id) withHistory.copy(updated = System.currentTimeMillis()) else it }
        persist(next.sortedBy { it.title.lowercase() })
    }

    fun delete(item: Item) { persistTrash(listOf(item.copy(updated = System.currentTimeMillis())) + trash); persist(items.filter { it.id != item.id }) }
    fun restore(item: Item) { persistTrash(trash.filter { it.id != item.id }); upsert(item.copy(id = UUID.randomUUID().toString())) }
    fun purge(item: Item) = persistTrash(trash.filter { it.id != item.id })

    fun rotate(current: String, replacement: String) { engine.rotate(current, replacement); persist(items) /* re-seal sidecar with new key */; persistTrash(trash) }

    /** Logins that fit a website (by domain) or an app (by package name). */
    fun matches(webDomain: String?, pkg: String?): List<Item> {
        val root = webDomain?.let { rootDomain(domainOf(it)) }
        return items.filter { i ->
            (root != null && i.domain.isNotEmpty() && rootDomain(i.domain) == root) ||
                (pkg != null && (pkg in i.apps || (i.domain.isNotEmpty() && pkg.split('.').any { part -> part.length > 3 && rootDomain(i.domain).startsWith(part) })))
        }.sortedByDescending { it.updated }
    }
}

/* ------------------------------------------------ generator ---------------------------------------- */

object Generator {
    private val random = SecureRandom()
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*-_=+?"
    private const val AMBIGUOUS_LOWER = "l"; private const val AMBIGUOUS_UPPER = "IO"; private const val AMBIGUOUS_DIGITS = "01"

    fun password(length: Int, upper: Boolean, digits: Boolean, symbols: Boolean, avoidAmbiguous: Boolean): String {
        val sets = buildList {
            add(LOWER + if (avoidAmbiguous) "" else AMBIGUOUS_LOWER)
            if (upper) add(UPPER + if (avoidAmbiguous) "" else AMBIGUOUS_UPPER)
            if (digits) add(DIGITS + if (avoidAmbiguous) "" else AMBIGUOUS_DIGITS)
            if (symbols) add(SYMBOLS)
        }
        val all = sets.joinToString("")
        while (true) {
            val chars = CharArray(length) { all[random.nextInt(all.length)] }
            if (sets.all { set -> chars.any { it in set } } || length < sets.size) return String(chars)
        }
    }

    fun passphrase(words: Int, separator: String, capitalize: Boolean, number: Boolean): String {
        val list = (0 until words).map { WORDS[random.nextInt(WORDS.size)].let { w -> if (capitalize) w.replaceFirstChar(Char::uppercase) else w } }.toMutableList()
        if (number) { val i = random.nextInt(list.size); list[i] = list[i] + random.nextInt(10) }
        val sep = if (separator.any { !it.isLetterOrDigit() }) separator else "-"
        return list.joinToString(sep)
    }

    /** Rough entropy in bits from the character classes used. */
    fun entropy(pw: String): Double {
        if (pw.isEmpty()) return 0.0
        var pool = 0
        if (pw.any { it.isLowerCase() }) pool += 26
        if (pw.any { it.isUpperCase() }) pool += 26
        if (pw.any { it.isDigit() }) pool += 10
        if (pw.any { !it.isLetterOrDigit() }) pool += 32
        val repeats = pw.length - pw.toSet().size
        return (pw.length - repeats / 2.0) * Math.log(pool.toDouble()) / Math.log(2.0)
    }

    /** Which kinds of character a password is missing: capital, lowercase, number, symbol. */
    fun missing(pw: String): List<String> = buildList {
        if (pw.none { it.isUpperCase() }) add("a capital letter")
        if (pw.none { it.isLowerCase() }) add("a lowercase letter")
        if (pw.none { it.isDigit() }) add("a number")
        if (pw.none { !it.isLetterOrDigit() && !it.isWhitespace() }) add("a symbol")
    }

    /**
     * 0 very weak … 4 very strong. Length alone never makes a password strong: it must mix capitals,
     * lowercase, numbers and symbols, and be at least 12 characters, to rate Strong or better.
     */
    fun strength(pw: String): Pair<Int, String> {
        val common = pw.lowercase() in COMMON || pw.length < 6
        val bits = if (common) 0.0 else entropy(pw)
        var level = when {
            bits < 28 -> 0
            bits < 40 -> 1
            bits < 60 -> 2
            bits < 80 -> 3
            else -> 4
        }
        val lacks = missing(pw)
        if (lacks.size >= 2) level = minOf(level, 1)
        if (lacks.isNotEmpty() || pw.length < 12) level = minOf(level, 2)
        val name = listOf("Very weak", "Weak", "Fair", "Strong", "Very strong")[level]
        val hint = when {
            pw.isEmpty() -> ""
            lacks.isNotEmpty() -> " · add " + lacks.joinToString(", ").replaceLast(", ", " and ")
            pw.length < 12 -> " · use 12 or more characters"
            else -> ""
        }
        return level to name + hint
    }

    private fun String.replaceLast(a: String, b: String): String { val i = lastIndexOf(a); return if (i < 0) this else substring(0, i) + b + substring(i + a.length) }

    private val COMMON = setOf("password", "123456", "12345678", "qwerty", "abc123", "111111", "letmein", "iloveyou", "admin", "welcome", "monkey", "dragon",
        "football", "baseball", "sunshine", "princess", "password1", "123456789", "1234567890", "qwerty123", "000000", "trustno1")

    val WORDS = ("acorn actor adapt agent alarm album alert alley amber ample angle ankle apple apron arena armor arrow atlas attic autumn avoid awake " +
        "bacon badge bagel baker banjo barn basin batch beach beard berry bison blade blank blaze bloom board boost booth brain brave bread brick bride brush " +
        "cabin cable cactus camel candy canoe canvas cargo carol carrot cedar chalk charm chess chili choir cider cinema circus civic clamp cliff cloak clover " +
        "coach cobra cocoa comet coral couch crane crater crisp crown crumb cupid curve daisy dance delta denim depot diary dingo disco dodge dolphin donut " +
        "dragon drift drum eagle easel ebony echo eclipse elbow elder ember emerald empire engine envoy epoch equal error essay ethic fable falcon fancy " +
        "feast fern ferry fiber fiddle field finch flame flint flock flute focus forge fossil frost fudge gadget galaxy garden garlic gecko giant ginger " +
        "glide globe glove goose grain grape gravel guitar habit hammer harbor harvest hazel heron hiker honey hornet hotel humble icicle igloo index " +
        "ivory jacket jaguar jasmine jelly jewel jigsaw jolly juice jungle kayak kernel kettle kiwi koala ladder lagoon lantern laser lemon lilac linen " +
        "lizard llama lobster locket lotus lunar lyric magnet mango maple marble meadow melon mentor meteor mimic mint mocha molar mosaic motel muffin " +
        "nectar needle noble noodle novel nugget oasis ocean olive omega onion opera orbit orchid otter oxygen paddle panda papaya parade parrot pastel " +
        "peach pebble pepper piano picnic pilot pixel planet plaza plum polar pony poppy prism puzzle quartz quest quill rabbit radar raven recipe reef " +
        "relic ribbon rider river robin rocket rodeo rumba saddle safari salmon sandal satin scout season shadow shelf shrimp signal silver sketch sloth " +
        "solar sonic spark spice sprout squid stable stamp steam stork summit sunset swan syrup tango teapot temple thunder tiger timber toast tomato " +
        "topaz torch tower trail tulip tundra turtle tuxedo umbra unicorn urban valley velvet violin visor vortex wafer walnut walrus wander wasabi " +
        "willow window winter wizard yacht yodel yogurt zebra zenith zephyr zigzag").split(' ')
}

/* -------------------------------------------------- TOTP ------------------------------------------- */

object Totp {
    fun base32(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = s.uppercase().replace(" ", "").replace("-", "").trimEnd('=')
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0; var bits = 0
        for (c in clean) {
            val v = alphabet.indexOf(c); require(v >= 0) { "That isn't a valid 2FA key" }
            buffer = (buffer shl 5) or v; bits += 5
            if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }

    /** Accepts a raw base32 secret or an otpauth:// URI. Returns (code, seconds left). */
    fun code(secretOrUri: String, now: Long = System.currentTimeMillis()): Pair<String, Int>? = runCatching {
        var secret = secretOrUri; var digits = 6; var period = 30; var algo = "HmacSHA1"
        if (secretOrUri.startsWith("otpauth://")) {
            val uri = android.net.Uri.parse(secretOrUri)
            secret = uri.getQueryParameter("secret") ?: return null
            digits = uri.getQueryParameter("digits")?.toIntOrNull() ?: 6
            period = uri.getQueryParameter("period")?.toIntOrNull() ?: 30
            algo = when (uri.getQueryParameter("algorithm")?.uppercase()) { "SHA256" -> "HmacSHA256"; "SHA512" -> "HmacSHA512"; else -> "HmacSHA1" }
        }
        val counter = now / 1000 / period
        val mac = Mac.getInstance(algo).apply { init(SecretKeySpec(base32(secret), algo)) }
        val h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(counter).array())
        val o = h.last().toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        val code = (bin % Math.pow(10.0, digits.toDouble()).toInt()).toString().padStart(digits, '0')
        code to (period - ((now / 1000) % period)).toInt()
    }.getOrNull()
}

/* ------------------------------------------------- health ------------------------------------------ */

data class Health(val weak: List<Item>, val reused: Map<String, List<Item>>, val old: List<Item>, val score: Int)

fun health(items: List<Item>): Health {
    val weak = items.filter { Generator.strength(it.pw).first <= 1 }
    val reused = items.filter { it.pw.isNotEmpty() }.groupBy { it.pw }.filterValues { it.size > 1 }.mapKeys { it.value.first().id }
    val old = items.filter { System.currentTimeMillis() - it.updated > 365L * 86_400_000L }
    val bad = (weak + reused.values.flatten() + old).map { it.id }.toSet().size
    val score = if (items.isEmpty()) 100 else (100 * (items.size - bad) / items.size)
    return Health(weak, reused, old, score)
}

/* ----------------------------------------- biometric + recovery ------------------------------------ */

/**
 * Biometric unlock keeps the master password encrypted with a hardware-backed key that only works
 * after a fingerprint or face check. Turning it off, or new biometrics being enrolled, deletes it.
 */
object BioKey {
    private const val ALIAS = "wonderdesk-vault-bio"
    private fun file(c: Context) = File(c.noBackupFilesDir, "vault-bio.bin")
    fun enabled(c: Context) = file(c).exists()

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
            .apply { if (android.os.Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) }
            .build())
        return gen.generateKey()
    }

    fun encryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
    fun decryptCipher(c: Context): Cipher? = runCatching {
        val raw = file(c).readBytes()
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
    }.getOrElse { if (it is android.security.keystore.KeyPermanentlyInvalidatedException) disable(c); null }

    fun store(c: Context, authed: Cipher, master: String) { file(c).writeBytes(authed.iv + authed.doFinal(master.toByteArray())) }
    fun read(c: Context, authed: Cipher): String { val raw = file(c).readBytes(); return String(authed.doFinal(raw.copyOfRange(12, raw.size))) }
    fun disable(c: Context) {
        file(c).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/** An optional recovery code that can unlock the vault if the master password is forgotten. */
object Recovery {
    private fun file(c: Context) = File(c.filesDir, "vault-recovery.bin")
    fun enabled(c: Context) = file(c).exists()

    fun newCode(): String {
        val r = SecureRandom(); val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (0 until 6).joinToString("-") { (0 until 4).map { a[r.nextInt(a.length)] }.joinToString("") }
    }

    private fun derive(code: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(code.uppercase().replace(" ", "").toCharArray(), salt, 300_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    fun save(c: Context, code: String, master: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(code, salt), "AES"), GCMParameterSpec(128, nonce)) }
        file(c).writeBytes(salt + nonce + cipher.doFinal(master.toByteArray()))
    }

    fun master(c: Context, code: String): String? = runCatching {
        val raw = file(c).readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(code, raw.copyOfRange(0, 16)), "AES"), GCMParameterSpec(128, raw.copyOfRange(16, 28))) }
        String(cipher.doFinal(raw.copyOfRange(28, raw.size)))
    }.getOrNull()

    fun disable(c: Context) { file(c).delete() }
}

fun sha1Prefix(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02X".format(it) }

@Suppress("unused") private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
