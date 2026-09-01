package br.com.estouseguro.data.repository

import br.com.estouseguro.domain.model.BloodType
import br.com.estouseguro.domain.model.EmergencyMedicalProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Versioned, bounded binary format kept independent from Android for unit testing. */
internal object MedicalProfileCodec {
    private const val MAGIC = 0x45534D50 // ESMP
    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 16 * 1024

    fun encode(profile: EmergencyMedicalProfile): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeUTF(profile.preferredName)
            data.writeUTF(profile.birthDateIso)
            data.writeInt(profile.bloodType?.ordinal ?: -1)
            data.writeUTF(profile.allergies)
            data.writeUTF(profile.medications)
            data.writeUTF(profile.medicalConditions)
            data.writeUTF(profile.healthPlanProvider)
            data.writeUTF(profile.healthPlanMemberId)
            data.writeUTF(profile.emergencyNotes)
            data.writeLong(profile.updatedAtEpochMillis)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "Ficha medica excede o limite seguro." }
        }
    }

    fun decode(payload: ByteArray): EmergencyMedicalProfile {
        require(payload.size in 1..MAX_PAYLOAD_BYTES) { "Tamanho de ficha medica invalido." }
        return DataInputStream(ByteArrayInputStream(payload)).use { data ->
            require(data.readInt() == MAGIC) { "Formato de ficha medica invalido." }
            require(data.readInt() == VERSION) { "Versao de ficha medica nao suportada." }
            val preferredName = data.readUTF()
            val birthDate = data.readUTF()
            val bloodTypeOrdinal = data.readInt()
            val bloodType = when (bloodTypeOrdinal) {
                -1 -> null
                in BloodType.entries.indices -> BloodType.entries[bloodTypeOrdinal]
                else -> throw IllegalArgumentException("Tipo sanguineo invalido.")
            }
            val result = EmergencyMedicalProfile(
                preferredName = preferredName,
                birthDateIso = birthDate,
                bloodType = bloodType,
                allergies = data.readUTF(),
                medications = data.readUTF(),
                medicalConditions = data.readUTF(),
                healthPlanProvider = data.readUTF(),
                healthPlanMemberId = data.readUTF(),
                emergencyNotes = data.readUTF(),
                updatedAtEpochMillis = data.readLong(),
            )
            require(data.available() == 0) { "Ficha medica contem dados inesperados." }
            result
        }
    }
}
