package br.com.estouseguro.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertStateTest {
    @Test fun `alerta sem destinatario autorizado falha de forma explicita`() {
        assertEquals("FAILED", aggregateAlertState("QUEUED", emptyList()))
    }

    @Test fun `fila progride para processamento e nao regride em retry`() {
        assertEquals("PROCESSING", aggregateAlertState("QUEUED", listOf("CLAIMED", "PENDING")))
        assertEquals("PROCESSING", aggregateAlertState("PROCESSING", listOf("RETRY", "PENDING")))
    }

    @Test fun `resultado terminal distingue sucesso falha e parcial`() {
        assertEquals("COMPLETE", aggregateAlertState("PROCESSING", listOf("DELIVERED", "READ")))
        assertEquals("FAILED", aggregateAlertState("PROCESSING", listOf("FAILED", "FAILED")))
        assertEquals("PARTIAL", aggregateAlertState("PROCESSING", listOf("DELIVERED", "FAILED")))
    }

    @Test fun `estado terminal nunca regride por evento atrasado`() {
        listOf("COMPLETE", "PARTIAL", "FAILED").forEach { terminal ->
            assertEquals(terminal, aggregateAlertState(terminal, listOf("PENDING", "CLAIMED")))
        }
    }

    @Test fun `pagina muda de autorizacao para revogacao`() {
        val token = "a".repeat(43)
        assertTrue(consentPage(token, "PENDING").contains("AUTORIZAR ALERTAS"))
        assertTrue(consentPage(token, "GRANTED").contains("REVOGAR AUTORIZAÇÃO"))
        assertTrue(consentPage(token, "REVOKED").contains("Consentimento revogado"))
    }
}
