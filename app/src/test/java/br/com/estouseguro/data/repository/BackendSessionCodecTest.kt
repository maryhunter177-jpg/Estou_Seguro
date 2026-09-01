package br.com.estouseguro.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendSessionCodecTest {
    @Test
    fun `round trips encrypted session plaintext structure`() {
        val expected = BackendSession(
            deviceId = "123e4567-e89b-12d3-a456-426614174000",
            accessToken = "abcdefghijklmnopqrstuvwxyz_ABCDEFG-0123456789",
            contacts = listOf(
                RemoteContactBinding(
                    localId = 7,
                    localPhone = "+5533999999999",
                    remoteId = "123e4567-e89b-12d3-a456-426614174001",
                    consentStatus = "PENDING",
                    consentUrl = "https://estou-seguro-api-sandbox.onrender.com/consent/abcdefghijklmnopqrstuvwxyz_ABCDEFG-0123456789",
                ),
            ),
        )

        assertEquals(expected, BackendSessionCodec.decode(BackendSessionCodec.encode(expected)))
    }

    @Test
    fun `rejects invalid bearer token from persisted payload`() {
        val invalid = BackendSession(
            deviceId = "123e4567-e89b-12d3-a456-426614174000",
            accessToken = "short",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackendSessionCodec.decode(BackendSessionCodec.encode(invalid))
        }
    }
}
