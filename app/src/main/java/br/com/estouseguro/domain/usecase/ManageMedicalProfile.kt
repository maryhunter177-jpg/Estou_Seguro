package br.com.estouseguro.domain.usecase

import br.com.estouseguro.domain.model.EmergencyMedicalProfile
import br.com.estouseguro.domain.repository.MedicalProfileRepository
import java.time.LocalDate
import java.time.format.DateTimeParseException

class MedicalProfileValidationException(
    val field: MedicalProfileField,
    message: String,
) : IllegalArgumentException(message)

enum class MedicalProfileField {
    PREFERRED_NAME, BIRTH_DATE, ALLERGIES, MEDICATIONS, MEDICAL_CONDITIONS,
    HEALTH_PLAN_PROVIDER, HEALTH_PLAN_MEMBER_ID, EMERGENCY_NOTES, PROFILE,
}

class ManageMedicalProfile(
    private val repository: MedicalProfileRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun load(): EmergencyMedicalProfile? = repository.load()

    fun save(input: EmergencyMedicalProfile): EmergencyMedicalProfile {
        val normalized = input.copy(
            preferredName = input.preferredName.trimAndCollapse(),
            birthDateIso = input.birthDateIso.trim(),
            allergies = input.allergies.trimAndCollapseLines(),
            medications = input.medications.trimAndCollapseLines(),
            medicalConditions = input.medicalConditions.trimAndCollapseLines(),
            healthPlanProvider = input.healthPlanProvider.trimAndCollapse(),
            healthPlanMemberId = input.healthPlanMemberId.trim(),
            emergencyNotes = input.emergencyNotes.trimAndCollapseLines(),
            updatedAtEpochMillis = now(),
        )
        validate(normalized)
        repository.save(normalized)
        return normalized
    }

    fun delete() = repository.delete()

    fun validate(profile: EmergencyMedicalProfile) {
        requireLength(profile.preferredName, 80, MedicalProfileField.PREFERRED_NAME)
        requireLength(profile.allergies, 500, MedicalProfileField.ALLERGIES)
        requireLength(profile.medications, 500, MedicalProfileField.MEDICATIONS)
        requireLength(profile.medicalConditions, 500, MedicalProfileField.MEDICAL_CONDITIONS)
        requireLength(profile.healthPlanProvider, 100, MedicalProfileField.HEALTH_PLAN_PROVIDER)
        requireLength(profile.healthPlanMemberId, 80, MedicalProfileField.HEALTH_PLAN_MEMBER_ID)
        requireLength(profile.emergencyNotes, 500, MedicalProfileField.EMERGENCY_NOTES)

        if (profile.birthDateIso.isNotEmpty()) {
            val birthDate = try {
                LocalDate.parse(profile.birthDateIso)
            } catch (_: DateTimeParseException) {
                invalid(MedicalProfileField.BIRTH_DATE, "Use a data no formato AAAA-MM-DD.")
            }
            if (birthDate.isAfter(LocalDate.now()) || birthDate.isBefore(LocalDate.now().minusYears(130))) {
                invalid(MedicalProfileField.BIRTH_DATE, "Informe uma data de nascimento valida.")
            }
        }
        if (profile.healthPlanMemberId.isNotEmpty() && profile.healthPlanProvider.isEmpty()) {
            invalid(MedicalProfileField.HEALTH_PLAN_PROVIDER, "Informe a operadora do plano.")
        }
        if (!profile.hasMedicalContent() && profile.preferredName.isEmpty() && profile.birthDateIso.isEmpty()) {
            invalid(MedicalProfileField.PROFILE, "Informe pelo menos um dado util para emergencia.")
        }
        profile.allTextFields().forEach { (value, field) ->
            if (value.any { it.isISOControl() && it != '\n' }) {
                invalid(field, "O campo contem caracteres nao permitidos.")
            }
        }
    }

    private fun requireLength(value: String, maximum: Int, field: MedicalProfileField) {
        if (value.length > maximum) invalid(field, "Limite de $maximum caracteres excedido.")
    }

    private fun invalid(field: MedicalProfileField, message: String): Nothing =
        throw MedicalProfileValidationException(field, message)

    private fun String.trimAndCollapse(): String = trim().replace(Regex("[ \\t]+"), " ")
    private fun String.trimAndCollapseLines(): String = trim().replace(Regex("[ \\t]+"), " ")

    private fun EmergencyMedicalProfile.allTextFields() = listOf(
        preferredName to MedicalProfileField.PREFERRED_NAME,
        allergies to MedicalProfileField.ALLERGIES,
        medications to MedicalProfileField.MEDICATIONS,
        medicalConditions to MedicalProfileField.MEDICAL_CONDITIONS,
        healthPlanProvider to MedicalProfileField.HEALTH_PLAN_PROVIDER,
        healthPlanMemberId to MedicalProfileField.HEALTH_PLAN_MEMBER_ID,
        emergencyNotes to MedicalProfileField.EMERGENCY_NOTES,
    )
}
