package br.com.estouseguro.domain.model

enum class DocumentType(val label: String) {
    CPF("CPF"), CIN_RG("CIN / RG"), CNH("Carteira de motorista (CNH)"),
    CTPS("Carteira de trabalho (CTPS)"), SUS("Cartão Nacional de Saúde (SUS)"),
    PASSPORT("Passaporte"), VOTER_ID("Título de eleitor"),
    BIRTH_CERTIFICATE("Certidão de nascimento"), MARRIAGE_CERTIFICATE("Certidão de casamento"),
    CRNM("Registro migratório (CRNM/RNM)"), RESERVIST("Certificado de reservista"),
    PROFESSIONAL_ID("Carteira profissional"), HEALTH_PLAN("Carteirinha do plano de saúde"),
    OTHER("Outro documento");
}

data class IdentityDocument(
    val id: String,
    val type: DocumentType,
    val customType: String = "",
    val number: String = "",
    val issuer: String = "",
    val expiryDateIso: String = "",
    val notes: String = "",
    val hasFrontImage: Boolean = false,
    val hasBackImage: Boolean = false,
    val updatedAtEpochMillis: Long = 0,
) {
    val displayType: String get() = if (type == DocumentType.OTHER && customType.isNotBlank()) customType else type.label
}
