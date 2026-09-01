package br.com.estouseguro.domain.model

enum class SmsEmergencyCategory {
    GENERAL, MEDICAL, SECURITY, DOMESTIC_VIOLENCE, CHILD_DANGER, ANXIETY,
}

/** Creates one GSM-7-compatible emergency SMS to reduce carrier fragmentation and throttling. */
object EmergencySmsFormatter {
    fun format(category: SmsEmergencyCategory, location: GeoPoint?): String {
        val opening = when (category) {
            SmsEmergencyCategory.GENERAL -> "SOS. Preciso de ajuda agora."
            SmsEmergencyCategory.MEDICAL -> "EMERGENCIA MEDICA. Preciso de ajuda."
            SmsEmergencyCategory.SECURITY -> "RISCO DE SEGURANCA. Preciso de ajuda."
            SmsEmergencyCategory.DOMESTIC_VIOLENCE -> "VIOLENCIA OU AMEACA. Preciso de ajuda agora."
            SmsEmergencyCategory.CHILD_DANGER -> "CRIANCA OU ADOLESCENTE EM RISCO. Ajuda urgente."
            SmsEmergencyCategory.ANXIETY -> "CRISE DE ANSIEDADE. Preciso de apoio agora."
        }
        val locationText = location?.let { " Local: ${it.mapsUrl()}" } ?: " Localizacao indisponivel."
        return "$opening$locationText Estou Seguro. Ligue para mim ou 190."
            .take(MAX_SINGLE_SMS_CHARS)
    }

    const val MAX_SINGLE_SMS_CHARS = 160
}
