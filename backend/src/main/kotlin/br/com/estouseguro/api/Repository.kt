package br.com.estouseguro.api

import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class AppRepository(private val dataSource: DataSource, private val config: AppConfig) {
    fun issueActivationCode(): ActivationCodeResponse {
        check(!config.isProduction) { "Activation codes are only available in sandbox" }
        val createdAt = Instant.now()
        val expiresAt = SandboxActivationCodes.expiresAt(createdAt)
        val displayedCode = SandboxActivationCodes.generate()
        val normalizedCode = checkNotNull(SandboxActivationCodes.normalize(displayedCode))
        dataSource.transaction { c ->
            c.prepareStatement(
                "DELETE FROM sandbox_activation_code WHERE expires_at < now()-interval '1 day' OR consumed_at < now()-interval '1 day'"
            ).use { it.executeUpdate() }
            c.prepareStatement(
                "INSERT INTO sandbox_activation_code(id,code_hash,created_at,expires_at) VALUES (?,?,?,?)"
            ).use { s ->
                s.setObject(1, UUID.randomUUID())
                s.setBytes(2, activationCodeHash(normalizedCode))
                s.setTimestamp(3, Timestamp.from(createdAt))
                s.setTimestamp(4, Timestamp.from(expiresAt))
                s.executeUpdate()
            }
        }
        return ActivationCodeResponse(displayedCode, expiresAt)
    }

    fun registerDevice(displayName: String, activationCode: String): RegisterDeviceResponse {
        val name = displayName.trim().take(80)
        require(name.length >= 2) { "Informe um nome válido" }
        val normalizedCode = SandboxActivationCodes.normalize(activationCode)
            ?: throw ApiException(401, "ACTIVATION_CODE_INVALID", ACTIVATION_CODE_ERROR)
        val id = UUID.randomUUID()
        val token = SecureTokens.randomToken()
        dataSource.transaction { c ->
            val codeId = c.prepareStatement(
                "SELECT id FROM sandbox_activation_code WHERE code_hash=? AND consumed_at IS NULL AND expires_at>now() FOR UPDATE"
            ).use { s ->
                s.setBytes(1, activationCodeHash(normalizedCode))
                s.executeQuery().use { r ->
                    if (!r.next()) throw ApiException(401, "ACTIVATION_CODE_INVALID", ACTIVATION_CODE_ERROR)
                    r.getObject(1, UUID::class.java)
                }
            }
            c.prepareStatement(
                "INSERT INTO device_account(id, display_name, access_token_hash) VALUES (?, ?, ?)"
            ).use { s ->
                s.setObject(1, id)
                s.setString(2, name)
                s.setBytes(3, SecureTokens.hmac(config.deviceTokenPepper, token))
                s.executeUpdate()
            }
            val consumed = c.prepareStatement(
                "UPDATE sandbox_activation_code SET consumed_at=now(),consumed_device_id=? WHERE id=? AND consumed_at IS NULL AND expires_at>now()"
            ).use { s ->
                s.setObject(1, id)
                s.setObject(2, codeId)
                s.executeUpdate()
            }
            check(consumed == 1) { "Activation code claim lost inside locked transaction" }
        }
        return RegisterDeviceResponse(id, token)
    }

    private fun activationCodeHash(normalizedCode: String): ByteArray =
        SecureTokens.hmac(config.deviceTokenPepper, "sandbox-activation:$normalizedCode")

    fun authenticate(token: String): DevicePrincipal? {
        if (token.isBlank()) return null
        dataSource.connection.use { c -> c.prepareStatement(
            "SELECT id FROM device_account WHERE access_token_hash = ? AND revoked_at IS NULL"
        ).use { s -> s.setBytes(1, SecureTokens.hmac(config.deviceTokenPepper, token)); s.executeQuery().use { r -> return if (r.next()) DevicePrincipal(r.getObject(1, UUID::class.java)) else null } } }
    }

    fun saveContact(deviceId: UUID, input: ContactRequest): ContactResponse {
        val name = input.name.trim().take(80)
        require(name.length >= 2) { "Nome do contato inválido" }
        val phone = BrazilianPhones.normalize(input.phone) ?: throw IllegalArgumentException("Celular brasileiro inválido")
        val consentToken = SecureTokens.randomToken()
        val consentHash = SecureTokens.hmac(config.consentTokenPepper, consentToken)
        val id = UUID.randomUUID()
        dataSource.connection.use { c -> c.prepareStatement(
            """INSERT INTO trusted_contact(id, device_id, name, phone_e164, consent_status, consent_token_hash)
                VALUES (?, ?, ?, ?, 'PENDING', ?)
                ON CONFLICT(device_id, phone_e164) DO UPDATE SET name=EXCLUDED.name,
                  consent_token_hash=CASE WHEN trusted_contact.consent_status='GRANTED' THEN trusted_contact.consent_token_hash ELSE EXCLUDED.consent_token_hash END,
                  consent_status=CASE WHEN trusted_contact.consent_status='GRANTED' THEN 'GRANTED' ELSE 'PENDING' END,
                  revoked_at=CASE WHEN trusted_contact.consent_status='GRANTED' THEN trusted_contact.revoked_at ELSE NULL END,
                  updated_at=now()
                RETURNING id, consent_status"""
        ).use { s ->
            s.setObject(1, id); s.setObject(2, deviceId); s.setString(3, name); s.setString(4, phone); s.setBytes(5, consentHash)
            s.executeQuery().use { r -> r.next(); val savedId = r.getObject(1, UUID::class.java); val status = r.getString(2)
                return ContactResponse(savedId, name, BrazilianPhones.mask(phone), status,
                    if (status == "PENDING") "${config.publicBaseUrl}/consent/$consentToken" else null)
            }
        } }
    }

    fun listContacts(deviceId: UUID): List<ContactResponse> {
        val out = mutableListOf<ContactResponse>()
        dataSource.connection.use { c -> c.prepareStatement(
            "SELECT id,name,phone_e164,consent_status FROM trusted_contact WHERE device_id=? ORDER BY created_at"
        ).use { s -> s.setObject(1, deviceId); s.executeQuery().use { r -> while (r.next()) out += ContactResponse(r.getObject(1, UUID::class.java), r.getString(2), BrazilianPhones.mask(r.getString(3)), r.getString(4)) } } }
        return out
    }

    fun grantConsent(token: String): Boolean {
        val hash = SecureTokens.hmac(config.consentTokenPepper, token)
        dataSource.connection.use { c -> c.prepareStatement(
            "UPDATE trusted_contact SET consent_status='GRANTED', consented_at=now(), revoked_at=NULL, updated_at=now() WHERE consent_token_hash=? AND consent_status='PENDING'"
        ).use { s -> s.setBytes(1, hash); return s.executeUpdate() == 1 } }
    }

    fun consentStatus(token: String): String? {
        val hash = SecureTokens.hmac(config.consentTokenPepper, token)
        dataSource.connection.use { c -> c.prepareStatement(
            "SELECT consent_status FROM trusted_contact WHERE consent_token_hash=?"
        ).use { s -> s.setBytes(1, hash); s.executeQuery().use { r -> return if (r.next()) r.getString(1) else null } } }
    }

    fun revokeConsent(token: String): Boolean {
        val hash = SecureTokens.hmac(config.consentTokenPepper, token)
        return dataSource.transaction { c ->
            val contactId = c.prepareStatement(
                "SELECT id FROM trusted_contact WHERE consent_token_hash=? AND consent_status IN ('PENDING','GRANTED') FOR UPDATE"
            ).use { s ->
                s.setBytes(1, hash)
                s.executeQuery().use { r -> if (r.next()) r.getObject(1, UUID::class.java) else null }
            } ?: return@transaction false
            c.prepareStatement(
                "UPDATE trusted_contact SET consent_status='REVOKED', revoked_at=now(), updated_at=now() WHERE id=?"
            ).use { s -> s.setObject(1, contactId); s.executeUpdate() }
            cancelPendingDeliveries(c, contactId)
            true
        }
    }

    fun revokeContact(deviceId: UUID, contactId: UUID): Boolean = dataSource.transaction { c ->
        val found = c.prepareStatement(
            "SELECT 1 FROM trusted_contact WHERE id=? AND device_id=? FOR UPDATE"
        ).use { s ->
            s.setObject(1, contactId)
            s.setObject(2, deviceId)
            s.executeQuery().use { it.next() }
        }
        if (!found) return@transaction false
        c.prepareStatement(
            "UPDATE trusted_contact SET consent_status='REVOKED', consent_token_hash=NULL, revoked_at=now(), updated_at=now() WHERE id=?"
        ).use { s -> s.setObject(1, contactId); s.executeUpdate() }
        cancelPendingDeliveries(c, contactId)
        true
    }

    fun createAlert(deviceId: UUID, key: String, input: CreateAlertRequest): CreateAlertResponse {
        require(key.matches(Regex("[A-Za-z0-9._:-]{16,128}"))) { "Idempotency-Key inválida" }
        require((input.latitude == null) == (input.longitude == null)) { "Latitude e longitude devem ser informadas juntas" }
        input.latitude?.let { require(it in -90.0..90.0) }
        input.longitude?.let { require(it in -180.0..180.0) }
        val canonical = "${input.category}|${input.latitude}|${input.longitude}|${input.capturedAt}"
        val requestHash = SecureTokens.hmac(config.deviceTokenPepper, canonical)
        return dataSource.transaction { c ->
            c.prepareStatement("SELECT id,state,request_hash FROM safety_alert WHERE device_id=? AND idempotency_key=?").use { s ->
                s.setObject(1, deviceId); s.setString(2, key); s.executeQuery().use { r -> if (r.next()) {
                    if (!java.security.MessageDigest.isEqual(requestHash, r.getBytes(3))) throw ApiException(409, "IDEMPOTENCY_CONFLICT", "A chave já foi usada com outro alerta")
                    return@transaction counts(c, r.getObject(1, UUID::class.java), r.getString(2))
                } }
            }
            val alertId = UUID.randomUUID()
            c.prepareStatement("INSERT INTO safety_alert(id,device_id,idempotency_key,request_hash,category,latitude,longitude,location_captured_at,state) VALUES (?,?,?,?,?,?,?,?, 'QUEUED')").use { s ->
                s.setObject(1, alertId); s.setObject(2, deviceId); s.setString(3, key); s.setBytes(4, requestHash); s.setString(5, input.category.name)
                s.setObject(6, input.latitude); s.setObject(7, input.longitude)
                s.setTimestamp(8, input.capturedAt?.let(Timestamp::from)); s.executeUpdate()
            }
            c.prepareStatement("""INSERT INTO whatsapp_delivery(id,alert_id,contact_id,recipient_phone,state)
                SELECT gen_random_uuid(), ?, id, phone_e164, 'PENDING' FROM trusted_contact WHERE device_id=? AND consent_status='GRANTED'""").use { s -> s.setObject(1, alertId); s.setObject(2, deviceId); s.executeUpdate() }
            refreshAlertState(c, alertId)
            counts(c, alertId, readAlertState(c, alertId))
        }
    }

    fun getAlertStatus(deviceId: UUID, alertId: UUID): AlertStatusResponse = dataSource.connection.use { c ->
        val header = c.prepareStatement(
            "SELECT state,category,created_at FROM safety_alert WHERE id=? AND device_id=?"
        ).use { s ->
            s.setObject(1, alertId)
            s.setObject(2, deviceId)
            s.executeQuery().use { r ->
                if (!r.next()) throw ApiException(404, "ALERT_NOT_FOUND", "Alerta não encontrado")
                Triple(r.getString(1), AlertCategory.valueOf(r.getString(2)), r.getTimestamp(3).toInstant())
            }
        }
        val recipients = counts(c, alertId, header.first)
        val deliveryCounts = c.prepareStatement(
            """SELECT
                count(*) FILTER (WHERE state IN ('PENDING','RETRY')),
                count(*) FILTER (WHERE state IN ('CLAIMED','ACCEPTED','SENT')),
                count(*) FILTER (WHERE state IN ('DELIVERED','READ')),
                count(*) FILTER (WHERE state='FAILED')
               FROM whatsapp_delivery WHERE alert_id=?"""
        ).use { s ->
            s.setObject(1, alertId)
            s.executeQuery().use { r ->
                r.next()
                DeliveryStatusCounts(r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4))
            }
        }
        AlertStatusResponse(
            alertId, header.first, header.second, header.third,
            recipients.authorizedRecipients, recipients.pendingConsentRecipients, deliveryCounts,
        )
    }

    private fun counts(c: java.sql.Connection, id: UUID, state: String): CreateAlertResponse {
        c.prepareStatement("""SELECT count(*) FILTER (WHERE tc.consent_status='GRANTED'), count(*) FILTER (WHERE tc.consent_status='PENDING')
            FROM trusted_contact tc JOIN safety_alert a ON a.device_id=tc.device_id WHERE a.id=?""").use { s -> s.setObject(1, id); s.executeQuery().use { r -> r.next(); return CreateAlertResponse(id, state, r.getInt(1), r.getInt(2)) } }
    }

    fun claimJobs(limit: Int = 20): List<DeliveryJob> = dataSource.transaction { c ->
        val jobs = mutableListOf<DeliveryJob>()
        c.prepareStatement("""SELECT wd.id,wd.alert_id,wd.recipient_phone,tc.name,da.display_name,a.category,a.latitude,a.longitude,wd.attempt_count
            FROM whatsapp_delivery wd JOIN safety_alert a ON a.id=wd.alert_id JOIN trusted_contact tc ON tc.id=wd.contact_id
            JOIN device_account da ON da.id=a.device_id WHERE
              ((wd.state IN ('PENDING','RETRY') AND wd.next_attempt_at<=now()) OR (wd.state='CLAIMED' AND wd.claimed_at < now()-interval '2 minutes'))
            ORDER BY wd.next_attempt_at FOR UPDATE OF wd SKIP LOCKED LIMIT ?""").use { s -> s.setInt(1, limit); s.executeQuery().use { r -> while (r.next()) jobs += r.toJob() } }
        if (jobs.isNotEmpty()) c.prepareStatement("UPDATE whatsapp_delivery SET state='CLAIMED',claimed_at=now(),attempt_count=attempt_count+1,updated_at=now() WHERE id = ANY (?)").use { s ->
            s.setArray(1, c.createArrayOf("uuid", jobs.map { it.id }.toTypedArray())); s.executeUpdate()
        }
        jobs.map { it.alertId }.distinct().forEach { refreshAlertState(c, it) }
        jobs
    }

    fun markAccepted(id: UUID, providerId: String) = updateDelivery(id, "ACCEPTED", providerId, null)
    fun markRetry(id: UUID, attempts: Int, error: String) {
        val terminal = attempts + 1 >= 5
        dataSource.transaction { c ->
            val alertId = deliveryAlertId(c, id) ?: return@transaction
            c.prepareStatement("UPDATE whatsapp_delivery SET state=?,last_error=?,next_attempt_at=now()+(? * interval '1 minute'),updated_at=now() WHERE id=? AND state='CLAIMED'").use { s ->
                s.setString(1, if (terminal) "FAILED" else "RETRY"); s.setString(2, error.take(500)); s.setInt(3, 1 shl attempts.coerceAtMost(5)); s.setObject(4, id); s.executeUpdate()
            }
            refreshAlertState(c, alertId)
        }
    }
    private fun updateDelivery(id: UUID, state: String, providerId: String?, error: String?) {
        dataSource.transaction { c ->
            val alertId = deliveryAlertId(c, id) ?: return@transaction
            c.prepareStatement("UPDATE whatsapp_delivery SET state=?,provider_message_id=?,last_error=?,updated_at=now() WHERE id=? AND state='CLAIMED'").use { s ->
                s.setString(1,state); s.setString(2,providerId); s.setString(3,error); s.setObject(4,id); s.executeUpdate()
            }
            refreshAlertState(c, alertId)
        }
    }
    fun applyProviderEvent(eventId: String, providerId: String, state: String, error: String?) {
        if (state !in setOf("SENT","DELIVERED","READ","FAILED")) return
        dataSource.transaction { c ->
            val inserted = c.prepareStatement("INSERT INTO webhook_event(event_id) VALUES (?) ON CONFLICT DO NOTHING").use { s ->
                s.setString(1, eventId.take(255)); s.executeUpdate() == 1
            }
            if (!inserted) return@transaction
            c.prepareStatement("""UPDATE whatsapp_delivery SET
                state=CASE
                  WHEN ?='FAILED' AND state NOT IN ('DELIVERED','READ','FAILED') THEN 'FAILED'
                  WHEN ?='READ' AND state IN ('CLAIMED','ACCEPTED','SENT','DELIVERED') THEN 'READ'
                  WHEN ?='DELIVERED' AND state IN ('CLAIMED','ACCEPTED','SENT') THEN 'DELIVERED'
                  WHEN ?='SENT' AND state IN ('ACCEPTED','CLAIMED') THEN 'SENT'
                  ELSE state END,
                last_error=CASE WHEN ?='FAILED' THEN ? ELSE last_error END, updated_at=now()
                WHERE provider_message_id=?""").use { s ->
                s.setString(1,state); s.setString(2,state); s.setString(3,state); s.setString(4,state)
                s.setString(5,state); s.setString(6,error?.take(500)); s.setString(7,providerId); s.executeUpdate()
            }
            c.prepareStatement("SELECT alert_id FROM whatsapp_delivery WHERE provider_message_id=?").use { s ->
                s.setString(1, providerId)
                s.executeQuery().use { r -> if (r.next()) refreshAlertState(c, r.getObject(1, UUID::class.java)) }
            }
        }
    }

    private fun deliveryAlertId(c: java.sql.Connection, deliveryId: UUID): UUID? =
        c.prepareStatement("SELECT alert_id FROM whatsapp_delivery WHERE id=? FOR UPDATE").use { s ->
            s.setObject(1, deliveryId)
            s.executeQuery().use { r -> if (r.next()) r.getObject(1, UUID::class.java) else null }
        }

    private fun readAlertState(c: java.sql.Connection, alertId: UUID): String =
        c.prepareStatement("SELECT state FROM safety_alert WHERE id=?").use { s ->
            s.setObject(1, alertId)
            s.executeQuery().use { r -> check(r.next()); r.getString(1) }
        }

    private fun refreshAlertState(c: java.sql.Connection, alertId: UUID) {
        val current = c.prepareStatement("SELECT state FROM safety_alert WHERE id=? FOR UPDATE").use { s ->
            s.setObject(1, alertId)
            s.executeQuery().use { r -> if (r.next()) r.getString(1) else return }
        }
        val deliveryStates = mutableListOf<String>()
        c.prepareStatement("SELECT state FROM whatsapp_delivery WHERE alert_id=?").use { s ->
            s.setObject(1, alertId)
            s.executeQuery().use { r -> while (r.next()) deliveryStates += r.getString(1) }
        }
        val aggregated = aggregateAlertState(current, deliveryStates)
        if (aggregated != current) c.prepareStatement("UPDATE safety_alert SET state=? WHERE id=?").use { s ->
            s.setString(1, aggregated)
            s.setObject(2, alertId)
            s.executeUpdate()
        }
    }

    private fun cancelPendingDeliveries(c: java.sql.Connection, contactId: UUID) {
        val alertIds = mutableListOf<UUID>()
        c.prepareStatement(
            "SELECT alert_id FROM whatsapp_delivery WHERE contact_id=? AND state IN ('PENDING','RETRY','CLAIMED') FOR UPDATE"
        ).use { s ->
            s.setObject(1, contactId)
            s.executeQuery().use { r -> while (r.next()) alertIds += r.getObject(1, UUID::class.java) }
        }
        if (alertIds.isEmpty()) return
        c.prepareStatement(
            "UPDATE whatsapp_delivery SET state='FAILED', last_error='CONSENT_REVOKED', updated_at=now() WHERE contact_id=? AND state IN ('PENDING','RETRY','CLAIMED')"
        ).use { s -> s.setObject(1, contactId); s.executeUpdate() }
        alertIds.distinct().forEach { refreshAlertState(c, it) }
    }
    fun ready(): Boolean = dataSource.connection.use { c -> c.prepareStatement("SELECT 1").use { s -> s.executeQuery().use { it.next() } } }

    companion object {
        private const val ACTIVATION_CODE_ERROR = "Código de ativação inválido, expirado ou já utilizado"
    }
}

