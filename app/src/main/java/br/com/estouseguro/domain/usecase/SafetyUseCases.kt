package br.com.estouseguro.domain.usecase

import br.com.estouseguro.domain.model.AlertStatus
import br.com.estouseguro.domain.model.DashboardSnapshot
import br.com.estouseguro.domain.model.GeoPoint
import br.com.estouseguro.domain.model.SafetyAlert
import br.com.estouseguro.domain.model.SmsDeliveryAttempt
import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.repository.AlertRepository
import br.com.estouseguro.domain.repository.ContactRepository
import br.com.estouseguro.domain.repository.SmsDeliveryRepository

class ContactValidationException(message: String) : IllegalArgumentException(message)

class ManageContacts(private val repository: ContactRepository) {
    fun add(name: String, phone: String): TrustedContact {
        val (cleanName, cleanPhone) = validate(name, phone)
        return repository.add(cleanName, cleanPhone)
    }

    fun update(id: Long, name: String, phone: String): TrustedContact {
        require(id > 0) { "Contato inválido." }
        val (cleanName, cleanPhone) = validate(name, phone)
        return repository.update(id, cleanName, cleanPhone)
    }

    private fun validate(name: String, phone: String): Pair<String, String> {
        val cleanName = name.trim()
        val cleanPhone = phone.filter { it.isDigit() || it == '+' }
        if (cleanName.length !in 2..80) {
            throw ContactValidationException("Informe um nome entre 2 e 80 caracteres.")
        }
        if (cleanPhone.length !in 8..16) {
            throw ContactValidationException("Informe um telefone válido com DDD.")
        }
        return cleanName to cleanPhone
    }

    fun delete(id: Long) = repository.delete(id)
}

class LoadDashboard(
    private val contacts: ContactRepository,
    private val alerts: AlertRepository,
) {
    operator fun invoke() = DashboardSnapshot(contacts.list(), alerts.latest())
}

class LoadSmsDeliveryStatus(private val deliveries: SmsDeliveryRepository) {
    operator fun invoke(alertId: Long?): List<SmsDeliveryAttempt> =
        if (alertId == null || alertId <= 0) emptyList() else deliveries.listForAlert(alertId)
}

class PrepareEmergencyAlert(
    private val contacts: ContactRepository,
    private val alerts: AlertRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun execute(location: GeoPoint?): PreparedAlert {
        val trustedContacts = contacts.list()
        require(trustedContacts.isNotEmpty()) {
            "Cadastre ao menos um contato de confiança antes de acionar o alerta."
        }
        val alert = alerts.create(
            SafetyAlert(
                createdAtEpochMillis = clock(),
                status = AlertStatus.READY_TO_SHARE,
                location = location,
            ),
        )
        return PreparedAlert(alert, buildMessage(alert), trustedContacts)
    }

    private fun buildMessage(alert: SafetyAlert): String = buildString {
        append("ALERTA — Estou Seguro: preciso de ajuda. ")
        alert.location?.let { append("Minha última localização conhecida: ${it.mapsUrl()}. ") }
        append("Confirme comigo por outro canal. Se houver risco imediato, contate as autoridades.")
    }
}

data class PreparedAlert(
    val alert: SafetyAlert,
    val message: String,
    val recipients: List<TrustedContact>,
)
