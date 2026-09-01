package br.com.estouseguro.api

import io.ktor.server.auth.Principal
import java.time.Instant
import java.util.UUID

data class DevicePrincipal(val deviceId: UUID) : Principal

data class RegisterDeviceRequest(val displayName: String = "")
data class RegisterDeviceResponse(val deviceId: UUID, val accessToken: String)
data class ActivationCodeResponse(val code: String, val expiresAt: Instant)

data class ContactRequest(val name: String = "", val phone: String = "")
data class ContactResponse(
    val id: UUID,
    val name: String,
    val maskedPhone: String,
    val consentStatus: String,
    val consentUrl: String? = null,
)

enum class AlertCategory {
    GENERAL, MEDICAL, SECURITY, DOMESTIC_VIOLENCE, CHILD_DANGER, ANXIETY,
}

data class CreateAlertRequest(
    val category: AlertCategory = AlertCategory.GENERAL,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val capturedAt: Instant? = null,
)

data class CreateAlertResponse(
    val alertId: UUID,
    val state: String,
    val authorizedRecipients: Int,
    val pendingConsentRecipients: Int,
)

data class DeliveryStatusCounts(
    val queued: Int,
    val processing: Int,
    val delivered: Int,
    val failed: Int,
)

data class AlertStatusResponse(
    val alertId: UUID,
    val state: String,
    val category: AlertCategory,
    val createdAt: Instant,
    val authorizedRecipients: Int,
    val pendingConsentRecipients: Int,
    val deliveries: DeliveryStatusCounts,
)

data class ErrorResponse(val code: String, val message: String, val requestId: String? = null)

data class DeliveryJob(
    val id: UUID,
    val alertId: UUID,
    val recipientPhone: String,
    val recipientName: String,
    val ownerDisplayName: String,
    val category: AlertCategory,
    val latitude: Double?,
    val longitude: Double?,
    val attempts: Int,
)

class ApiException(val status: Int, val code: String, override val message: String) : RuntimeException(message)
