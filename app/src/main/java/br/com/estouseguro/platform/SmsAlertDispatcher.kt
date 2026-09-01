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
import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.repository.SmsDeliveryRepository

/**
 * Direct carrier-SMS transport. The caller owns runtime permission UX and explicit user consent.
 * One independent send is made per contact so a failure never prevents remaining recipients.
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
        val recipients = contacts.mapNotNull { normalizePhone(it.phone) }.distinct()
        if (recipients.isEmpty()) return SmsDispatchResult.NoRecipients

        val manager = smsManager(subscriptionId)
        var queuedParts = 0
        val immediateFailures = mutableMapOf<String, String>()
        recipients.forEach { recipient ->
            val parts = manager.divideMessage(message).ifEmpty { arrayListOf(message) }
            val attempts = parts.mapIndexed { index, _ ->
                repository.create(
                    SmsDeliveryAttempt(
                        alertId = alertId,
                        recipient = recipient,
                        partIndex = index,
                        partCount = parts.size,
                        subscriptionId = subscriptionId,
                        status = SmsDeliveryStatus.QUEUED,
                        updatedAtEpochMillis = clock(),
                    ),
                )
            }
            try {
                manager.sendMultipartTextMessage(
                    recipient,
                    null,
                    parts,
                    ArrayList(attempts.map { statusIntent(it.id, ACTION_SENT) }),
                    ArrayList(attempts.map { statusIntent(it.id, ACTION_DELIVERED) }),
                )
                queuedParts += parts.size
            } catch (error: Exception) {
                attempts.forEach {
                    repository.updateStatus(
                        it.id, SmsDeliveryStatus.SEND_FAILED, RESULT_IMMEDIATE_EXCEPTION, clock(),
                    )
                }
                immediateFailures[recipient] = error.javaClass.simpleName
            }
        }
        return SmsDispatchResult.Accepted(
            recipientCount = recipients.size,
            queuedPartCount = queuedParts,
            immediateFailures = immediateFailures,
        )
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subscriptionId: Int?): SmsManager = if (subscriptionId == null) {
        context.getSystemService(SmsManager::class.java)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
    } else {
        SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    }

    private fun statusIntent(attemptId: Long, action: String): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ATTEMPT_ID, attemptId)
        }
        return PendingIntent.getBroadcast(
            context,
            (attemptId xor action.hashCode().toLong()).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun normalizePhone(raw: String): String? {
        val trimmed = raw.trim()
        val hasPlus = trimmed.startsWith('+')
        val digits = trimmed.filter(Char::isDigit)
        if (digits.length !in 8..15) return null
        return if (hasPlus) "+$digits" else digits
    }

    companion object {
        internal const val ACTION_SENT = "br.com.estouseguro.SMS_SENT"
        internal const val ACTION_DELIVERED = "br.com.estouseguro.SMS_DELIVERED"
        internal const val EXTRA_ATTEMPT_ID = "attempt_id"
        internal const val RESULT_IMMEDIATE_EXCEPTION = -10_001

        fun create(context: Context): SmsAlertDispatcher {
            val app = context.applicationContext
            return SmsAlertDispatcher(app, SqliteSmsDeliveryRepository(EstouSeguroDatabase(app)))
        }
    }
}

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
        SqliteSmsDeliveryRepository(EstouSeguroDatabase(context.applicationContext)).updateStatus(
            attemptId,
            status,
            resultCode,
            System.currentTimeMillis(),
        )
    }
}
