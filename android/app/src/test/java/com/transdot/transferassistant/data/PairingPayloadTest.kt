package com.transdot.transferassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingPayloadTest {
    @Test
    fun parsesVersionOnePayload() {
        val credential = PairingPayload.parse(
            """{"v":1,"session_id":"123e4567-e89b-12d3-a456-426614174000","qr_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}""",
        )

        assertEquals("123e4567-e89b-12d3-a456-426614174000", credential.sessionId)
        assertEquals(43, credential.secret.length)
    }

    @Test
    fun rejectsUnrelatedQRCode() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.parse("https://example.com")
        }
    }

    @Test
    fun parsesVersionTwoPairingOrigin() {
        val credential = PairingPayload.parse(
            """{"v":2,"kind":"pairing","server_url":"https://transfer.example.com","instance_id":"instance-1","session_id":"123e4567-e89b-12d3-a456-426614174000","qr_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}""",
        )
        assertEquals("https://transfer.example.com", credential.serverAddress)
        assertEquals("instance-1", credential.instanceId)
    }

    @Test
    fun parsesBootstrapPayloadWithoutPermanentToken() {
        val payload = BootstrapPayload.parse(
            """{"v":2,"kind":"bootstrap","server_url":"https://transfer.example.com","instance_id":"instance-1","instance_fingerprint":"7f3a-91c2","bootstrap_session_id":"123e4567-e89b-12d3-a456-426614174000","bootstrap_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expires_at":"2099-08-20T12:00:00Z"}""",
            allowCleartext = false,
        )
        assertEquals("https://transfer.example.com", payload.serverAddress)
        assertEquals("7f3a-91c2", payload.instanceFingerprint)
    }
}
