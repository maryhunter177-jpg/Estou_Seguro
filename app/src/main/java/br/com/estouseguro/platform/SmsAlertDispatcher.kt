package br.com.estouseguro.platform

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import br.com.estouseguro.data.local.EstouSeguroDatabase
import br.com.estouseguro.data.repository.SqliteSmsDeliveryRepository
import br.com.estouseguro.domain.model.SmsDeliveryAttempt
import br.com.estouseguro.domain.model.SmsDeliveryStatus
import br.com.estouseguro.domain.model.BrazilianPhoneNumber
import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.repository.SmsDeliveryRepository

/**
 * Direct carrier-SMS transport. The caller owns runtime permission UX and explicit user consent.
 * Contacts are handed to the modem sequentially. The next contact is released only after every
 * part of the current contact receives a SENT callback, limiting OEM/carrier throttling.
 */
class SmsAlertDispatcher(
    private val context: Context,
    private val repository: SmsDeliveryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun send(
        alertId: Long,
        message: String,
        contacts: List<TrustedContact>,
        subscriptionId: Int? = null,
    ): SmsDispatchResult {
        require(alertId > 0) { "Alert must be persisted before dispatch" }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SmsDispatchResult.PermissionRequired(Manifest.permission.SEND_SMS)
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            return SmsDispatchResult.UnsupportedDevice
        }
        val normalized = contacts.map { it to BrazilianPhoneNumber.normalizeForSms(it.phone) }
        val recipients = normalized.mapNotNull { it.second }.distinct()
        if (recipients.isEmpty()) return SmsDispatchResult.NoRecipients

        val initialized = repository.initializeDispatch(alertId, message, recipients, subscriptionId, clock())
        if (!initialized) {
            // The alert already owns a durable queue. Never create a second transport attempt.
            return SmsDispatchResult.Accepted(
                recipientCount = recipients.size,
                queuedPartCount = repository.listForAlert(alertId).size.coerceAtLeast(1),
                immediateFailures = emptyMap(),
            )
        }
        val first = dispatchNext(alertId)
        val invalid = normalized.filter { it.second == null }.associate {
            it.first.phone to RESULT_INVALID_BRAZILIAN_NUMBER
        }
        return SmsDispatchResult.Accepted(
            recipientCount = recipients.size,
            queuedPartCount = first.queuedParts,
            immediateFailures = invalid + first.immediateFailures,
        )
    }

    /** Continues a persisted dispatch; used by the callback receiver after process recreation. */
    internal fun resume(alertId: Long) {
        dispatchNext(alertId)
    }

    private fun dispatchNext(alertId: Long): DispatchProgress {
        val claim = repository.claimNextRecipient(alertId, clock()) ?: return DispatchProgress()
        var attempts = emptyList<SmsDeliveryAttempt>()
        return try {
            val manager = smsManager(claim.subscriptionId)
            val parts = manager.divideMessage(claim.message).ifEmpty { arrayListOf(claim.message) }
            attempts = parts.mapIndexed { index, _ ->
                repository.create(
                    SmsDeliveryAttempt(
                        alertId = alertId,
                        recipient = claim.recipient,
                        partIndex = index,
                        partCount = parts.size,
                        subscriptionId = claim.subscriptionId,
                        status = SmsDeliveryStatus.QUEUED,
                        updatedAtEpochMillis = clock(),
                    ),
                )
            }
            manager.sendMultipartTextMessage(
                claim.recipient,
                null,
                parts,
                ArrayList(attempts.map { statusIntent(it.id, alertId, claim.recipient, ACTION_SENT) }),
                ArrayList(attempts.map { statusIntent(it.id, alertId, claim.recipient, ACTION_DELIVERED) }),
            )
            DispatchProgress(queuedParts = parts.size)
        } catch (error: Exception) {
            attempts.forEach {
                repository.updateStatus(
                    it.id, SmsDeliveryStatus.SEND_FAILED, RESULT_IMMEDIATE_EXCEPTION, clock(),
                )
            }
            val hasNext = repository.completeRecipient(alertId, claim.recipient, clock())
            val following = if (hasNext) dispatchNext(alertId) else DispatchProgress()
            following.copy(
                immediateFailures = following.immediateFailures +
                    (claim.recipient to error.javaClass.simpleName),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subscriptionId: Int?): SmsManager = if (subscriptionId == null) {
        context.getSystemService(SmsManager::class.java)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
    } else {
        SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    }

    private fun statusIntent(attemptId: Long, alertId: Long, recipient: String, action: String): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ATTEMPT_ID, attemptId)
            putExtra(EXTRA_ALERT_ID, alertId)
            putExtra(EXTRA_RECIPIENT, recipient)
        }
        return PendingIntent.getBroadcast(
            context,
            (attemptId xor action.hashCode().toLong()).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        internal const val ACTION_SENT = "br.com.estouseguro.SMS_SENT"
        internal const val ACTION_DELIVERED = "br.com.estouseguro.SMS_DELIVERED"
        internal const val EXTRA_ATTEMPT_ID = "attempt_id"
        internal const val EXTRA_ALERT_ID = "alert_id"
        internal const val EXTRA_RECIPIENT = "recipient"
        internal const val RESULT_IMMEDIATE_EXCEPTION = -10_001
        private const val RESULT_INVALID_BRAZILIAN_NUMBER = "INVALID_BRAZILIAN_NUMBER"

        fun create(context: Context): SmsAlertDispatcher {
            val app = context.applicationContext
            return SmsAlertDispatcher(app, SqliteSmsDeliveryRepository(EstouSeguroDatabase(app)))
        }
    }
}

