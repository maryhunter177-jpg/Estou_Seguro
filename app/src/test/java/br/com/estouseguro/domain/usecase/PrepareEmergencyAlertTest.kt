package br.com.estouseguro.domain.usecase

import br.com.estouseguro.domain.model.AlertStatus
import br.com.estouseguro.domain.model.GeoPoint
import br.com.estouseguro.domain.model.SafetyAlert
import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.repository.AlertRepository
import br.com.estouseguro.domain.repository.ContactRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepareEmergencyAlertTest {
    private val contacts = FakeContactRepository()
    private val alerts = FakeAlertRepository()
    private val useCase = PrepareEmergencyAlert(contacts, alerts) { 1_000L }

    @Test
    fun `requires at least one trusted contact`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            useCase.execute(null)
        }

        assertTrue(error.message!!.contains("contato"))
        assertTrue(alerts.items.isEmpty())
    }

    @Test
    fun `persists alert and includes location in message`() {
        contacts.items += TrustedContact(1, "Ana", "+5511999999999")
        val location = GeoPoint(-23.55, -46.63, 900L)

        val result = useCase.execute(location)

        assertEquals(1_000L, result.alert.createdAtEpochMillis)
        assertEquals(AlertStatus.READY_TO_SHARE, result.alert.status)
        assertEquals(1, alerts.items.size)
        assertTrue(result.message.contains("-23.55,-46.63"))
    }
}

private class FakeContactRepository : ContactRepository {
    val items = mutableListOf<TrustedContact>()
    override fun list(): List<TrustedContact> = items.toList()
    override fun add(name: String, phone: String): TrustedContact =
        TrustedContact((items.size + 1).toLong(), name, phone).also(items::add)
    override fun update(id: Long, name: String, phone: String): TrustedContact =
        TrustedContact(id, name, phone)
    override fun delete(id: Long) { items.removeAll { it.id == id } }
}

private class FakeAlertRepository : AlertRepository {
    val items = mutableListOf<SafetyAlert>()
    override fun create(alert: SafetyAlert): SafetyAlert =
        alert.copy(id = (items.size + 1).toLong()).also(items::add)
    override fun latest(): SafetyAlert? = items.lastOrNull()
    override fun update(alert: SafetyAlert) {
        items[items.indexOfFirst { it.id == alert.id }] = alert
    }
}
