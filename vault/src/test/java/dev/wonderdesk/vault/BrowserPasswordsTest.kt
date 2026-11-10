package dev.wonderdesk.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPasswordsTest {
    @Test fun lengthAloneIsNotStrong() {
        assertTrue(Generator.strength("correcthorsebatterystaplecorrecthorse").first <= 1)
        assertTrue(Generator.strength("CorrectHorseBatteryStaple").first <= 2)
        assertTrue(Generator.strength("Correct-Horse-Battery-Staple7").first >= 3)
        assertTrue(Generator.strength("aB3$").first == 0)
        assertTrue(Generator.strength("abcdefghijkl").second.contains("capital"))
    }

    @Test fun generatorMeetsTheRule() {
        repeat(50) { assertTrue(Generator.strength(Generator.password(16, true, true, true, true)).first >= 3) }
        repeat(20) { assertTrue(Generator.missing(Generator.passphrase(4, "-", true, true)).isEmpty()) }
    }

    @Test fun chromeCsv() {
        val p = BrowserPasswords.read("name,url,username,password,note\nexample.com,https://example.com/login,me@x.com,\"p,a\"\"ss\",\"line1\nline2\"\n")
        assertEquals("Chrome or Google", p.source)
        assertEquals(1, p.items.size)
        assertEquals("p,a\"ss", p.items[0].pw)
        assertEquals("line1\nline2", p.items[0].notes)
    }

    @Test fun firefoxCsv() {
        val p = BrowserPasswords.read("\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\r\n" +
            "\"https://www.mozilla.org\",\"fox\",\"Secr3t!\",,\"https://www.mozilla.org\",\"{abc}\",\"1\",\"1\",\"1\"\r\n")
        assertEquals("Firefox", p.source)
        assertEquals("fox", p.items[0].user)
        assertEquals("mozilla.org", p.items[0].title)
    }

    @Test fun bitwardenCsv() {
        val p = BrowserPasswords.read("folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n,,login,Bank,,,0,https://bank.example,alice,Hunter2!,JBSWY3DPEHPK3PXP\n")
        assertEquals("Bitwarden", p.source)
        assertEquals("Bank", p.items[0].title)
        assertEquals("JBSWY3DPEHPK3PXP", p.items[0].totp)
    }

    @Test fun exportRoundTrips() {
        val items = listOf(Item(title = "A, \"quoted\"", user = "u", pw = "p\"w", url = "https://a.example"))
        val back = BrowserPasswords.read(BrowserPasswords.export(items)).items
        assertEquals(items[0].title, back[0].title); assertEquals(items[0].pw, back[0].pw)
    }
}
