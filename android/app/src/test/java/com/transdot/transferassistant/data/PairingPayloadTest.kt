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
}
