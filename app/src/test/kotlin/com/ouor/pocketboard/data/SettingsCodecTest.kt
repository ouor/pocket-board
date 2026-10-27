package com.ouor.pocketboard.data

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.ouor.pocketboard.core.ink.rules.InkConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun inkConfigRoundTrips() {
        val s = AppSettings(ink = AppSettings().ink.copy(config = InkConfig(restartPauseMs = 425, rightOfGapRatio = 1.5f)))
        val p = mutablePreferencesOf()
        SettingsCodec.encode(s, p)
        assertEquals(s, SettingsCodec.decode(p))
    }

    @Test
    fun outOfRangeInkValuesAreClampedInsteadOfThrowing() {
        val p = mutablePreferencesOf(
            longPreferencesKey("ink_restart_pause_ms") to 99_999L,
            floatPreferencesKey("ink_restart_overlap_ratio") to -1f,
        )
        val c = SettingsCodec.decode(p).ink.config
        assertEquals(2000L, c.restartPauseMs)
        assertEquals(0f, c.restartOverlapRatio)
    }

    @Test
    fun outOfRangeGestureValuesAreClamped() {
        val p = mutablePreferencesOf(longPreferencesKey("gesture_tap_max_ms") to 5L, floatPreferencesKey("gesture_swipe_axis_ratio") to 0.2f)
        val g = SettingsCodec.decode(p).gesture
        assertEquals(50L, g.tapMaxMs)
        assertEquals(1f, g.swipeAxisRatio)
    }
}
