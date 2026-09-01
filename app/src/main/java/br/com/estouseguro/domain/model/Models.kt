package br.com.estouseguro.domain.model

data class TrustedContact(
    val id: Long = 0,
    val name: String,
    val phone: String,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val capturedAtEpochMillis: Long,
) {
    fun mapsUrl(): String = "https://maps.google.com/?q=$latitude,$longitude"
}

enum class AlertStatus { PREPARING, READY_TO_SHARE, SHARED, CANCELLED }

enum class SmsDeliveryStatus {
    QUEUED,
    HANDED_TO_RADIO,
    DELIVERED,
    SEND_FAILED,
    DELIVERY_FAILED,
}

data class SmsDeliveryAttempt(
    val id: Long = 0,
    val alertId: Long,
    val recipient: String,
    val partIndex: Int,
    val partCount: Int,
    val subscriptionId: Int?,
    val status: SmsDeliveryStatus,
    val platformResultCode: Int? = null,
    val updatedAtEpochMillis: Long,
)

/** A single recipient atomically claimed from the durable SMS dispatch queue. */
data class SmsDispatchClaim(
    val alertId: Long,
    val recipient: String,
    val message: String,
    val subscriptionId: Int?,
)

data class SafetyAlert(
    val id: Long = 0,
    val createdAtEpochMillis: Long,
    val status: AlertStatus,
    val location: GeoPoint?,
)

data class DashboardSnapshot(
    val contacts: List<TrustedContact>,
    val latestAlert: SafetyAlert?,
)
