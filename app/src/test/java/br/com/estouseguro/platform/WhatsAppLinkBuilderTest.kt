package br.com.estouseguro.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppLinkBuilderTest {
    @Test
    fun `adds Brazil country code to a national mobile number`() {
        assertEquals(
            "5533996667145",
            WhatsAppLinkBuilder.normalizeRecipient("(33) 99666-7145"),
        )
    }

    @Test
    fun `keeps a valid explicit international number`() {
        assertEquals(
            "351912345678",
            WhatsAppLinkBuilder.normalizeRecipient("+351 912 345 678"),
        )
    }

    @Test
    fun `rejects number without area or country code`() {
        assertNull(WhatsAppLinkBuilder.normalizeRecipient("99666-7145"))
    }

    @Test
    fun `rejects ten digit number that looks like a mobile missing its ninth digit`() {
        assertNull(WhatsAppLinkBuilder.normalizeRecipient("33 9966-7145"))
    }

    @Test
    fun `builds click to chat link with encoded alert`() {
        val link = WhatsAppLinkBuilder.build("+55 (33) 99666-7145", "SOS: localização atual")

        assertTrue(link!!.startsWith("https://wa.me/5533996667145?text="))
        assertTrue(link.contains("SOS%3A%20localiza%C3%A7%C3%A3o%20atual"))
    }
}
