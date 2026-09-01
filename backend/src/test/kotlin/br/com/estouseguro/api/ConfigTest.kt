package br.com.estouseguro.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {
    private fun env() = mapOf(
        "PUBLIC_BASE_URL" to "http://localhost:8080",
        "DATABASE_URL" to "jdbc:postgresql://localhost/test",
        "DATABASE_USER" to "test",
        "DATABASE_PASSWORD" to "test",
        "DEVICE_TOKEN_PEPPER" to "a".repeat(32),
        "CONSENT_TOKEN_PEPPER" to "b".repeat(32),
        "SANDBOX_REGISTRATION_KEY" to "c".repeat(24),
        "META_WEBHOOK_VERIFY_TOKEN" to "verify",
    )

    @Test fun `sandbox permite worker desligado sem credenciais Meta`() {
        assertEquals(false, AppConfig.fromEnvironment(env()).workerEnabled)
    }

    @Test fun `producao exige https e credenciais`() {
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnvironment(env() + ("APP_ENV" to "production")) }
    }

    @Test fun `categoria possui texto seguro para template`() {
        assertEquals("Violência contra a mulher", categoryLabel(AlertCategory.DOMESTIC_VIOLENCE))
        AlertCategory.entries.forEach { require(categoryLabel(it).isNotBlank()) }
    }

    @Test fun `converte conexao gerenciada em jdbc sem credenciais na url`() {
        assertEquals("jdbc:postgresql://db.internal:5432/estou_seguro",
            normalizeDatabaseUrl("postgresql://user:secret@db.internal:5432/estou_seguro"))
    }
}
