package br.com.estouseguro.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityTest {
    @Test fun `normaliza somente celular brasileiro`() {
        assertEquals("+5531991234567", BrazilianPhones.normalize("(31) 99123-4567"))
        assertEquals("+5531991234567", BrazilianPhones.normalize("+55 31 99123-4567"))
        assertNull(BrazilianPhones.normalize("31 3123-4567"))
        assertNull(BrazilianPhones.normalize("+1 202 555 0100"))
    }

    @Test fun `tokens sao aleatorios e hash e deterministico`() {
        assertNotEquals(SecureTokens.randomToken(), SecureTokens.randomToken())
        assertTrue(SecureTokens.hmac("pepper", "value").contentEquals(SecureTokens.hmac("pepper", "value")))
    }

    @Test fun `assinatura de webhook usa hmac`() {
        val payload = "evento".toByteArray()
        val hex = SecureTokens.hmacSha256("segredo".toByteArray(), payload).joinToString("") { "%02x".format(it) }
        assertTrue(SecureTokens.verifyHexHmac("sha256=$hex", "segredo", payload))
        assertFalse(SecureTokens.verifyHexHmac("sha256=${"0".repeat(64)}", "segredo", payload))
    }
}
