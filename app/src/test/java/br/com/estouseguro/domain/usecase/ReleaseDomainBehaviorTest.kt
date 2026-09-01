package br.com.estouseguro.domain.usecase

import br.com.estouseguro.domain.model.SafetyAlert
import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.repository.AlertRepository
import br.com.estouseguro.domain.repository.ContactRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseDomainBehaviorTest {
    @Test
    fun `update applies the same normalization as contact creation`() {
        val contacts = ReleaseContactRepository().apply {
            items += TrustedContact(7, "Nome antigo", "+5511000000000")
        }

        val updated = ManageContacts(contacts).update(7, "  Ana Maria  ", "+55 (21) 98888-7777")

        assertEquals("Ana Maria", updated.name)
        assertEquals("+5521988887777", updated.phone)
    }

    @Test
    fun `update rejects an invalid persisted identifier`() {
        assertThrows(IllegalArgumentException::class.java) {
            ManageContacts(ReleaseContactRepository()).update(0, "Ana", "+5521988887777")
        }
    }

    @Test
    fun `alert without location remains shareable without a maps link`() {
        val contacts = ReleaseContactRepository().apply {
            items += TrustedContact(1, "Ana", "+5521988887777")
        }
        val alerts = ReleaseAlertRepository()

        val prepared = PrepareEmergencyAlert(contacts, alerts) { 42L }.execute(null)

        assertEquals(42L, prepared.alert.createdAtEpochMillis)
        assertEquals(1, prepared.recipients.size)
        assertFalse(prepared.message.contains("maps.google.com"))
        assertEquals(prepared.alert, alerts.latest())
    }
}

private class ReleaseContactRepository : ContactRepository {
    val items = mutableListOf<TrustedContact>()

    override fun list(): List<TrustedContact> = items.toList()

    override fun add(name: String, phone: String): TrustedContact =
        TrustedContact((items.size + 1).toLong(), name, phone).also(items::add)

    override fun update(id: Long, name: String, phone: String): TrustedContact =
        TrustedContact(id, name, phone).also { updated ->
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0) items[index] = updated
        }

    override fun delete(id: Long) {
        items.removeAll { it.id == id }
    }
}

private class ReleaseAlertRepository : AlertRepository {
    private val items = mutableListOf<SafetyAlert>()

    override fun create(alert: SafetyAlert): SafetyAlert =
        alert.copy(id = (items.size + 1).toLong()).also(items::add)

    override fun latest(): SafetyAlert? = items.lastOrNull()

    override fun update(alert: SafetyAlert) {
        val index = items.indexOfFirst { it.id == alert.id }
        check(index >= 0)
        items[index] = alert
    }
}
