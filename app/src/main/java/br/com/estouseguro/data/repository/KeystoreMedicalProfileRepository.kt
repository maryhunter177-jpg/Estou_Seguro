package br.com.estouseguro.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import br.com.estouseguro.domain.model.EmergencyMedicalProfile
import br.com.estouseguro.domain.repository.MedicalProfileRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MedicalProfileStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Persists only AES-GCM ciphertext. The non-exportable key remains in Android Keystore.
 * Authentication failure is surfaced; sensitive data is never silently downgraded to plaintext.
 */
class KeystoreMedicalProfileRepository(context: Context) : MedicalProfileRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): EmergencyMedicalProfile? {
        val encoded = preferences.getString(KEY_ENVELOPE, null) ?: return null
        var plaintext = ByteArray(0)
        return try {
            val envelope = decodeEnvelope(Base64.decode(encoded, Base64.NO_WRAP))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(TAG_BITS, envelope.iv))
            cipher.updateAAD(ASSOCIATED_DATA)
            plaintext = cipher.doFinal(envelope.ciphertext)
            MedicalProfileCodec.decode(plaintext)
        } catch (error: Exception) {
            throw MedicalProfileStorageException(
                "Nao foi possivel abrir a ficha medica protegida. Os dados podem estar corrompidos ou a chave foi invalidada.",
                error,
            )
        } finally {
            plaintext.fill(0)
        }
    }

    override fun save(profile: EmergencyMedicalProfile) {
        var plaintext = ByteArray(0)
        try {
            plaintext = MedicalProfileCodec.encode(profile)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(ASSOCIATED_DATA)
            val envelope = encodeEnvelope(cipher.iv, cipher.doFinal(plaintext))
            val persisted = preferences.edit()
                .putString(KEY_ENVELOPE, Base64.encodeToString(envelope, Base64.NO_WRAP))
                .commit()
            check(persisted) { "Falha ao persistir a ficha medica." }
        } catch (error: Exception) {
            if (error is MedicalProfileStorageException) throw error
            throw MedicalProfileStorageException("Nao foi possivel proteger e salvar a ficha medica.", error)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun delete() {
        if (!preferences.edit().remove(KEY_ENVELOPE).commit()) {
            throw MedicalProfileStorageException("Nao foi possivel excluir a ficha medica.")
        }
    }

    private fun getOrCreateKey(): SecretKey {
        existingKeyOrNull()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey = existingKeyOrNull()
        ?: throw MedicalProfileStorageException("A chave da ficha medica nao esta mais disponivel.")

    private fun existingKeyOrNull(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private data class Envelope(val iv: ByteArray, val ciphertext: ByteArray)

    private fun encodeEnvelope(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size in 12..16 && ciphertext.isNotEmpty()) { "Envelope criptografico invalido." }
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeInt(ENVELOPE_VERSION)
                it.writeInt(iv.size)
                it.write(iv)
                it.writeInt(ciphertext.size)
                it.write(ciphertext)
            }
        }.toByteArray()
    }

    private fun decodeEnvelope(bytes: ByteArray): Envelope {
        require(bytes.size in 1..MAX_ENVELOPE_BYTES) { "Envelope criptografico invalido." }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == ENVELOPE_VERSION) { "Versao criptografica nao suportada." }
            val ivSize = input.readInt()
            require(ivSize in 12..16) { "Vetor de inicializacao invalido." }
            val iv = ByteArray(ivSize).also(input::readFully)
            val ciphertextSize = input.readInt()
            require(ciphertextSize in 16..MAX_ENVELOPE_BYTES && ciphertextSize == input.available()) {
                "Conteudo criptografado invalido."
            }
            Envelope(iv, ByteArray(ciphertextSize).also(input::readFully))
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "encrypted_medical_profile_v1"
        private const val KEY_ENVELOPE = "profile_envelope"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "estou_seguro_medical_profile_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val ENVELOPE_VERSION = 1
        private const val MAX_ENVELOPE_BYTES = 20 * 1024
        private val ASSOCIATED_DATA = "br.com.estouseguro:medical-profile:v1".toByteArray(Charsets.UTF_8)
    }
}
