package br.com.estouseguro.data.repository

import br.com.estouseguro.domain.model.BloodType
import br.com.estouseguro.domain.model.EmergencyMedicalProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MedicalProfileCodecTest {
    @Test
    fun `round trips every field`() {
        val expected = EmergencyMedicalProfile(
            preferredName = "Ana",
            birthDateIso = "1990-05-20",
            bloodType = BloodType.AB_NEGATIVE,
            allergies = "Latex",
            medications = "Medicamento 10 mg",
            medicalConditions = "Diabetes",
            healthPlanProvider = "Operadora",
            healthPlanMemberId = "M-123",
            emergencyNotes = "Contato por Libras",
            updatedAtEpochMillis = 99L,
        )

        assertEquals(expected, MedicalProfileCodec.decode(MedicalProfileCodec.encode(expected)))
    }

    @Test
    fun `encoding is deterministic for encryption input`() {
        val profile = EmergencyMedicalProfile(allergies = "Latex", updatedAtEpochMillis = 1)
        assertArrayEquals(MedicalProfileCodec.encode(profile), MedicalProfileCodec.encode(profile))
    }

    @Test
    fun `rejects truncated and trailing payloads`() {
        val encoded = MedicalProfileCodec.encode(
            EmergencyMedicalProfile(allergies = "Latex", updatedAtEpochMillis = 1),
        )
        assertThrows(Exception::class.java) { MedicalProfileCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            MedicalProfileCodec.decode(encoded + byteArrayOf(1))
        }
    }
}
