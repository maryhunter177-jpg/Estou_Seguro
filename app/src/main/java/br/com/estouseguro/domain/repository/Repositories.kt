package br.com.estouseguro.domain.repository

import br.com.estouseguro.domain.model.SafetyAlert
import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.model.SmsDeliveryAttempt
import br.com.estouseguro.domain.model.SmsDeliveryStatus
import br.com.estouseguro.domain.model.SmsDispatchClaim

interface ContactRepository {
    fun list(): List<TrustedContact>
    fun add(name: String, phone: String): TrustedContact
    fun update(id: Long, name: String, phone: String): TrustedContact
    fun delete(id: Long)
}

interface AlertRepository {
    fun create(alert: SafetyAlert): SafetyAlert
    fun latest(): SafetyAlert?
    fun update(alert: SafetyAlert)
}

interface SmsDeliveryRepository {
    fun create(attempt: SmsDeliveryAttempt): SmsDeliveryAttempt
    fun updateStatus(
        id: Long,
        status: SmsDeliveryStatus,
        platformResultCode: Int?,
        updatedAtEpochMillis: Long,
    )
    fun listForAlert(alertId: Long): List<SmsDeliveryAttempt>

    /** Creates an idempotent, durable, ordered dispatch. Returns false when it already exists. */
    fun initializeDispatch(
        alertId: Long,
        message: String,
        recipients: List<String>,
        subscriptionId: Int?,
        updatedAtEpochMillis: Long,
    ): Boolean

    /** Atomically reserves only the current recipient, preventing concurrent duplicate sends. */
    fun claimNextRecipient(alertId: Long, updatedAtEpochMillis: Long): SmsDispatchClaim?

    /** Advances only when [recipient] is still the in-flight recipient. */
    fun completeRecipient(alertId: Long, recipient: String, updatedAtEpochMillis: Long): Boolean
}

interface SessionRepository {
    fun hasCredential(): Boolean
    fun register(displayName: String, pin: CharArray)
    fun authenticate(pin: CharArray): Boolean
    fun displayName(): String
    fun clearSessionSecrets()
}
