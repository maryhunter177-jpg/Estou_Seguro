package br.com.estouseguro.api

import java.security.SecureRandom
import java.time.Instant

/** Human-readable one-time bootstrap codes. Ambiguous characters are intentionally excluded. */
object SandboxActivationCodes {
    private const val SYMBOL_COUNT = 12
    private const val TTL_SECONDS = 15L * 60L
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(): String = display(buildString(SYMBOL_COUNT) {
        repeat(SYMBOL_COUNT) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    })

    /** Returns the canonical unformatted value used for hashing, never the display form. */
    fun normalize(raw: String): String? {
        val compact = raw.trim().uppercase().filterNot { it == '-' || it == ' ' }
        if (compact.length != SYMBOL_COUNT || compact.any { it !in ALPHABET }) return null
        return compact
    }

    fun display(normalized: String): String {
        require(normalized.length == SYMBOL_COUNT && normalized.all { it in ALPHABET })
        return normalized.chunked(4).joinToString("-")
    }

    fun expiresAt(createdAt: Instant): Instant = createdAt.plusSeconds(TTL_SECONDS)

    fun isUsable(expiresAt: Instant, consumedAt: Instant?, now: Instant): Boolean =
        consumedAt == null && now.isBefore(expiresAt)
}
