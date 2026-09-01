package br.com.estouseguro.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxActivationCodesTest {
    @Test fun `gera codigo legivel no formato canonico`() {
        val codes = List(100) { SandboxActivationCodes.generate() }

        assertEquals(100, codes.toSet().size)
        codes.forEach { code ->
            assertTrue(code.matches(Regex("[23456789ABCDEFGHJKMNPQRSTVWXYZ]{4}(-[23456789ABCDEFGHJKMNPQRSTVWXYZ]{4}){2}")))
            assertNotNull(SandboxActivationCodes.normalize(code))
        }
    }

    @Test fun `normalizacao aceita caixa espaco e hifen sem aceitar caracteres ambiguos`() {
        assertEquals("ABCD2345WXYZ", SandboxActivationCodes.normalize(" abcd-2345 wxyz "))
        assertNull(SandboxActivationCodes.normalize("ABCD-1234-WXYZ"))
        assertNull(SandboxActivationCodes.normalize("ABCD-2345-WXYO"))
        assertNull(SandboxActivationCodes.normalize("ABCD_2345_WXYZ"))
        assertNull(SandboxActivationCodes.normalize("ABCD-2345-WXY"))
    }

    @Test fun `validade maxima e de quinze minutos e consumo e unico`() {
        val created = Instant.parse("2026-09-01T12:00:00Z")
        val expires = SandboxActivationCodes.expiresAt(created)

        assertEquals(created.plusSeconds(900), expires)
        assertTrue(SandboxActivationCodes.isUsable(expires, null, created.plusSeconds(899)))
        assertFalse(SandboxActivationCodes.isUsable(expires, null, expires))
        assertFalse(SandboxActivationCodes.isUsable(expires, created.plusSeconds(1), created.plusSeconds(2)))
    }
}
