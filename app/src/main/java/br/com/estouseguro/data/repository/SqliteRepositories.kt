package br.com.estouseguro.data.repository

import android.content.ContentValues
import br.com.estouseguro.data.local.EstouSeguroDatabase
import br.com.estouseguro.domain.model.AlertStatus
import br.com.estouseguro.domain.model.GeoPoint
import br.com.estouseguro.domain.model.SafetyAlert
import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.repository.AlertRepository
import br.com.estouseguro.domain.repository.ContactRepository
import br.com.estouseguro.domain.model.SmsDeliveryAttempt
import br.com.estouseguro.domain.model.SmsDeliveryStatus
import br.com.estouseguro.domain.model.SmsDispatchClaim
import br.com.estouseguro.domain.repository.SmsDeliveryRepository

class SqliteContactRepository(private val database: EstouSeguroDatabase) : ContactRepository {
    override fun list(): List<TrustedContact> {
        val contacts = mutableListOf<TrustedContact>()
        database.readableDatabase.query(
            "trusted_contact",
            arrayOf("id", "name", "phone"),
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                contacts += TrustedContact(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    phone = cursor.getString(2),
                )
            }
        }
        return contacts
    }

    override fun add(name: String, phone: String): TrustedContact {
        val values = ContentValues().apply {
            put("name", name)
            put("phone", phone)
            put("created_at", System.currentTimeMillis())
        }
        val id = database.writableDatabase.insertOrThrow("trusted_contact", null, values)
        return TrustedContact(id, name, phone)
    }

    override fun update(id: Long, name: String, phone: String): TrustedContact {
        val values = ContentValues().apply {
            put("name", name)
            put("phone", phone)
        }
        val changed = database.writableDatabase.update(
            "trusted_contact",
            values,
            "id = ?",
            arrayOf(id.toString()),
        )
        check(changed == 1) { "Contato não encontrado." }
        return TrustedContact(id, name, phone)
    }

    override fun delete(id: Long) {
        database.writableDatabase.delete("trusted_contact", "id = ?", arrayOf(id.toString()))
    }
}

class SqliteSmsDeliveryRepository(private val database: EstouSeguroDatabase) : SmsDeliveryRepository {
    override fun create(attempt: SmsDeliveryAttempt): SmsDeliveryAttempt {
        val values = attempt.toValues()
        val id = database.writableDatabase.insertOrThrow("sms_delivery_attempt", null, values)
        return attempt.copy(id = id)
    }

    override fun updateStatus(
        id: Long,
        status: SmsDeliveryStatus,
        platformResultCode: Int?,
        updatedAtEpochMillis: Long,
    ) {
        val values = ContentValues().apply {
            put("status", status.name)
            if (platformResultCode == null) putNull("platform_result_code")
            else put("platform_result_code", platformResultCode)
            put("updated_at", updatedAtEpochMillis)
        }
        val allowedPrevious = when (status) {
            SmsDeliveryStatus.HANDED_TO_RADIO -> listOf(SmsDeliveryStatus.QUEUED)
            SmsDeliveryStatus.DELIVERED,
            SmsDeliveryStatus.DELIVERY_FAILED ->
                listOf(SmsDeliveryStatus.QUEUED, SmsDeliveryStatus.HANDED_TO_RADIO)
            SmsDeliveryStatus.SEND_FAILED -> listOf(SmsDeliveryStatus.QUEUED)
            SmsDeliveryStatus.QUEUED -> emptyList()
        }
        if (allowedPrevious.isEmpty()) return
        val placeholders = allowedPrevious.joinToString(",") { "?" }
        database.writableDatabase.update(
            "sms_delivery_attempt",
            values,
            "id = ? AND status IN ($placeholders)",
            arrayOf(id.toString(), *allowedPrevious.map { it.name }.toTypedArray()),
        )
    }

