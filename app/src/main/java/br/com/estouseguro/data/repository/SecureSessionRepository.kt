package br.com.estouseguro.data.repository

import android.content.Context
import android.util.Base64
import br.com.estouseguro.domain.repository.SessionRepository
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SecureSessionRepository(context: Context) : SessionRepository {
    private val preferences = context.getSharedPreferences("local_session_v1", Context.MODE_PRIVATE)

    /**
     * Only reports a credential when the complete persisted record can actually be read.
     * Checking key presence alone can strand the user on the unlock screen after a
     * corrupt or interrupted preferences write.
     */
    override fun hasCredential(): Boolean {
        val salt = preferences.getString(KEY_SALT, null)?.decode() ?: return false
        val hash = preferences.getString(KEY_HASH, null)?.decode() ?: return false
        return salt.size == SALT_BYTES && hash.size == KEY_BITS / Byte.SIZE_BITS
    }

    override fun register(displayName: String, pin: CharArray) {
        var salt = ByteArray(0)
        var hash = ByteArray(0)
        try {
            require(displayName.trim().length in 2..80) { "Informe seu nome." }
            require(PinCredentialPolicy.isValid(pin)) {
                "O PIN deve ter entre 4 e 8 números."
            }
            salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            hash = derive(pin, salt)
            val persisted = preferences.edit()
                .putString(KEY_NAME, displayName.trim())
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt(KEY_ITERATIONS, ITERATIONS)
                .putInt(KEY_FORMAT_VERSION, CURRENT_FORMAT_VERSION)
                .commit()
            check(persisted) { "Nao foi possivel salvar o acesso com seguranca." }
        } finally {
            salt.fill(0)
            hash.fill(0)
            pin.fill('\u0000')
        }
    }

    override fun authenticate(pin: CharArray): Boolean {
        var salt = ByteArray(0)
        var expected = ByteArray(0)
        var actual = ByteArray(0)
        return try {
            if (!PinCredentialPolicy.isValid(pin)) return false
            salt = preferences.getString(KEY_SALT, null)?.decode() ?: return false
            expected = preferences.getString(KEY_HASH, null)?.decode() ?: return false
            if (salt.size != SALT_BYTES || expected.size != KEY_BITS / Byte.SIZE_BITS) return false

            val iterations = preferences.getInt(KEY_ITERATIONS, LEGACY_ITERATIONS)
            if (iterations !in MIN_SUPPORTED_ITERATIONS..MAX_SUPPORTED_ITERATIONS) return false

            actual = derive(pin, salt, iterations)
            val authenticated = MessageDigest.isEqual(expected, actual)
            if (authenticated && !preferences.contains(KEY_FORMAT_VERSION)) {
                // Preserve credentials made by the first APK and attach metadata only
                // after the legacy credential has successfully authenticated.
                preferences.edit()
                    .putInt(KEY_ITERATIONS, LEGACY_ITERATIONS)
                    .putInt(KEY_FORMAT_VERSION, CURRENT_FORMAT_VERSION)
                    .apply()
            }
            authenticated
        } finally {
            pin.fill('\u0000')
            salt.fill(0)
            expected.fill(0)
            actual.fill(0)
        }
    }

    override fun displayName(): String = preferences.getString(KEY_NAME, "") ?: ""

    override fun clearSessionSecrets() = Unit

    private fun derive(
        pin: CharArray,
        salt: ByteArray,
        iterations: Int = ITERATIONS,
    ): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun String.decode(): ByteArray? = runCatching {
        Base64.decode(this, Base64.NO_WRAP)
    }.getOrNull()

    companion object {
        private const val KEY_NAME = "display_name"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_ITERATIONS = "pin_iterations"
        private const val KEY_FORMAT_VERSION = "credential_format_version"
        private const val SALT_BYTES = 16
        private const val ITERATIONS = 210_000
        private const val LEGACY_ITERATIONS = 210_000
        private const val MIN_SUPPORTED_ITERATIONS = 100_000
        private const val MAX_SUPPORTED_ITERATIONS = 1_000_000
        private const val KEY_BITS = 256
        private const val CURRENT_FORMAT_VERSION = 1
    }
}

/** PINs are ASCII-only so OEM keyboards and Unicode digit rules behave identically. */
internal object PinCredentialPolicy {
    fun isValid(pin: CharArray): Boolean =
        pin.size in 4..8 && pin.all { it in '0'..'9' }
}
