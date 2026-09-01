package br.com.estouseguro.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencySmsFormatterTest {
    @Test fun `mensagens de todas as categorias cabem em um SMS`() {
        SmsEmergencyCategory.entries.forEach { category ->
            val message = EmergencySmsFormatter.format(category, GeoPoint(-19.778284, -42.1491415, 1))
            assertTrue(message.length <= EmergencySmsFormatter.MAX_SINGLE_SMS_CHARS)
            assertFalse(message.any { it.code > 127 })
        }
    }

    @Test fun `informa quando localizacao nao existe`() {
        assertTrue(EmergencySmsFormatter.format(SmsEmergencyCategory.GENERAL, null)
            .contains("Localizacao indisponivel"))
    }
}
