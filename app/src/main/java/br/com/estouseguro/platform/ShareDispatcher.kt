package br.com.estouseguro.platform

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import br.com.estouseguro.domain.model.TrustedContact

object ShareDispatcher {
    fun emergency(context: Context, message: String, contacts: List<TrustedContact>) {
        val numbers = contacts.joinToString(separator = ";") { Uri.encode(it.phone) }
        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$numbers")
            putExtra("sms_body", message)
        }
        if (smsIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(smsIntent)
        } else {
            shareText(context, message)
        }
    }

    fun checkIn(context: Context, displayName: String, contacts: List<TrustedContact>) {
        val message = "$displayName fez check-in no Estou Seguro: cheguei bem."
        emergency(context, message, contacts)
    }

    /** Opens WhatsApp's generic share flow. The user must choose and confirm a recipient. */
    fun whatsApp(context: Context, message: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, message)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            shareText(context, message)
        }
    }

    /**
     * Opens the conversation for a specific registered contact with the alert pre-filled.
     * WhatsApp's public personal-app integration does not provide a supported silent-send action,
     * so the user still confirms the final send inside WhatsApp.
     */
    fun whatsApp(context: Context, message: String, contact: TrustedContact) {
        val link = WhatsAppLinkBuilder.build(contact.phone, message)
        if (link == null) {
            shareText(context, message)
            return
        }

        val uri = Uri.parse(link)
        if (startWhatsAppPackage(context, uri, "com.whatsapp")) return
        if (startWhatsAppPackage(context, uri, "com.whatsapp.w4b")) return

        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            shareText(context, message)
        }
    }

    private fun startWhatsAppPackage(context: Context, uri: Uri, packageName: String): Boolean =
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(packageName))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    private fun shareText(context: Context, message: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar alerta"))
    }
}
