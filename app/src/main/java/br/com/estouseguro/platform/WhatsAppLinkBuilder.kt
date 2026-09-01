package br.com.estouseguro.platform

import br.com.estouseguro.domain.model.BrazilianPhoneNumber
import java.net.URLEncoder

/** Builds links documented by WhatsApp's "Click to chat" feature. */
object WhatsAppLinkBuilder {
    /**
     * Converts Brazilian national numbers, or explicit `+` international numbers, to the
     * digits-only international format required by wa.me.
     */
    fun normalizeRecipient(rawPhone: String): String? =
        BrazilianPhoneNumber.normalizeForSms(rawPhone)?.removePrefix("+")

    fun build(rawPhone: String, message: String): String? {
        val recipient = normalizeRecipient(rawPhone) ?: return null
        val encodedMessage = URLEncoder.encode(message, Charsets.UTF_8.name())
            .replace("+", "%20")
        return "https://wa.me/$recipient?text=$encodedMessage"
    }
}