private data class DispatchProgress(
    val queuedParts: Int = 0,
    val immediateFailures: Map<String, String> = emptyMap(),
)

sealed interface SmsDispatchResult {
    data class Accepted(
        val recipientCount: Int,
        val queuedPartCount: Int,
        val immediateFailures: Map<String, String>,
    ) : SmsDispatchResult
    data class PermissionRequired(val permission: String) : SmsDispatchResult
    data object UnsupportedDevice : SmsDispatchResult
    data object NoRecipients : SmsDispatchResult
}

data class SmsSubscription(
    val id: Int,
    val displayName: String,
    val carrierName: String,
    val isDefault: Boolean,
)

object SmsSubscriptions {
    fun active(context: Context): List<SmsSubscription> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val defaultId = SubscriptionManager.getDefaultSmsSubscriptionId()
        @Suppress("MissingPermission")
        val subscriptions: List<SubscriptionInfo> = manager.activeSubscriptionInfoList.orEmpty()
        return subscriptions.map {
            SmsSubscription(
                id = it.subscriptionId,
                displayName = it.displayName?.toString().orEmpty(),
                carrierName = it.carrierName?.toString().orEmpty(),
                isDefault = it.subscriptionId == defaultId,
            )
        }
    }
}

/** Persists radio/network callbacks even if the app process was recreated. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val attemptId = intent.getLongExtra(SmsAlertDispatcher.EXTRA_ATTEMPT_ID, 0)
        if (attemptId <= 0) return
        val alertId = intent.getLongExtra(SmsAlertDispatcher.EXTRA_ALERT_ID, 0)
        val recipient = intent.getStringExtra(SmsAlertDispatcher.EXTRA_RECIPIENT).orEmpty()
        val status = when (intent.action) {
            SmsAlertDispatcher.ACTION_SENT -> if (resultCode == Activity.RESULT_OK) {
                SmsDeliveryStatus.HANDED_TO_RADIO
            } else {
                SmsDeliveryStatus.SEND_FAILED
            }
            SmsAlertDispatcher.ACTION_DELIVERED -> if (resultCode == Activity.RESULT_OK) {
                SmsDeliveryStatus.DELIVERED
            } else {
                SmsDeliveryStatus.DELIVERY_FAILED
            }
            else -> return
        }
        val repository = SqliteSmsDeliveryRepository(EstouSeguroDatabase(context.applicationContext))
        repository.updateStatus(
            attemptId,
            status,
            resultCode,
            System.currentTimeMillis(),
        )
        if (intent.action != SmsAlertDispatcher.ACTION_SENT || alertId <= 0 || recipient.isBlank()) return

        val currentParts = repository.listForAlert(alertId).filter { it.recipient == recipient }
        val modemAnsweredForEveryPart = currentParts.isNotEmpty() && currentParts.all {
            it.status != SmsDeliveryStatus.QUEUED
        }
        if (modemAnsweredForEveryPart && repository.completeRecipient(
                alertId, recipient, System.currentTimeMillis(),
            )
        ) {
            SmsAlertDispatcher.create(context).resume(alertId)
        }
    }
}
