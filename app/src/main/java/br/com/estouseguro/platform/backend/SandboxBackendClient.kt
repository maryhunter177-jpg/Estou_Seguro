package br.com.estouseguro.platform.backend

import br.com.estouseguro.data.repository.BackendSession
import br.com.estouseguro.data.repository.BackendSessionStore
import br.com.estouseguro.data.repository.RemoteContactBinding
import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.usecase.PreparedAlert
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

enum class BackendAlertCategory {
    GENERAL, MEDICAL, SECURITY, DOMESTIC_VIOLENCE, CHILD_DANGER, ANXIETY,
}

data class ContactSyncResult(val status: String, val consentUrl: String?)
data class CloudAlertResult(
    val alertId: String,
    val authorizedRecipients: Int,
    val pendingConsentRecipients: Int,
    val pendingConsentUrls: List<String>,
)

class SandboxBackendException(message: String, val statusCode: Int? = null) : Exception(message)
class BackendActivationRequiredException : Exception("Ativacao do backend necessaria.")

/** HTTPS-only client for the sandbox API. It is deliberately additive to the local SMS path. */
class SandboxBackendClient(
    private val baseUrl: String,
    private val sessionStore: BackendSessionStore,
) {
    val isEnabled: Boolean = baseUrl.startsWith("https://")

    @Synchronized
    fun activate(displayName: String, rawCode: CharArray) {
        try {
            checkEnabled()
            if (sessionStore.load() != null) return
            val code = normalizeActivationCode(rawCode)
                ?: throw SandboxBackendException("Informe o codigo de ativacao com 12 caracteres.")
            val response = request(
                method = "POST",
                path = "/v1/devices/register",
                headers = mapOf("X-Sandbox-Activation-Code" to code),
                body = JSONObject().put("displayName", displayName),
            )
            BackendSession(
                deviceId = response.getString("deviceId"),
                accessToken = response.getString("accessToken"),
            ).also(sessionStore::save)
        } finally {
            rawCode.fill('\u0000')
        }
    }

    @Synchronized
    fun syncContact(displayName: String, contact: TrustedContact): ContactSyncResult {
        checkEnabled()
        var session = ensureRegistered(displayName)
        val existing = session.contacts.firstOrNull { it.localId == contact.id }
        if (existing != null && existing.localPhone == contact.phone) {
            session = refreshConsentStates(session)
            session.contacts.firstOrNull { it.localId == contact.id }?.let {
                if (it.consentStatus == "GRANTED" || it.consentUrl != null) {
                    return ContactSyncResult(it.consentStatus, it.consentUrl)
                }
            }
        }
        if (existing != null && existing.localPhone != contact.phone) {
            revokeRemoteContact(session, existing.remoteId)
            session = session.copy(contacts = session.contacts.filterNot { it.localId == contact.id })
            sessionStore.save(session)
        }
        val remote = postContact(session, contact)
        val updated = session.copy(
            contacts = session.contacts.filterNot { it.localId == contact.id } + remote,
        )
        sessionStore.save(updated)
        return ContactSyncResult(remote.consentStatus, remote.consentUrl)
    }

    @Synchronized
    fun removeContact(localId: Long) {
        if (!isEnabled) return
        val session = sessionStore.load() ?: return
        val binding = session.contacts.firstOrNull { it.localId == localId } ?: return
        revokeRemoteContact(session, binding.remoteId)
        sessionStore.save(session.copy(contacts = session.contacts.filterNot { it.localId == localId }))
    }

    @Synchronized
    fun createAlert(
        displayName: String,
        prepared: PreparedAlert,
        category: BackendAlertCategory,
    ): CloudAlertResult {
        checkEnabled()
        var session = refreshConsentStates(ensureRegistered(displayName))
        prepared.recipients.forEach { contact ->
            val binding = session.contacts.firstOrNull { it.localId == contact.id }
            if (binding == null || binding.localPhone != contact.phone) {
                if (binding != null) revokeRemoteContact(session, binding.remoteId)
                val remote = postContact(session, contact)
                session = session.copy(contacts = session.contacts.filterNot { it.localId == contact.id } + remote)
                sessionStore.save(session)
            }
        }
        val activeIds = prepared.recipients.map { it.id }.toSet()
        session.contacts.filterNot { it.localId in activeIds }.forEach { revokeRemoteContact(session, it.remoteId) }
        if (session.contacts.any { it.localId !in activeIds }) {
            session = session.copy(contacts = session.contacts.filter { it.localId in activeIds })
            sessionStore.save(session)
        }

        val location = prepared.alert.location
        val payload = JSONObject()
            .put("category", category.name)
            .put("capturedAt", location?.capturedAtEpochMillis?.let { Instant.ofEpochMilli(it).toString() })
            .put("latitude", location?.latitude)
            .put("longitude", location?.longitude)
        val response = request(
            method = "POST",
            path = "/v1/alerts",
            bearer = session.accessToken,
            headers = mapOf("Idempotency-Key" to idempotencyKey(prepared.alert.id)),
            body = payload,
        )
        return CloudAlertResult(
            alertId = response.getString("alertId"),
            authorizedRecipients = response.getInt("authorizedRecipients"),
            pendingConsentRecipients = response.getInt("pendingConsentRecipients"),
            pendingConsentUrls = session.contacts.mapNotNull { it.consentUrl }.distinct(),
        )
    }

    private fun ensureRegistered(displayName: String): BackendSession {
        sessionStore.load()?.let { return it }
        throw BackendActivationRequiredException()
    }

    private fun postContact(session: BackendSession, contact: TrustedContact): RemoteContactBinding {
        val response = request(
            method = "POST",
            path = "/v1/contacts",
            bearer = session.accessToken,
            body = JSONObject().put("name", contact.name).put("phone", contact.phone),
        )
        return RemoteContactBinding(
            localId = contact.id,
            localPhone = contact.phone,
            remoteId = response.getString("id"),
            consentStatus = response.getString("consentStatus"),
            consentUrl = response.optString("consentUrl").takeIf { it.startsWith("https://") },
        )
    }

    private fun refreshConsentStates(session: BackendSession): BackendSession {
        if (session.contacts.isEmpty()) return session
        val response = request("GET", "/v1/contacts", bearer = session.accessToken)
        val statuses = mutableMapOf<String, String>()
        response.optJSONArray("items")?.copyStatusesTo(statuses)
            ?: response.optJSONArray("data")?.copyStatusesTo(statuses)
        // Ktor serializes a top-level list. request() wraps it under `array`.
        response.optJSONArray("array")?.copyStatusesTo(statuses)
        if (statuses.isEmpty()) return session
        val updated = session.copy(contacts = session.contacts.map { binding ->
            val status = statuses[binding.remoteId] ?: binding.consentStatus
            binding.copy(consentStatus = status, consentUrl = binding.consentUrl.takeIf { status == "PENDING" })
        })
        sessionStore.save(updated)
        return updated
    }

    private fun JSONArray.copyStatusesTo(target: MutableMap<String, String>) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            target[item.optString("id")] = item.optString("consentStatus")
        }
    }

    private fun revokeRemoteContact(session: BackendSession, remoteId: String) {
        request("DELETE", "/v1/contacts/$remoteId", bearer = session.accessToken)
    }

    private fun request(
        method: String,
        path: String,
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
    ): JSONObject {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            headers.forEach(::setRequestProperty)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            body?.toString()?.toByteArray(Charsets.UTF_8)?.let { bytes ->
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val text = readBounded(if (status in 200..299) connection.inputStream else connection.errorStream)
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf(String::isNotBlank) ?: "Servidor recusou a solicitacao."
                throw SandboxBackendException(message.take(180), status)
            }
            val trimmed = text.trim()
            when {
                trimmed.isEmpty() -> JSONObject()
                trimmed.startsWith("[") -> JSONObject().put("array", JSONArray(trimmed))
                else -> JSONObject(trimmed)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        stream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) throw SandboxBackendException("Resposta do servidor muito grande.")
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun checkEnabled() = check(isEnabled) { "Backend sandbox nao configurado neste build." }

    companion object {
        private const val ACTIVATION_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"

        internal fun normalizeActivationCode(raw: CharArray): String? {
            val normalized = raw.concatToString()
                .uppercase()
                .filterNot { it == ' ' || it == '-' }
            return normalized.takeIf {
                it.length == 12 && it.all(ACTIVATION_ALPHABET::contains)
            }
        }

        internal fun formatActivationCode(raw: String): String {
            val clean = raw.uppercase().filter { it in ACTIVATION_ALPHABET }.take(12)
            return clean.chunked(4).joinToString("-")
        }

        internal fun idempotencyKey(localAlertId: Long): String {
            require(localAlertId > 0)
            return "android-alert-v1-$localAlertId"
        }

        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}
