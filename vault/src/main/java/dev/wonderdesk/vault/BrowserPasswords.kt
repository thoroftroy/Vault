package dev.wonderdesk.vault

import android.content.Context
import android.net.Uri

/**
 * Moving passwords between Vault and the browsers.
 *
 * Android doesn't let one app read the passwords another app saved, so passwords come over through
 * the browser's own export file (CSV), made on the phone where the browser allows it, or on a computer. Vault reads every common
 * format, merges duplicates, and can delete the plain-text export afterwards.
 */
object BrowserPasswords {
    /**
     * @param onPhone true when the Android app itself can save an export file. Firefox, Edge and Samsung
     * Internet can't (checked Nov 2026), so their guide explains the real route instead of inventing menus.
     */
    data class Browser(val name: String, val pkg: String?, val onPhone: Boolean, val summary: String, val steps: List<String>, val note: String = "")

    val BROWSERS = listOf(
        Browser("Google Password Manager", "com.google.android.gms", true, "Export a file from passwords.google.com",
            listOf("Open passwords.google.com (Vault opens it for you)", "Tap Settings ⚙", "Tap Export passwords, then Export", "The file is saved to Downloads")),
        Browser("Chrome", "com.android.chrome", true, "Chrome on this phone can export a file",
            listOf("In Chrome, tap ⋮ → Settings", "Tap Google Password Manager", "Tap Settings ⚙", "Tap Export passwords", "Save the file to Downloads")),
        Browser("Brave", "com.brave.browser", true, "Brave on this phone can export a file",
            listOf("In Brave, tap ⋮ → Settings", "Tap Password manager", "Tap ⋮ or Settings, then Export passwords", "Save the file to Downloads")),
        Browser("Firefox", "org.mozilla.firefox", false, "Firefox for Android can't export passwords. Use Firefox on a computer",
            listOf("On a computer, sign in to Firefox with the same Mozilla account and let Logins sync", "Open Settings → Passwords (or go to about:logins)", "Click ⋯ (top right) → Export passwords", "Move the .csv file to this phone (USB, Ripple, or any file share)"),
            "Firefox, Fennec, Mull and Iceraven on Android have no export option, so the file has to come from a computer."),
        Browser("Edge", "com.microsoft.emmx", false, "Edge for Android can't export passwords. Use Edge on a computer",
            listOf("On a computer, sign in to Edge with the same Microsoft account and let Passwords sync", "Open Settings → Profiles → Passwords", "Click ⋯ → Export passwords", "Move the .csv file to this phone (USB, Ripple, or any file share)"),
            "Microsoft says passwords can't be exported from Edge for Android or iPhone."),
        Browser("Samsung Internet", "com.sec.android.app.sbrowser", false, "Samsung only exports an encrypted .spass file other apps can't read",
            listOf("If the same passwords are also in Chrome or Google Password Manager, export from there instead", "Otherwise, sign in to each site with Vault as your autofill service and let Vault save them as you go"),
            "Samsung Pass exports only its own encrypted .spass format, so no other password manager can import it directly."),
    )

    fun installed(c: Context): List<Browser> = BROWSERS.filter { b -> b.pkg == "com.google.android.gms" || runCatching { c.packageManager.getPackageInfo(b.pkg!!, 0) }.isSuccess }

    fun open(c: Context, b: Browser) {
        if (b.pkg == "com.google.android.gms") { runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://passwords.google.com/options")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }; return }
        c.packageManager.getLaunchIntentForPackage(b.pkg ?: return)?.let { runCatching { c.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** RFC 4180 CSV: quoted fields, doubled quotes, commas and new lines inside quotes. */
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val f = StringBuilder()
        var i = 0; var quoted = false
        val t = text.removePrefix("﻿")
        while (i < t.length) {
            val ch = t[i]
            if (quoted) {
                if (ch == '"') { if (i + 1 < t.length && t[i + 1] == '"') { f.append('"'); i++ } else quoted = false } else f.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> { row += f.toString(); f.setLength(0) }
                '\r' -> {}
                '\n' -> { row += f.toString(); f.setLength(0); if (row.any { it.isNotEmpty() }) rows += row; row = mutableListOf() }
                else -> f.append(ch)
            }
            i++
        }
        row += f.toString(); if (row.any { it.isNotEmpty() }) rows += row
        return rows
    }

    data class Parsed(val items: List<Item>, val source: String)

    /** Understands Chrome/Google, Firefox, Bitwarden, 1Password, LastPass, Dashlane, Proton Pass, KeePass and Vault's own CSV. */
    fun read(text: String): Parsed {
        val rows = parseCsv(text)
        require(rows.size >= 2) { "That file has no passwords in it" }
        val head = rows[0].map { it.trim().lowercase().replace(' ', '_') }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> head.indexOf(n).takeIf { it >= 0 } }
        val cUrl = col("url", "login_uri", "website", "web_site", "origin", "uri", "location")
        val cUser = col("username", "login_username", "user", "login", "email", "user_name", "login_name")
        val cPw = col("password", "login_password", "pass")
        val cName = col("name", "title", "account", "item_name")
        val cNote = col("note", "notes", "extra", "comments", "comment")
        val cTotp = col("totp", "login_totp", "otpauth", "otp", "one-time_password", "2fa")
        require(cPw != null) { "Couldn't find a password column. Export as CSV from your browser or password manager" }
        val source = when {
            "httprealm" in head || "formactionorigin" in head -> "Firefox"
            "login_uri" in head -> "Bitwarden"
            "grouping" in head -> "LastPass"
            head == listOf("name", "url", "username", "password", "note") || head == listOf("name", "url", "username", "password") -> "Chrome or Google"
            else -> "CSV"
        }
        val items = rows.drop(1).mapNotNull { r ->
            fun v(i: Int?) = i?.let { r.getOrNull(it) }?.trim().orEmpty()
            val pw = v(cPw); val url = v(cUrl); val user = v(cUser)
            if (pw.isEmpty() && user.isEmpty()) return@mapNotNull null
            val host = domainOf(url)
            Item(title = v(cName).ifBlank { host.ifBlank { user } }, user = user, pw = pw, url = url.takeIf { it.startsWith("http") || '.' in it } ?: "",
                notes = v(cNote), totp = v(cTotp))
        }
        return Parsed(items, source)
    }

    data class Merge(val added: Int, val updated: Int, val same: Int)

    /** Adds new logins; for the same site and user name, keeps the newer password (old one goes to history). */
    fun merge(incoming: List<Item>): Merge {
        var added = 0; var updated = 0; var same = 0
        for (n in incoming) {
            val match = Vault.items.firstOrNull { e -> e.user.equals(n.user, true) && (rootDomain(e.domain) == rootDomain(n.domain) && n.domain.isNotEmpty() || e.title.equals(n.title, true) && n.domain.isEmpty()) }
            when {
                match == null -> { Vault.upsert(n); added++ }
                match.pw == n.pw -> same++
                else -> { Vault.upsert(match.copy(pw = n.pw, notes = match.notes.ifBlank { n.notes }, totp = match.totp.ifBlank { n.totp })); updated++ }
            }
        }
        return Merge(added, updated, same)
    }

    /** CSV that Chrome, Google Password Manager and Firefox can all import. */
    fun export(items: List<Item>): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        return "name,url,username,password,note\n" + items.joinToString("\n") { i -> listOf(i.title, i.url.ifBlank { if (i.domain.isNotEmpty()) "https://${i.domain}" else "" }, i.user, i.pw, i.notes).joinToString(",") { q(it) } } + "\n"
    }
}
