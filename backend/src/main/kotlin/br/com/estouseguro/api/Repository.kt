package br.com.estouseguro.api

import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class AppRepository(private val dataSource: DataSource, private val config: AppConfig) {
    fun registerDevice(displayName: String): RegisterDeviceResponse {
        val name = displayName.trim().take(80)
        require(name.length >= 2) { "Informe um nome válido" }
        val id = UUID.randomUUID()
        val token = SecureTokens.randomToken()
        dataSource.connection.use { c -> c.prepareStatement(
            "INSERT INTO device_account(id, display_name, access_token_hash) VALUES (?, ?, ?)"
        ).use { s -> s.setObject(1, id); s.setString(2, name); s.setBytes(3, SecureTokens.hmac(config.deviceTokenPepper, token)); s.executeUpdate() } }
        return RegisterDeviceResponse(id, token)
    }

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
                  consent_status=CASE WHEN trusted_contact.consent_status='GRANTED' THEN 'GRANTED' ELSE 'PENDING' END, updated_at=now()
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
            "UPDATE trusted_contact SET consent_status='GRANTED', consented_at=now(), consent_token_hash=NULL, updated_at=now() WHERE consent_token_hash=? AND consent_status='PENDING'"
        ).use { s -> s.setBytes(1, hash); return s.executeUpdate() == 1 } }
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
                s.setObject(6, input.latitude); s.setObject(7, input.longitude); s.setObject(8, input.capturedAt); s.executeUpdate()
            }
            c.prepareStatement("""INSERT INTO whatsapp_delivery(id,alert_id,contact_id,recipient_phone,state)
                SELECT gen_random_uuid(), ?, id, phone_e164, 'PENDING' FROM trusted_contact WHERE device_id=? AND consent_status='GRANTED'""").use { s -> s.setObject(1, alertId); s.setObject(2, deviceId); s.executeUpdate() }
            counts(c, alertId, "QUEUED")
        }
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
        jobs
    }

    fun markAccepted(id: UUID, providerId: String) = update(id, "ACCEPTED", providerId, null)
    fun markRetry(id: UUID, attempts: Int, error: String) {
        val terminal = attempts + 1 >= 5
        dataSource.connection.use { c -> c.prepareStatement("UPDATE whatsapp_delivery SET state=?,last_error=?,next_attempt_at=now()+(? * interval '1 minute'),updated_at=now() WHERE id=?").use { s ->
            s.setString(1, if (terminal) "FAILED" else "RETRY"); s.setString(2, error.take(500)); s.setInt(3, 1 shl attempts.coerceAtMost(5)); s.setObject(4, id); s.executeUpdate()
        } }
    }
    private fun update(id: UUID, state: String, providerId: String?, error: String?) { dataSource.connection.use { c -> c.prepareStatement("UPDATE whatsapp_delivery SET state=?,provider_message_id=?,last_error=?,updated_at=now() WHERE id=?").use { s -> s.setString(1,state); s.setString(2,providerId); s.setString(3,error); s.setObject(4,id); s.executeUpdate() } } }
    fun applyProviderEvent(eventId: String, providerId: String, state: String, error: String?) {
        if (state !in setOf("SENT","DELIVERED","READ","FAILED")) return
        dataSource.transaction { c ->
            val inserted = c.prepareStatement("INSERT INTO webhook_event(event_id) VALUES (?) ON CONFLICT DO NOTHING").use { s ->
                s.setString(1, eventId.take(255)); s.executeUpdate() == 1
            }
            if (!inserted) return@transaction
            c.prepareStatement("""UPDATE whatsapp_delivery SET
                state=CASE
                  WHEN ?='FAILED' AND state NOT IN ('DELIVERED','READ') THEN 'FAILED'
                  WHEN ?='READ' THEN 'READ'
                  WHEN ?='DELIVERED' AND state NOT IN ('READ') THEN 'DELIVERED'
                  WHEN ?='SENT' AND state IN ('ACCEPTED','CLAIMED') THEN 'SENT'
                  ELSE state END,
                last_error=CASE WHEN ?='FAILED' THEN ? ELSE last_error END, updated_at=now()
                WHERE provider_message_id=?""").use { s ->
                s.setString(1,state); s.setString(2,state); s.setString(3,state); s.setString(4,state)
                s.setString(5,state); s.setString(6,error?.take(500)); s.setString(7,providerId); s.executeUpdate()
            }
        }
    }
    fun ready(): Boolean = dataSource.connection.use { c -> c.prepareStatement("SELECT 1").use { s -> s.executeQuery().use { it.next() } } }
}

private fun ResultSet.toJob(): DeliveryJob {
    fun nullableDouble(column: String): Double? { val value=getDouble(column); return if (wasNull()) null else value }
    return DeliveryJob(getObject("id", UUID::class.java), getObject("alert_id", UUID::class.java), getString("recipient_phone"), getString("name"), getString("display_name"), AlertCategory.valueOf(getString("category")), nullableDouble("latitude"), nullableDouble("longitude"), getInt("attempt_count"))
}
