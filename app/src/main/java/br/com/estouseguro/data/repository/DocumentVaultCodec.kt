package br.com.estouseguro.data.repository

import br.com.estouseguro.domain.model.DocumentType
import br.com.estouseguro.domain.model.IdentityDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object DocumentVaultCodec {
    private const val MAGIC = 0x45534456 // ESDV
    private const val VERSION = 1
    private const val MAX_DOCUMENTS = 100
    private const val MAX_BYTES = 256 * 1024

    fun encode(documents: List<IdentityDocument>): ByteArray {
        require(documents.size <= MAX_DOCUMENTS) { "Limite de documentos excedido." }
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(documents.size)
                documents.forEach { document ->
                    data.writeUTF(document.id)
                    data.writeInt(document.type.ordinal)
                    data.writeUTF(document.customType)
                    data.writeUTF(document.number)
                    data.writeUTF(document.issuer)
                    data.writeUTF(document.expiryDateIso)
                    data.writeUTF(document.notes)
                    data.writeBoolean(document.hasFrontImage)
                    data.writeBoolean(document.hasBackImage)
                    data.writeLong(document.updatedAtEpochMillis)
                }
            }
        }.toByteArray().also { require(it.size <= MAX_BYTES) { "Cofre excede o limite seguro." } }
    }

    fun decode(payload: ByteArray): List<IdentityDocument> {
        require(payload.size in 1..MAX_BYTES) { "Tamanho de cofre inválido." }
        return DataInputStream(ByteArrayInputStream(payload)).use { data ->
            require(data.readInt() == MAGIC && data.readInt() == VERSION) { "Formato de cofre não suportado." }
            val count = data.readInt()
            require(count in 0..MAX_DOCUMENTS) { "Quantidade de documentos inválida." }
            List(count) {
                val id = data.readUTF()
                val typeOrdinal = data.readInt()
                require(typeOrdinal in DocumentType.entries.indices) { "Tipo de documento inválido." }
                IdentityDocument(
                    id = id,
                    type = DocumentType.entries[typeOrdinal],
                    customType = data.readUTF(),
                    number = data.readUTF(),
                    issuer = data.readUTF(),
                    expiryDateIso = data.readUTF(),
                    notes = data.readUTF(),
                    hasFrontImage = data.readBoolean(),
                    hasBackImage = data.readBoolean(),
                    updatedAtEpochMillis = data.readLong(),
                )
            }.also { require(data.available() == 0) { "Cofre contém dados inesperados." } }
        }
    }
}
