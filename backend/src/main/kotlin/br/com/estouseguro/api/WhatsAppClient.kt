package br.com.estouseguro.api

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson

class WhatsAppClient(private val config: MetaConfig) : AutoCloseable {
    private val client = HttpClient(CIO) { install(ContentNegotiation) { jackson() } }

    suspend fun send(job: DeliveryJob): String {
        val location = if (job.latitude != null && job.longitude != null)
            "https://maps.google.com/?q=${job.latitude},${job.longitude}" else "Localização indisponível"
        val parameters = listOf(job.ownerDisplayName, categoryLabel(job.category), location).map {
            mapOf("type" to "text", "text" to it.take(1024))
        }
        val payload = mapOf(
            "messaging_product" to "whatsapp",
            "recipient_type" to "individual",
            "to" to job.recipientPhone.filter(Char::isDigit),
            "type" to "template",
            "template" to mapOf(
                "name" to config.templateName,
                "language" to mapOf("code" to config.templateLanguage),
                "components" to listOf(mapOf("type" to "body", "parameters" to parameters)),
            ),
        )
        val response = client.post("https://graph.facebook.com/${config.graphVersion}/${config.phoneNumberId}/messages") {
            bearerAuth(config.accessToken); contentType(ContentType.Application.Json); setBody(payload)
        }
        val body: JsonNode = response.body()
        if (!response.status.isSuccess()) {
            val code = body.path("error").path("code").asText("unknown")
            throw IllegalStateException("Meta recusou o envio (código $code)")
        }
        return body.path("messages").path(0).path("id").asText().takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Meta não retornou o identificador da mensagem")
    }

    override fun close() = client.close()
}

fun categoryLabel(category: AlertCategory): String = when (category) {
    AlertCategory.GENERAL -> "SOS: preciso de ajuda"
    AlertCategory.MEDICAL -> "Emergência médica"
    AlertCategory.SECURITY -> "Roubo, sequestro ou risco"
    AlertCategory.DOMESTIC_VIOLENCE -> "Violência contra a mulher"
    AlertCategory.CHILD_DANGER -> "Criança ou adolescente em risco"
    AlertCategory.ANXIETY -> "Crise de ansiedade"
}
