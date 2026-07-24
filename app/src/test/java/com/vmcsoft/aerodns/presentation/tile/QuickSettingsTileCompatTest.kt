package com.vmcsoft.aerodns.presentation.tile

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSettingsTileCompatTest {

    @Test
    fun `supportsSubtitle is false below API 29`() {
        assertFalse(QuickSettingsTileCompat.supportsSubtitle(Build.VERSION_CODES.P))
        assertFalse(QuickSettingsTileCompat.supportsSubtitle(Build.VERSION_CODES.O_MR1))
        assertFalse(QuickSettingsTileCompat.supportsSubtitle(Build.VERSION_CODES.N))
    }

    @Test
    fun `supportsSubtitle is true from API 29`() {
        assertTrue(QuickSettingsTileCompat.supportsSubtitle(Build.VERSION_CODES.Q))
        assertTrue(QuickSettingsTileCompat.supportsSubtitle(Build.VERSION_CODES.TIRAMISU))
    }
}
