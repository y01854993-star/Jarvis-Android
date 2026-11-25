package com.jarvis.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {
    @Test fun masksApiKeys() {
        val s = Redact.mask("key=AIzaSyA1234567890123456789012345678901 and ghp_abcdefghijklmnopqrstuvwx and sk-abcdefghijklmnopqrstuvwxyz")
        assertFalse(s.contains("AIza")); assertFalse(s.contains("ghp_")); assertFalse(s.contains("sk-abc"))
    }

    @Test fun masksEmailsAndPhoneNumbers() {
        assertEquals("call <number> or <email>", Redact.mask("call +20 100 123 4567 or me@example.com"))
    }

    @Test fun keepsOrdinaryText() {
        assertEquals("gemini: timeout (code 503)", Redact.mask("gemini: timeout (code 503)"))
    }

    @Test fun detectsKeysTypedIntoTheChat() {
        assertTrue(Redact.looksLikeSecret("AQ.Ab8RN6LFtyjnYJxZM6B5svRsmNkR7YBzFAKEFAKEFAKE"))
        assertTrue(Redact.looksLikeSecret("  sk-abcdefghijklmnopqrstuvwxyz123456 "))
        assertTrue(Redact.looksLikeSecret("AIzaSyA1234567890123456789012345678901"))
    }

    @Test fun ordinarySentencesAreNotSecrets() {
        assertFalse(Redact.looksLikeSecret("افتح يوتيوب وشغل أول فيديو"))
        assertFalse(Redact.looksLikeSecret("hello world this is a normal request 123"))
        assertFalse(Redact.looksLikeSecret("supercalifragilisticexpialidocious"))
    }
}
