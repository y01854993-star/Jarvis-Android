package com.jarvis.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
