package br.com.estouseguro.domain.usecase

import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.repository.ContactRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManageContactsTest {
    private val repository = RecordingContactRepository()
    private val useCase = ManageContacts(repository)

    @Test
    fun `normalizes phone before saving`() {
        val contact = useCase.add("  Maria  ", "+55 (11) 99999-9999")

        assertEquals("Maria", contact.name)
        assertEquals("+5511999999999", contact.phone)
    }

    @Test
    fun `rejects short phone`() {
        assertThrows(ContactValidationException::class.java) {
            useCase.add("Maria", "123")
        }
    }
}

private class RecordingContactRepository : ContactRepository {
    private val contacts = mutableListOf<TrustedContact>()
    override fun list() = contacts.toList()
    override fun add(name: String, phone: String) =
        TrustedContact(1, name, phone).also(contacts::add)
    override fun update(id: Long, name: String, phone: String) = TrustedContact(id, name, phone)
    override fun delete(id: Long) = Unit
}
