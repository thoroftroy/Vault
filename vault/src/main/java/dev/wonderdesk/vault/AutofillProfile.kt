package dev.wonderdesk.vault

import org.json.JSONObject

/** The person's own details (name, address, phone, a card) for filling forms. Kept as one encrypted Vault entry, like a login. */
object AutofillProfile {
    const val CAT = "Autofill details"
    val PERSONAL_KEYS = setOf("fullName", "first", "last", "email", "phone", "line1", "line2", "city", "region", "postcode", "country", "org")
    val CARD_KEYS = setOf("cardNumber", "cardName", "cardExp", "cardCvc")
    val FIELDS = listOf("first" to "First name", "last" to "Last name", "email" to "Email", "phone" to "Phone", "line1" to "Address", "line2" to "Apartment, suite", "city" to "City", "region" to "State or province",
        "postcode" to "Postcode or ZIP", "country" to "Country", "org" to "Company")
    val CARD_FIELDS = listOf("cardName" to "Name on card", "cardNumber" to "Card number", "cardExp" to "Expiry (MM/YY)", "cardCvc" to "Security code")

    private fun entry() = Vault.items.firstOrNull { it.category == CAT }

    fun get(): Map<String, String> = runCatching {
        val o = JSONObject(entry()?.notes ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getString(it) }.filterValues { it.isNotBlank() }
    }.getOrDefault(emptyMap())

    fun save(values: Map<String, String>) {
        val json = JSONObject().also { o -> values.filterValues { it.isNotBlank() }.forEach { (k, v) -> o.put(k, v.trim()) } }.toString()
        Vault.upsert((entry() ?: Item(title = "Autofill details", category = CAT)).copy(notes = json))
    }

    /** The value a form field of this kind should get. */
    fun valueFor(v: Map<String, String>, key: String): String? = when (key) {
        "fullName" -> listOfNotNull(v["first"], v["last"]).joinToString(" ").ifBlank { null }
        else -> v[key]?.ifBlank { null }
    }
}
