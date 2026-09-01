package br.com.estouseguro.domain.repository

import br.com.estouseguro.domain.model.EmergencyMedicalProfile

interface MedicalProfileRepository {
    fun load(): EmergencyMedicalProfile?
    fun save(profile: EmergencyMedicalProfile)
    fun delete()
}
