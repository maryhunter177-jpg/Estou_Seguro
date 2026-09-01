package br.com.estouseguro.domain.usecase

import br.com.estouseguro.domain.model.BrazilianCpf
import br.com.estouseguro.domain.model.DocumentType
import br.com.estouseguro.domain.model.IdentityDocument
import br.com.estouseguro.domain.repository.DocumentSide
import br.com.estouseguro.domain.repository.DocumentVaultRepository
import java.time.LocalDate
import java.util.UUID

class ManageDocumentVault(
    private val repository: DocumentVaultRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun list(): List<IdentityDocument> = repository.list().sortedBy { it.displayType }

    fun save(input: IdentityDocument): IdentityDocument {
        val normalized = input.copy(
            id = input.id.ifBlank { UUID.randomUUID().toString() },
            customType = input.customType.trim().replace(Regex("[ \\t]+"), " "),
            number = input.number.trim(),
            issuer = input.issuer.trim().replace(Regex("[ \\t]+"), " "),
            expiryDateIso = input.expiryDateIso.trim(),
            notes = input.notes.trim(),
            updatedAtEpochMillis = now(),
        )
        require(normalized.type != DocumentType.OTHER || normalized.customType.isNotBlank()) {
            "Informe o tipo do documento."
        }
        require(normalized.number.isNotBlank()) { "Informe o número do documento." }
        require(normalized.number.length <= 80 && normalized.issuer.length <= 100 && normalized.notes.length <= 500) {
            "Um dos campos excede o limite permitido."
        }
        if (normalized.type == DocumentType.CPF) {
            require(BrazilianCpf.isValid(normalized.number)) { "CPF inválido. Confira os 11 dígitos." }
        }
        if (normalized.expiryDateIso.isNotBlank()) LocalDate.parse(normalized.expiryDateIso)
        return repository.save(normalized)
    }

    fun delete(documentId: String) = repository.delete(documentId)
    fun saveImage(documentId: String, side: DocumentSide, bytes: ByteArray, mimeType: String) =
        repository.saveImage(documentId, side, bytes, mimeType)
    fun loadImage(documentId: String, side: DocumentSide): ByteArray? = repository.loadImage(documentId, side)
}
