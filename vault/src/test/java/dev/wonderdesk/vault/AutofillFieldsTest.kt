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
}
