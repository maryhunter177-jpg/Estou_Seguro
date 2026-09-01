package br.com.estouseguro.domain.repository

import br.com.estouseguro.domain.model.IdentityDocument

interface DocumentVaultRepository {
    fun list(): List<IdentityDocument>
    fun save(document: IdentityDocument): IdentityDocument
    fun delete(documentId: String)
    fun saveImage(documentId: String, side: DocumentSide, bytes: ByteArray, mimeType: String)
    fun loadImage(documentId: String, side: DocumentSide): ByteArray?
}

enum class DocumentSide { FRONT, BACK }
