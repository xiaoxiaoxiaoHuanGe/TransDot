package com.transdot.transferassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RebindPayloadTest {
    @Test
    fun parsesSecureRebindPayload() {
        val payload = RebindPayload.parse(
            """{"v":2,"kind":"rebind","server_url":"https://transfer.example.com","instance_id":"instance-2","instance_fingerprint":"7f3a-91c2","rebind_session_id":"123e4567-e89b-12d3-a456-426614174000","rebind_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expires_at":"2099-08-20T12:00:00Z"}""",
            allowCleartext = false,
        )
        assertEquals("https://transfer.example.com", payload.serverAddress)
        assertEquals("instance-2", payload.instanceId)
    }

    @Test
    fun claimResponseMustMatchScannedServerIdentity() {
        val payload = RebindPayload.parse(
            """{"v":2,"kind":"rebind","server_url":"https://transfer.example.com","instance_id":"instance-2","instance_fingerprint":"7f3a-91c2","rebind_session_id":"123e4567-e89b-12d3-a456-426614174000","rebind_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expires_at":"2099-08-20T12:00:00Z"}""",
            allowCleartext = false,
        )

        val session = parseRebindClaimResponse(
            """{"device_id":"master-2","master_token":"secret","instance_id":"instance-2","instance_fingerprint":"7f3a-91c2"}""",
            payload,
        )
        assertEquals("master-2", session.deviceId)
        assertEquals("instance-2", session.instanceId)

        assertThrows(RebindFailure.Invalid::class.java) {
            parseRebindClaimResponse(
                """{"device_id":"master-2","master_token":"secret","instance_id":"other","instance_fingerprint":"7f3a-91c2"}""",
                payload,
            )
        }
        assertThrows(RebindFailure.Invalid::class.java) {
            parseRebindClaimResponse(
                """{"device_id":"master-2","master_token":"secret","instance_id":"instance-2","instance_fingerprint":"0000-0000"}""",
                payload,
            )
        }
    }
}
