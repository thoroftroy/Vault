package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasswordStrengthTest {
    @Test fun labelsRewardLengthAndCharacterClasses() {
        assertEquals(0 to "Weak", Passwords.strength(""))
        assertEquals("Weak", Passwords.strength("password").second)
        assertEquals("Fair", Passwords.strength("Password123").second)
        assertEquals("Good", Passwords.strength("Password1234").second)
        assertEquals(7 to "Strong", Passwords.strength("Correct-Horse-29-Xy"))
    }
}
