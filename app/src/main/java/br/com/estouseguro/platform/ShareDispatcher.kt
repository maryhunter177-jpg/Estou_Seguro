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

    /** Opens WhatsApp with the alert text. WhatsApp always requires the user to choose/confirm recipients. */
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

    private fun shareText(context: Context, message: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar alerta"))
    }
}
