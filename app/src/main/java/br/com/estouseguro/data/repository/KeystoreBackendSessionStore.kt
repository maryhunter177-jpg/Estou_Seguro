package br.com.estouseguro.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RemoteContactBinding(
    val localId: Long,
    val localPhone: String,
    val remoteId: String,
    val consentStatus: String,
    val consentUrl: String?,
)

data class BackendSession(
    val deviceId: String,
    val accessToken: String,
    val contacts: List<RemoteContactBinding> = emptyList(),
)

interface BackendSessionStore {
    fun load(): BackendSession?
    fun save(session: BackendSession)
    fun clear()
}

/** Keeps the backend bearer token and consent links encrypted by a non-exportable Keystore key. */
class KeystoreBackendSessionStore(context: Context) : BackendSessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): BackendSession? {
        val encoded = preferences.getString(KEY_ENVELOPE, null) ?: return null
        return runCatching {
            val envelope = BackendSessionCodec.decodeEnvelope(Base64.decode(encoded, Base64.NO_WRAP))
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, envelope.iv))
                updateAAD(AAD)
            }
            BackendSessionCodec.decode(cipher.doFinal(envelope.ciphertext))
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(session: BackendSession) {
        val plaintext = BackendSessionCodec.encode(session)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                updateAAD(AAD)
            }
            val envelope = BackendSessionCodec.encodeEnvelope(cipher.iv, cipher.doFinal(plaintext))
            check(preferences.edit().putString(KEY_ENVELOPE, Base64.encodeToString(envelope, Base64.NO_WRAP)).commit()) {
                "Nao foi possivel salvar a sessao segura do servidor."
            }
        } finally {
            plaintext.fill(0)
        }
    }

    override fun clear() {
        preferences.edit().remove(KEY_ENVELOPE).commit()
    }

    private fun getOrCreateKey(): SecretKey = existingKeyOrNull() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        .apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()

    private fun existingKey(): SecretKey = existingKeyOrNull()
        ?: error("A chave da sessao do servidor nao esta disponivel.")

    private fun existingKeyOrNull(): SecretKey? = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        .getKey(KEY_ALIAS, null) as? SecretKey

    companion object {
        private const val PREFERENCES = "encrypted_backend_session_v1"
        private const val KEY_ENVELOPE = "session_envelope"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "estou_seguro_backend_session_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private val AAD = "br.com.estouseguro:backend-session:v1".toByteArray()
    }
}

internal object BackendSessionCodec {
    data class Envelope(val iv: ByteArray, val ciphertext: ByteArray)

    fun encode(session: BackendSession): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeUTF(session.deviceId)
            out.writeUTF(session.accessToken)
            out.writeInt(session.contacts.size)
            session.contacts.forEach {
                out.writeLong(it.localId)
                out.writeUTF(it.localPhone)
                out.writeUTF(it.remoteId)
                out.writeUTF(it.consentStatus)
                out.writeBoolean(it.consentUrl != null)
                it.consentUrl?.let(out::writeUTF)
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): BackendSession {
        require(bytes.size in 1..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION)
            val deviceId = input.readUTF().also { require(it.matches(UUID_PATTERN)) }
            val token = input.readUTF().also { require(it.matches(TOKEN_PATTERN)) }
            val count = input.readInt().also { require(it in 0..MAX_CONTACTS) }
            val contacts = List(count) {
                RemoteContactBinding(
                    localId = input.readLong().also { require(it > 0) },
                    localPhone = input.readUTF().also { require(it.length in 10..16) },
                    remoteId = input.readUTF().also { require(it.matches(UUID_PATTERN)) },
                    consentStatus = input.readUTF().also { require(it in VALID_STATUSES) },
                    consentUrl = if (input.readBoolean()) input.readUTF().also { require(it.startsWith("https://")) } else null,
                )
            }
            require(input.available() == 0)
            BackendSession(deviceId, token, contacts)
        }
    }

    fun encodeEnvelope(iv: ByteArray, ciphertext: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION); out.writeInt(iv.size); out.write(iv)
            out.writeInt(ciphertext.size); out.write(ciphertext)
        }
    }.toByteArray()

    fun decodeEnvelope(bytes: ByteArray): Envelope = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(bytes.size in 1..MAX_BYTES && input.readInt() == VERSION)
        val iv = ByteArray(input.readInt().also { require(it in 12..16) }).also(input::readFully)
        val cipher = ByteArray(input.readInt().also { require(it in 16..MAX_BYTES && it == input.available()) }).also(input::readFully)
        Envelope(iv, cipher)
    }

    private const val VERSION = 1
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_CONTACTS = 50
    private val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")
    private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{32,128}")
    private val VALID_STATUSES = setOf("PENDING", "GRANTED", "REVOKED")
}
