package com.transdot.transferassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test
    fun normalizesHttpsAddress() {
        assertEquals(
            "https://transfer.example.com",
            ServerAddress.normalize(" https://transfer.example.com/ ", allowCleartext = false),
        )
    }

    @Test
    fun preservesExplicitPort() {
        assertEquals(
            "http://192.0.2.10:5757",
            ServerAddress.normalize("http://192.0.2.10:5757", allowCleartext = true),
        )
    }

    @Test
    fun rejectsHttpForReleaseBuild() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerAddress.normalize("http://192.0.2.10:5757", allowCleartext = false)
        }
    }

    @Test
    fun rejectsAddressWithPath() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerAddress.normalize("https://example.com/private", allowCleartext = false)
        }
    }
}
