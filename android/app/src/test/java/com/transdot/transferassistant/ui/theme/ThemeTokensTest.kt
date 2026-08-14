package com.transdot.transferassistant.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTokensTest {
    @Test
    fun typographyUsesReadableChineseScale() {
        assertEquals(32f, AppTypography.headlineLarge.fontSize.value, 0f)
        assertEquals(24f, AppTypography.headlineMedium.fontSize.value, 0f)
        assertEquals(16f, AppTypography.bodyLarge.fontSize.value, 0f)
        assertTrue(AppTypography.bodyLarge.lineHeight.value >= 24f)
        assertTrue(AppTypography.bodyMedium.lineHeight.value >= 20f)
    }

    @Test
    fun motionTokensKeepPolishRestrained() {
        assertEquals(160, AppMotion.fastMillis)
        assertEquals(240, AppMotion.normalMillis)
        assertEquals(320, AppMotion.emphasisMillis)
        assertTrue(AppMotion.fastMillis < AppMotion.normalMillis)
        assertTrue(AppMotion.normalMillis < AppMotion.emphasisMillis)
    }
}