internal fun aggregateAlertState(current: String, deliveryStates: List<String>): String {
    if (current in setOf("COMPLETE", "PARTIAL", "FAILED")) return current
    if (deliveryStates.isEmpty()) return "FAILED"
    val hasProcessing = deliveryStates.any { it in setOf("CLAIMED", "ACCEPTED", "SENT") }
    val hasQueued = deliveryStates.any { it in setOf("PENDING", "RETRY") }
    if (hasProcessing || hasQueued) {
        return if (current == "PROCESSING" || hasProcessing) "PROCESSING" else "QUEUED"
    }
    val delivered = deliveryStates.count { it in setOf("DELIVERED", "READ") }
    val failed = deliveryStates.count { it == "FAILED" }
    return when {
        delivered == deliveryStates.size -> "COMPLETE"
        failed == deliveryStates.size -> "FAILED"
        delivered + failed == deliveryStates.size -> "PARTIAL"
        else -> current
    }
}

private fun ResultSet.toJob(): DeliveryJob {
    fun nullableDouble(column: String): Double? { val value=getDouble(column); return if (wasNull()) null else value }
    return DeliveryJob(getObject("id", UUID::class.java), getObject("alert_id", UUID::class.java), getString("recipient_phone"), getString("name"), getString("display_name"), AlertCategory.valueOf(getString("category")), nullableDouble("latitude"), nullableDouble("longitude"), getInt("attempt_count"))
}
