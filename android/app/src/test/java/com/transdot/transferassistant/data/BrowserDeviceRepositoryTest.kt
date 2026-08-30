package com.transdot.transferassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.ZoneId

class BrowserDeviceRepositoryTest {
    @Test
    fun parsesDeviceListAndNullLastSeen() {
        val response = parseBrowserDevicesResponse(
            """{"devices":[{"id":"browser-1","display_name":"Office","created_at":"2026-08-30T10:00:00Z","last_seen_at":null}],"active_count":1,"maximum_count":10}""",
        )
        assertEquals(1, response.devices.size)
        assertEquals("Office", response.devices.single().displayName)
        assertNull(response.devices.single().lastSeenAt)
        assertEquals(10, response.maximumCount)
    }

    @Test
    fun formatsAuthorizationDateInLocalZone() {
        assertEquals(
            "2026-08-30 18:00",
            browserAuthorizedLabel("2026-08-30T10:00:00Z", ZoneId.of("Asia/Shanghai")),
        )
    }

    @Test
    fun oldServerSpaFallbackIsTreatedAsUnsupported() {
        assertThrows(BrowserDeviceFailure.Unsupported::class.java) {
            parseBrowserDevicesResponseOrUnsupported("<!doctype html><title>TransDot</title>")
        }
    }

    @Test
    fun formatsRecentUsageForNeverTodayAndOlderDates() {
        val utc = ZoneId.of("UTC")
        assertEquals("刚刚授权", browserLastSeenLabel(null, "2026-08-30T12:00:00Z", utc))
        assertEquals("今天 11:20", browserLastSeenLabel("2026-08-30T11:20:00Z", "2026-08-30T12:00:00Z", utc))
        assertEquals("2026-08-29", browserLastSeenLabel("2026-08-29T11:20:00Z", "2026-08-30T12:00:00Z", utc))
    }
}
