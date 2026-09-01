package br.com.estouseguro.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.jackson.*
import java.security.MessageDigest
import java.util.UUID

fun main() {
    val config = AppConfig.fromEnvironment()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config) }.start(wait = true)
}

fun Application.module(config: AppConfig) {
    val appLog = environment.log
    val dataSource = DatabaseFactory.create(config)
    MigrationRunner(dataSource).migrate()
    val repository = AppRepository(dataSource, config)
    val mapper = ObjectMapper().registerModule(JavaTimeModule()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    val worker = if (config.workerEnabled) QueueWorker(repository, WhatsAppClient(config.meta)).also { it.start() } else null
    monitor.subscribe(ApplicationStopped) { worker?.close(); dataSource.close() }

    install(ForwardedHeaders)
    install(CallId) { generate { UUID.randomUUID().toString() }; replyToHeader("X-Request-ID") }
    install(CallLogging) { callIdMdc("requestId") }
    install(ContentNegotiation) { jackson { registerModule(JavaTimeModule()); disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) } }
    install(StatusPages) {
        exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.status), ErrorResponse(error.code, error.message, call.callId)) }
        exception<IllegalArgumentException> { call, error -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_REQUEST", error.message ?: "Requisição inválida", call.callId)) }
        exception<Throwable> { call, error ->
            appLog.error("Unhandled request error", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", "Não foi possível concluir a solicitação", call.callId))
        }
    }
    install(Authentication) { bearer("device") { authenticate { repository.authenticate(it.token) } } }

    routing {
        get("/health/live") { call.respond(mapOf("status" to "UP")) }
        get("/health/ready") { if (repository.ready()) call.respond(mapOf("status" to "UP")) else call.respond(HttpStatusCode.ServiceUnavailable) }

        post("/ops/v1/activation-codes") {
            if (config.isProduction) throw ApiException(404, "NOT_FOUND", "Rota indisponível")
            val supplied = call.request.header("X-Sandbox-Registration-Key").orEmpty()
            if (!constantEquals(supplied, config.sandboxRegistrationKey))
                throw ApiException(401, "UNAUTHORIZED", "Chave operacional inválida")
            call.respond(HttpStatusCode.Created, repository.issueActivationCode())
        }

        post("/v1/devices/register") {
            if (config.isProduction) throw ApiException(404, "NOT_FOUND", "Rota indisponível")
            val activationCode = call.request.header("X-Sandbox-Activation-Code").orEmpty()
            call.respond(
                HttpStatusCode.Created,
                repository.registerDevice(call.receive<RegisterDeviceRequest>().displayName, activationCode),
            )
        }

        authenticate("device") {
            route("/v1/contacts") {
                get { call.respond(repository.listContacts(call.principal<DevicePrincipal>()!!.deviceId)) }
                post { call.respond(HttpStatusCode.Created, repository.saveContact(call.principal<DevicePrincipal>()!!.deviceId, call.receive<ContactRequest>())) }
                delete("/{id}") {
                    val contactId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: throw ApiException(404, "CONTACT_NOT_FOUND", "Contato não encontrado")
                    if (!repository.revokeContact(call.principal<DevicePrincipal>()!!.deviceId, contactId))
                        throw ApiException(404, "CONTACT_NOT_FOUND", "Contato não encontrado")
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            post("/v1/alerts") {
                val key = call.request.header("Idempotency-Key") ?: throw ApiException(400, "MISSING_IDEMPOTENCY_KEY", "Envie o cabeçalho Idempotency-Key")
                call.respond(HttpStatusCode.Accepted, repository.createAlert(call.principal<DevicePrincipal>()!!.deviceId, key, call.receive<CreateAlertRequest>()))
            }
            get("/v1/alerts/{id}") {
                val alertId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: throw ApiException(404, "ALERT_NOT_FOUND", "Alerta não encontrado")
                call.respond(repository.getAlertStatus(call.principal<DevicePrincipal>()!!.deviceId, alertId))
            }
        }

        get("/consent/{token}") {
            val token = call.parameters["token"].orEmpty()
            if (!token.matches(Regex("[A-Za-z0-9_-]{40,64}"))) throw ApiException(404, "INVALID_LINK", "Link inválido ou expirado")
            val status = repository.consentStatus(token)
                ?: throw ApiException(404, "INVALID_LINK", "Link inválido ou expirado")
            call.respondText(consentPage(token, status), ContentType.Text.Html)
        }
        post("/consent/{token}") {
            val token = call.parameters["token"].orEmpty()
            if (!token.matches(Regex("[A-Za-z0-9_-]{40,64}")) || !repository.grantConsent(token))
                throw ApiException(404, "INVALID_LINK", "Link inválido, expirado ou já utilizado")
            call.respondText("<html lang='pt-BR'><meta charset='utf-8'><title>Consentimento confirmado</title><body><h1>Consentimento confirmado</h1><p>Você poderá receber alertas de emergência do Estou Seguro.</p></body></html>", ContentType.Text.Html)
        }
        post("/consent/{token}/revoke") {
            val token = call.parameters["token"].orEmpty()
            if (!token.matches(Regex("[A-Za-z0-9_-]{40,64}")) || !repository.revokeConsent(token))
                throw ApiException(404, "INVALID_LINK", "Link inválido, expirado ou já revogado")
            call.respondText("<html lang='pt-BR'><meta charset='utf-8'><title>Consentimento revogado</title><body><h1>Consentimento revogado</h1><p>Este contato não receberá novos alertas do Estou Seguro.</p></body></html>", ContentType.Text.Html)
        }

        get("/webhooks/whatsapp") {
            val valid = call.request.queryParameters["hub.mode"] == "subscribe" &&
                constantEquals(call.request.queryParameters["hub.verify_token"].orEmpty(), config.meta.webhookVerifyToken)
            if (!valid) throw ApiException(403, "WEBHOOK_REJECTED", "Verificação recusada")
            call.respondText(call.request.queryParameters["hub.challenge"].orEmpty(), ContentType.Text.Plain)
        }
        post("/webhooks/whatsapp") {
            if (config.meta.appSecret.isBlank())
                throw ApiException(503, "WEBHOOK_DISABLED", "Webhook ainda não configurado")
            val bytes = call.receive<ByteArray>()
            if (!SecureTokens.verifyHexHmac(call.request.header("X-Hub-Signature-256"), config.meta.appSecret, bytes))
                throw ApiException(401, "INVALID_SIGNATURE", "Assinatura inválida")
            processWebhook(mapper.readTree(bytes), repository)
            call.respondText("EVENT_RECEIVED", ContentType.Text.Plain)
        }
    }
}

private fun processWebhook(root: JsonNode, repository: AppRepository) {
    root.path("entry").forEach { entry -> entry.path("changes").forEach { change ->
        change.path("value").path("statuses").forEach { status ->
            val messageId = status.path("id").asText()
            val state = status.path("status").asText().uppercase()
            val timestamp = status.path("timestamp").asText()
            if (messageId.isNotBlank()) {
                val error = status.path("errors").firstOrNull()?.path("code")?.asText()
                repository.applyProviderEvent("$messageId:$state:$timestamp", messageId, state, error)
            }
        }
    } }
}

private fun constantEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

internal fun consentPage(token: String, status: String) = when (status) {
    "PENDING" -> """<!doctype html><html lang="pt-BR"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Estou Seguro</title><style>body{font:18px system-ui;max-width:38rem;margin:4rem auto;padding:1.5rem;color:#102638}button{background:#0b4668;color:white;border:0;border-radius:12px;padding:1rem 1.4rem;font-weight:700}</style><body><h1>Autorizar alertas</h1><p>Ao confirmar, você autoriza o Estou Seguro a enviar mensagens de emergência pelo WhatsApp. O mesmo link permite revogar a autorização posteriormente.</p><form method="post" action="/consent/$token"><button type="submit">AUTORIZAR ALERTAS</button></form></body></html>"""
    "GRANTED" -> """<!doctype html><html lang="pt-BR"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Estou Seguro</title><style>body{font:18px system-ui;max-width:38rem;margin:4rem auto;padding:1.5rem;color:#102638}button{background:#9f1d25;color:white;border:0;border-radius:12px;padding:1rem 1.4rem;font-weight:700}</style><body><h1>Alertas autorizados</h1><p>Você autorizou alertas de emergência. É possível revogar essa autorização agora.</p><form method="post" action="/consent/$token/revoke"><button type="submit">REVOGAR AUTORIZAÇÃO</button></form></body></html>"""
    else -> """<!doctype html><html lang="pt-BR"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Estou Seguro</title><body><h1>Consentimento revogado</h1><p>Este contato não receberá novos alertas.</p></body></html>"""
}
