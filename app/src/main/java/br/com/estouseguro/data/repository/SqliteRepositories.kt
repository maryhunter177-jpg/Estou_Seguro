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
        database.writableDatabase.update(
            "sms_delivery_attempt", values, "id = ?", arrayOf(id.toString()),
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
