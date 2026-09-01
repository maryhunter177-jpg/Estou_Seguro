package br.com.estouseguro.domain.usecase

import br.com.estouseguro.domain.model.BloodType
import br.com.estouseguro.domain.model.EmergencyMedicalProfile
import br.com.estouseguro.domain.repository.MedicalProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ManageMedicalProfileTest {
    private val repository = InMemoryMedicalProfileRepository()
    private val subject = ManageMedicalProfile(repository, now = { 123_456L })

    @Test
    fun `save normalizes and persists optional emergency profile`() {
        val saved = subject.save(
            EmergencyMedicalProfile(
                preferredName = "  Maria   Silva  ",
                bloodType = BloodType.O_POSITIVE,
                allergies = "  Dipirona  ",
                updatedAtEpochMillis = 0,
            ),
        )

        assertEquals("Maria Silva", saved.preferredName)
        assertEquals("Dipirona", saved.allergies)
        assertEquals(123_456L, saved.updatedAtEpochMillis)
        assertEquals(saved, subject.load())
    }

    @Test
    fun `rejects member id without health plan provider`() {
        val error = assertThrows(MedicalProfileValidationException::class.java) {
            subject.save(profile(healthPlanMemberId = "ABC-123"))
        }
        assertEquals(MedicalProfileField.HEALTH_PLAN_PROVIDER, error.field)
    }

    @Test
    fun `rejects future birth date`() {
        val error = assertThrows(MedicalProfileValidationException::class.java) {
            subject.save(profile(birthDateIso = "2999-01-01"))
        }
        assertEquals(MedicalProfileField.BIRTH_DATE, error.field)
    }

    @Test
    fun `rejects an empty profile to avoid collecting meaningless records`() {
        val error = assertThrows(MedicalProfileValidationException::class.java) {
            subject.save(EmergencyMedicalProfile(updatedAtEpochMillis = 0))
        }
        assertEquals(MedicalProfileField.PROFILE, error.field)
    }

    @Test
    fun `delete removes sensitive record`() {
        subject.save(profile())
        subject.delete()
        assertNull(subject.load())
    }

    private fun profile(
        birthDateIso: String = "",
        healthPlanMemberId: String = "",
    ) = EmergencyMedicalProfile(
        allergies = "Penicilina",
        birthDateIso = birthDateIso,
        healthPlanMemberId = healthPlanMemberId,
        updatedAtEpochMillis = 0,
    )
}

private class InMemoryMedicalProfileRepository : MedicalProfileRepository {
    private var profile: EmergencyMedicalProfile? = null
    override fun load(): EmergencyMedicalProfile? = profile
    override fun save(profile: EmergencyMedicalProfile) { this.profile = profile }
    override fun delete() { profile = null }
}
