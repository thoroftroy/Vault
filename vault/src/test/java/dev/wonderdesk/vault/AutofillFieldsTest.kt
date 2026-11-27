package dev.wonderdesk.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillFieldsTest {
    @Test fun wordsSplitCamelAndPunctuation() {
        assertEquals("search contacts edit text", fieldWords("searchContactsEditText"))
        assertEquals("address line1", fieldWords("address-line1"))
    }

    @Test fun shortWordsNeedWholeWords() {
        assertFalse(hasWords(fieldWords("hotel name"), "tel"))
        assertFalse(hasWords(fieldWords("chapter title"), "apt"))
        assertFalse(hasWords(fieldWords("electricity bill"), "city"))
        assertTrue(hasWords(fieldWords("tel"), "tel"))
        assertTrue(hasWords(fieldWords("creditCardNumber"), "creditcardnumber"))
    }

    @Test fun searchAndChatBoxesAreNotDetails() {
        assertNull(classifyField(emptyList(), "Search contacts", 0))
        assertNull(classifyField(emptyList(), "Hotel or destination", 0))
        assertNull(classifyField(emptyList(), "Type a message", 0))
        assertNull(classifyField(emptyList(), "Album name", 0))
    }

    @Test fun realFieldsStillClassified() {
        assertEquals("phone", classifyField(listOf("phone"), "", 0))
        assertEquals("postcode", classifyField(emptyList(), "ZIP code", 0))
        assertEquals("city", classifyField(emptyList(), "City", 0))
        assertEquals("cardNumber", classifyField(listOf("creditcardnumber"), "", 0))
        assertEquals("first", classifyField(emptyList(), "First name", 0))
        assertEquals("fullName", classifyField(emptyList(), "Name", 0))
    }

    private val nothing = Saved(login = false, details = false, card = false)
    private fun offer(focus: String?, pass: Boolean = false, user: Boolean = false, saved: Saved = nothing, declared: Int = 0, kinds: Int = 0, card: Int = 0, people: Boolean = false) =
        offerDecision(focus, pass, user, saved, declared, kinds, card, people)

    @Test fun signInWithPasswordIsOffered() {
        assertTrue(offer("pass", pass = true, user = true))
        assertTrue(offer("user", pass = true, user = true))
    }

    @Test fun userNameAloneOnlyWhenSaved() {
        assertFalse(offer("user", user = true))
        assertTrue(offer("user", user = true, saved = nothing.copy(login = true)))
    }

    @Test fun searchMessageAndOtherBoxesNever() {
        assertFalse(offer("ignored", pass = true, user = true, saved = Saved(true, true, true)))
        assertFalse(offer("other", pass = true, user = true, saved = Saved(true, true, true)))
    }

    @Test fun unknownFocusOnlyForSavedSignIn() {
        assertFalse(offer(null, pass = true, user = true))
        assertFalse(offer(null, user = true, saved = nothing.copy(login = true)))
        assertTrue(offer(null, pass = true, saved = nothing.copy(login = true)))
    }

    @Test fun detailsNeedSavedDetailsAndARealForm() {
        assertFalse(offer("detail", declared = 2, kinds = 4))
        assertTrue(offer("detail", declared = 1, kinds = 1, saved = nothing.copy(details = true)))
        assertFalse(offer("detail", kinds = 2, saved = nothing.copy(details = true)))
        assertTrue(offer("detail", kinds = 3, saved = nothing.copy(details = true)))
        assertFalse(offer("detail", declared = 3, kinds = 3, saved = nothing.copy(details = true), people = true))
    }

    @Test fun cardOnlyWhenACardIsSaved() {
        assertFalse(offer("card", card = 2))
        assertTrue(offer("card", card = 2, saved = nothing.copy(card = true)))
    }
}
