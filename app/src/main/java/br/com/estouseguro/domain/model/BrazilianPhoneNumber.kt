package br.com.estouseguro.domain.model

/** Strict normalization for contacts reached through a Brazilian SIM. */
object BrazilianPhoneNumber {
    fun normalizeForSms(raw: String): String? {
        val value = raw.trim()
        val digits = value.filter(Char::isDigit)
        if (value.startsWith("+") && !digits.startsWith("55")) {
            return if (digits.length in 8..15) "+$digits" else null
        }

        val national = when {
            digits.startsWith("55") && digits.length in 12..13 -> digits.drop(2)
            !value.startsWith("+") && digits.length in 10..11 -> digits
            else -> return null
        }
        val ddd = national.take(2).toIntOrNull() ?: return null
        if (ddd !in VALID_DDDS) return null

        val subscriber = national.drop(2)
        val validLandline = subscriber.length == 8 && subscriber.firstOrNull() in '2'..'5'
        val validMobile = subscriber.length == 9 && subscriber.firstOrNull() == '9'
        return if (validLandline || validMobile) "+55$national" else null
    }

    private val VALID_DDDS = setOf(
        11, 12, 13, 14, 15, 16, 17, 18, 19,
        21, 22, 24, 27, 28,
        31, 32, 33, 34, 35, 37, 38,
        41, 42, 43, 44, 45, 46, 47, 48, 49,
        51, 53, 54, 55,
        61, 62, 63, 64, 65, 66, 67, 68, 69,
        71, 73, 74, 75, 77, 79,
        81, 82, 83, 84, 85, 86, 87, 88, 89,
        91, 92, 93, 94, 95, 96, 97, 98, 99,
    )
}
