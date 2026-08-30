package com.transdot.transferassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineSourceLabelTest {
    @Test
    fun `uses browser display name when available`() {
        assertEquals("书房电脑", timelineSourceLabel("windows_browser", "书房电脑"))
    }

    @Test
    fun `keeps compatible fallback labels`() {
        assertEquals("Android", timelineSourceLabel("android_master", null))
        assertEquals("浏览器", timelineSourceLabel("windows_browser", "  "))
    }
}
