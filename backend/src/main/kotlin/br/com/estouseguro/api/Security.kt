package br.com.estouseguro.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SecureTokens {
    private val random = SecureRandom()

    fun generate(byteCount: Int = 32): String = ByteArray(byteCount).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun hash(token: String, pepper: String): ByteArray = hmacSha256(pepper.toByteArray(), token.toByteArray())

    fun randomToken(byteCount: Int = 32): String = generate(byteCount)

    fun hmac(pepper: String, value: String): ByteArray = hash(value, pepper)

    fun hmacSha256(secret: ByteArray, payload: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(payload)
    }

    fun verifyHexHmac(header: String?, secret: String, payload: ByteArray): Boolean {
        val supplied = header?.removePrefix("sha256=")?.takeIf { it.length == 64 } ?: return false
        val expected = hmacSha256(secret.toByteArray(), payload).toHex()
        return MessageDigest.isEqual(expected.toByteArray(), supplied.lowercase().toByteArray())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

object BrazilianPhones {
    fun normalize(raw: String): String? {
        val explicitInternational = raw.trim().startsWith("+")
        val digits = raw.filter(Char::isDigit)
        val national = when {
            explicitInternational && digits.startsWith("55") -> digits.removePrefix("55")
            !explicitInternational && digits.startsWith("55") && digits.length in 12..13 -> digits.removePrefix("55")
            !explicitInternational -> digits
            else -> return null
        }
        if (national.length != 11) return null
        val ddd = national.take(2).toIntOrNull() ?: return null
        if (ddd !in 11..99 || national[2] != '9') return null
        return "+55$national"
    }

    fun masked(e164: String): String = "(**) *****-${e164.takeLast(4)}"

    fun mask(e164: String): String = masked(e164)
}
