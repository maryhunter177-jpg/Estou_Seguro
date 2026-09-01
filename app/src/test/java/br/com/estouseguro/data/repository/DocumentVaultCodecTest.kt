package br.com.estouseguro.data.repository

import br.com.estouseguro.domain.model.DocumentType
import br.com.estouseguro.domain.model.IdentityDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentVaultCodecTest {
    @Test fun `preserva documentos e indicadores de foto`() {
        val expected = listOf(IdentityDocument(
            id = "8f531f7d-828b-4c9b-b9dc-9a3f048ddcf0",
            type = DocumentType.CNH,
            number = "12345678900",
            issuer = "DETRAN/MG",
            expiryDateIso = "2030-12-01",
            notes = "Categoria B",
            hasFrontImage = true,
            updatedAtEpochMillis = 10,
        ))
        assertEquals(expected, DocumentVaultCodec.decode(DocumentVaultCodec.encode(expected)))
    }

    @Test fun `rejeita payload adulterado`() {
        assertThrows(Exception::class.java) { DocumentVaultCodec.decode(byteArrayOf(1, 2, 3)) }
    }
}