    override fun listForAlert(alertId: Long): List<SmsDeliveryAttempt> {
        val result = mutableListOf<SmsDeliveryAttempt>()
        database.readableDatabase.query(
            "sms_delivery_attempt", COLUMNS, "alert_id = ?", arrayOf(alertId.toString()),
            null, null, "recipient ASC, part_index ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val resultCodeIndex = cursor.getColumnIndexOrThrow("platform_result_code")
                val subscriptionIndex = cursor.getColumnIndexOrThrow("subscription_id")
                result += SmsDeliveryAttempt(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    alertId = cursor.getLong(cursor.getColumnIndexOrThrow("alert_id")),
                    recipient = cursor.getString(cursor.getColumnIndexOrThrow("recipient")),
                    partIndex = cursor.getInt(cursor.getColumnIndexOrThrow("part_index")),
                    partCount = cursor.getInt(cursor.getColumnIndexOrThrow("part_count")),
                    subscriptionId = if (cursor.isNull(subscriptionIndex)) null else cursor.getInt(subscriptionIndex),
                    status = SmsDeliveryStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                    platformResultCode = if (cursor.isNull(resultCodeIndex)) null else cursor.getInt(resultCodeIndex),
                    updatedAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                )
            }
        }
        return result
    }

    override fun initializeDispatch(
        alertId: Long,
        message: String,
        recipients: List<String>,
        subscriptionId: Int?,
        updatedAtEpochMillis: Long,
    ): Boolean {
        require(recipients.isNotEmpty())
        require(recipients.none { '\n' in it })
        val values = ContentValues().apply {
            put("alert_id", alertId)
            put("message", message)
            put("recipients", recipients.joinToString("\n"))
            if (subscriptionId == null) putNull("subscription_id") else put("subscription_id", subscriptionId)
            put("next_recipient_index", 0)
            put("state", QUEUE_READY)
            put("updated_at", updatedAtEpochMillis)
        }
        return database.writableDatabase.insertWithOnConflict(
            "sms_dispatch_queue", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    override fun claimNextRecipient(alertId: Long, updatedAtEpochMillis: Long): SmsDispatchClaim? {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val claim = db.query(
                "sms_dispatch_queue",
                arrayOf("message", "recipients", "subscription_id", "next_recipient_index", "state"),
                "alert_id = ?",
                arrayOf(alertId.toString()),
                null, null, null,
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(4) != QUEUE_READY) return@use null
                val recipients = cursor.getString(1).split('\n').filter(String::isNotBlank)
                val index = cursor.getInt(3)
                if (index !in recipients.indices) return@use null
                SmsDispatchClaim(
                    alertId = alertId,
                    recipient = recipients[index],
                    message = cursor.getString(0),
                    subscriptionId = if (cursor.isNull(2)) null else cursor.getInt(2),
                )
            } ?: return null
            val changed = db.update(
                "sms_dispatch_queue",
                ContentValues().apply {
                    put("state", QUEUE_IN_FLIGHT)
                    put("updated_at", updatedAtEpochMillis)
                },
                "alert_id = ? AND state = ?",
                arrayOf(alertId.toString(), QUEUE_READY),
            )
            if (changed != 1) return null
            db.setTransactionSuccessful()
            return claim
        } finally {
            db.endTransaction()
        }
    }

    override fun completeRecipient(
        alertId: Long,
        recipient: String,
        updatedAtEpochMillis: Long,
    ): Boolean {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            var hasNext = false
            var currentIndex = -1
            val canAdvance = db.query(
                "sms_dispatch_queue",
                arrayOf("recipients", "next_recipient_index", "state"),
                "alert_id = ?",
                arrayOf(alertId.toString()),
                null, null, null,
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(2) != QUEUE_IN_FLIGHT) return@use false
                val recipients = cursor.getString(0).split('\n').filter(String::isNotBlank)
                val index = cursor.getInt(1)
                if (recipients.getOrNull(index) != recipient) return@use false
                currentIndex = index
                hasNext = index + 1 < recipients.size
                true
            }
            if (!canAdvance) return false
            val changed = db.update(
                "sms_dispatch_queue",
                ContentValues().apply {
                    put("next_recipient_index", currentIndex + 1)
                    put("state", if (hasNext) QUEUE_READY else QUEUE_COMPLETE)
                    put("updated_at", updatedAtEpochMillis)
                    if (!hasNext) {
                        // Keep the idempotency marker but erase transient sensitive payload data.
                        put("message", "")
                        put("recipients", "")
                    }
                },
                "alert_id = ? AND state = ? AND next_recipient_index = ?",
                arrayOf(alertId.toString(), QUEUE_IN_FLIGHT, currentIndex.toString()),
            )
            if (changed != 1) return false
            db.setTransactionSuccessful()
            return hasNext
        } finally {
            db.endTransaction()
        }
    }

    private fun SmsDeliveryAttempt.toValues() = ContentValues().apply {
        put("alert_id", alertId)
        put("recipient", recipient)
        put("part_index", partIndex)
        put("part_count", partCount)
        if (subscriptionId == null) putNull("subscription_id") else put("subscription_id", subscriptionId)
        put("status", status.name)
        if (platformResultCode == null) putNull("platform_result_code") else put("platform_result_code", platformResultCode)
        put("updated_at", updatedAtEpochMillis)
    }

    companion object {
        private const val QUEUE_READY = "READY"
        private const val QUEUE_IN_FLIGHT = "IN_FLIGHT"
        private const val QUEUE_COMPLETE = "COMPLETE"
        private val COLUMNS = arrayOf(
            "id", "alert_id", "recipient", "part_index", "part_count", "subscription_id",
            "status", "platform_result_code", "updated_at",
        )
    }
}

class SqliteAlertRepository(private val database: EstouSeguroDatabase) : AlertRepository {
    override fun create(alert: SafetyAlert): SafetyAlert {
        val id = database.writableDatabase.insertOrThrow("safety_alert", null, alert.toValues())
        return alert.copy(id = id)
    }

    override fun latest(): SafetyAlert? = database.readableDatabase.query(
        "safety_alert",
        COLUMNS,
        null,
        null,
        null,
        null,
        "created_at DESC",
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val latitudeIndex = cursor.getColumnIndexOrThrow("latitude")
        val location = if (cursor.isNull(latitudeIndex)) {
            null
        } else {
            GeoPoint(
                latitude = cursor.getDouble(latitudeIndex),
                longitude = cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
                capturedAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("location_captured_at")),
            )
        }
        SafetyAlert(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            createdAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            status = AlertStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
            location = location,
        )
    }

    override fun update(alert: SafetyAlert) {
        require(alert.id > 0) { "Cannot update an alert that was not persisted" }
        database.writableDatabase.update(
            "safety_alert",
            alert.toValues(),
            "id = ?",
            arrayOf(alert.id.toString()),
        )
    }

    private fun SafetyAlert.toValues() = ContentValues().apply {
        put("created_at", createdAtEpochMillis)
        put("status", status.name)
        if (location == null) {
            putNull("latitude")
            putNull("longitude")
            putNull("location_captured_at")
        } else {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("location_captured_at", location.capturedAtEpochMillis)
        }
    }

    companion object {
        private val COLUMNS = arrayOf(
            "id", "created_at", "status", "latitude", "longitude", "location_captured_at",
        )
    }
}
