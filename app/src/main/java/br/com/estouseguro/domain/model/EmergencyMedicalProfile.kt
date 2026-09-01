package br.com.estouseguro.domain.model

/**
 * Optional, minimum emergency data selected by the user.
 *
 * National identifiers (including CPF), addresses and full medical records are
 * deliberately not part of this model. They are not necessary for first response.
 */
data class EmergencyMedicalProfile(
    val preferredName: String = "",
    val birthDateIso: String = "",
    val bloodType: BloodType? = null,
    val allergies: String = "",
    val medications: String = "",
    val medicalConditions: String = "",
    val healthPlanProvider: String = "",
    val healthPlanMemberId: String = "",
    val emergencyNotes: String = "",
    val updatedAtEpochMillis: Long,
) {
    fun hasMedicalContent(): Boolean =
        bloodType != null || allergies.isNotBlank() || medications.isNotBlank() ||
            medicalConditions.isNotBlank() || healthPlanProvider.isNotBlank() ||
            healthPlanMemberId.isNotBlank() || emergencyNotes.isNotBlank()
}

enum class BloodType {
    A_POSITIVE, A_NEGATIVE, B_POSITIVE, B_NEGATIVE,
    AB_POSITIVE, AB_NEGATIVE, O_POSITIVE, O_NEGATIVE,
}
