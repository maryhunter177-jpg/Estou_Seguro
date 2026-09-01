package br.com.estouseguro.platform.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SandboxBackendClientTest {
    @Test
    fun `idempotency key is stable for the same local alert`() {
        assertEquals("android-alert-v1-42", SandboxBackendClient.idempotencyKey(42))
        assertEquals("android-alert-v1-42", SandboxBackendClient.idempotencyKey(42))
    }

    @Test
    fun `idempotency key rejects unsaved alert`() {
        assertThrows(IllegalArgumentException::class.java) {
            SandboxBackendClient.idempotencyKey(0)
        }
    }

    @Test
    fun `normalizes activation code without persisting separators`() {
        val raw = " 2abc-3def 4ghj ".toCharArray()

        assertEquals("2ABC3DEF4GHJ", SandboxBackendClient.normalizeActivationCode(raw))
        assertEquals("2ABC-3DEF-4GHJ", SandboxBackendClient.formatActivationCode(raw.concatToString()))
    }

    @Test
    fun `rejects ambiguous or incomplete activation codes`() {
        assertNull(SandboxBackendClient.normalizeActivationCode("2ABC-3DEF-4GHI".toCharArray()))
        assertNull(SandboxBackendClient.normalizeActivationCode("2ABC-3DEF".toCharArray()))
    }
}
