package br.com.estouseguro.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import br.com.estouseguro.domain.model.IdentityDocument
import br.com.estouseguro.domain.repository.DocumentSide
import br.com.estouseguro.domain.repository.DocumentVaultRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DocumentVaultStorageException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class KeystoreDocumentVaultRepository(context: Context) : DocumentVaultRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val imageDirectory = File(context.filesDir, "document_vault").apply { mkdirs() }

    override fun list(): List<IdentityDocument> {
        val encoded = preferences.getString(KEY_ENVELOPE, null) ?: return emptyList()
        return decrypt(Base64.decode(encoded, Base64.NO_WRAP), METADATA_AAD).let(DocumentVaultCodec::decode)
    }

    override fun save(document: IdentityDocument): IdentityDocument {
        val documents = list().toMutableList()
        val existingIndex = documents.indexOfFirst { it.id == document.id }
        if (existingIndex >= 0) documents[existingIndex] = document else documents += document
        persistMetadata(documents)
        return document
    }

    override fun delete(documentId: String) {
        persistMetadata(list().filterNot { it.id == documentId })
        DocumentSide.entries.forEach { imageFile(documentId, it).delete() }
    }

    override fun saveImage(documentId: String, side: DocumentSide, bytes: ByteArray, mimeType: String) {
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "A imagem deve ter no máximo 12 MB." }
        require(mimeType.startsWith("image/")) { "Selecione uma imagem válida." }
        val documents = list().toMutableList()
        val index = documents.indexOfFirst { it.id == documentId }
        require(index >= 0) { "Salve o documento antes de adicionar fotos." }
        val target = imageFile(documentId, side)
        val temporary = File(imageDirectory, "${target.name}.tmp")
        try {
            temporary.outputStream().use { it.write(encrypt(bytes, imageAad(documentId, side))) }
            if (target.exists() && !target.delete()) error("Não foi possível substituir a foto anterior.")
            check(temporary.renameTo(target)) { "Não foi possível concluir o armazenamento da foto." }
            val current = documents[index]
            documents[index] = if (side == DocumentSide.FRONT) current.copy(hasFrontImage = true)
            else current.copy(hasBackImage = true)
            persistMetadata(documents)
        } finally {
            temporary.delete()
        }
    }

    override fun loadImage(documentId: String, side: DocumentSide): ByteArray? {
        val source = imageFile(documentId, side)
        if (!source.exists()) return null
        require(source.length() <= MAX_ENCRYPTED_IMAGE_BYTES) { "Arquivo de imagem inválido." }
        return decrypt(source.readBytes(), imageAad(documentId, side))
    }

    private fun persistMetadata(documents: List<IdentityDocument>) {
        var plaintext = ByteArray(0)
        try {
            plaintext = DocumentVaultCodec.encode(documents)
            val encoded = Base64.encodeToString(encrypt(plaintext, METADATA_AAD), Base64.NO_WRAP)
            check(preferences.edit().putString(KEY_ENVELOPE, encoded).commit()) { "Falha ao salvar o cofre." }
        } catch (error: Exception) {
            throw DocumentVaultStorageException("Não foi possível proteger e salvar o cofre de documentos.", error)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(ENVELOPE_VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.writeInt(ciphertext.size)
                data.write(ciphertext)
            }
        }.toByteArray()
    }

    private fun decrypt(envelope: ByteArray, aad: ByteArray): ByteArray = try {
        val (iv, ciphertext) = DataInputStream(ByteArrayInputStream(envelope)).use { data ->
            require(data.readInt() == ENVELOPE_VERSION) { "Versão criptográfica inválida." }
            val ivSize = data.readInt()
            require(ivSize in 12..16) { "Vetor de inicialização inválido." }
            val iv = ByteArray(ivSize).also(data::readFully)
            val ciphertextSize = data.readInt()
            require(ciphertextSize >= 16 && ciphertextSize == data.available()) { "Conteúdo criptografado inválido." }
            iv to ByteArray(ciphertextSize).also(data::readFully)
        }
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
            doFinal(ciphertext)
        }
    } catch (error: Exception) {
        throw DocumentVaultStorageException("Não foi possível abrir o cofre protegido.", error)
    }

    private fun imageFile(documentId: String, side: DocumentSide): File {
        require(documentId.matches(Regex("[a-fA-F0-9-]{1,64}"))) { "Identificador inválido." }
        return File(imageDirectory, "${documentId}_${side.name.lowercase()}.vault")
    }

    private fun imageAad(documentId: String, side: DocumentSide) =
        "br.com.estouseguro:document-image:v1:$documentId:${side.name}".toByteArray(Charsets.UTF_8)

    private fun getOrCreateKey(): SecretKey = existingKeyOrNull() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        .apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()

    private fun existingKey(): SecretKey = existingKeyOrNull()
        ?: throw DocumentVaultStorageException("A chave do cofre não está mais disponível.")

    private fun existingKeyOrNull(): SecretKey? = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        .getKey(KEY_ALIAS, null) as? SecretKey

    companion object {
        private const val PREFERENCES_NAME = "encrypted_document_vault_v1"
        private const val KEY_ENVELOPE = "documents_envelope"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "estou_seguro_document_vault_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val ENVELOPE_VERSION = 1
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        private const val MAX_ENCRYPTED_IMAGE_BYTES = MAX_IMAGE_BYTES + 1024
        private val METADATA_AAD = "br.com.estouseguro:document-vault:v1".toByteArray(Charsets.UTF_8)
    }
}
