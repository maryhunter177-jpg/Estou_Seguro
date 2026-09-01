package br.com.estouseguro.domain.usecase

import br.com.estouseguro.domain.model.DocumentType
import br.com.estouseguro.domain.model.IdentityDocument
import br.com.estouseguro.domain.repository.DocumentSide
import br.com.estouseguro.domain.repository.DocumentVaultRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManageDocumentVaultTest {
    private val repository = MemoryDocumentVaultRepository()
    private val subject = ManageDocumentVault(repository) { 99 }

    @Test fun `normaliza e salva CPF valido`() {
        val saved = subject.save(IdentityDocument("", DocumentType.CPF, number = "529.982.247-25"))
        assertEquals(99, saved.updatedAtEpochMillis)
        assertEquals(1, repository.documents.size)
    }

    @Test fun `rejeita CPF invalido`() {
        assertThrows(IllegalArgumentException::class.java) {
            subject.save(IdentityDocument("", DocumentType.CPF, number = "11111111111"))
        }
    }
}

private class MemoryDocumentVaultRepository : DocumentVaultRepository {
    val documents = mutableListOf<IdentityDocument>()
    override fun list() = documents.toList()
    override fun save(document: IdentityDocument): IdentityDocument = document.also { documents += it }
    override fun delete(documentId: String) { documents.removeAll { it.id == documentId } }
    override fun saveImage(documentId: String, side: DocumentSide, bytes: ByteArray, mimeType: String) = Unit
    override fun loadImage(documentId: String, side: DocumentSide): ByteArray? = null
}
