package com.ouor.pocketboard.data

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.keymap.LatinMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun inkConfigRoundTrips() {
        val s = AppSettings(ink = AppSettings().ink.copy(config = InkConfig(restartPauseMs = 425, newCharPauseMs = 900, rightOfGapRatio = 1.5f, topLeftCornerRatio = 0.35f, topLeftBackRatio = 0.6f, stackedMinStrokes = 12)))
        val p = mutablePreferencesOf()
        SettingsCodec.encode(s, p)
        assertEquals(s, SettingsCodec.decode(p))
    }

    @Test
    fun outOfRangeInkValuesAreClampedInsteadOfThrowing() {
        val p = mutablePreferencesOf(
            longPreferencesKey("ink_restart_pause_ms") to 99_999L,
            longPreferencesKey("ink_new_char_pause_ms_v2") to 99_999L,
            floatPreferencesKey("ink_restart_overlap_ratio") to -1f,
        )
        val c = SettingsCodec.decode(p).ink.config
        assertEquals(2000L, c.restartPauseMs)
        assertEquals(3000L, c.newCharPauseMs)
        assertEquals(0f, c.restartOverlapRatio)
    }

    @Test
    fun oldStoredNewCharPauseDefaultIsDropped() {
        val p = mutablePreferencesOf(longPreferencesKey("ink_new_char_pause_ms") to 600L)
        val s = SettingsCodec.decode(p)
        assertEquals(500L, s.ink.config.newCharPauseMs)
        SettingsCodec.encode(s.copy(ink = s.ink.copy(config = s.ink.config.copy(newCharPauseMs = 700))), p)
        assertEquals(null, p[longPreferencesKey("ink_new_char_pause_ms")])
        assertEquals(700L, SettingsCodec.decode(p).ink.config.newCharPauseMs) // 새로 고른 값은 유지
    }

    @Test
    fun oldStoredRightOfGapDefaultIsDropped() {
        val p = mutablePreferencesOf(floatPreferencesKey("ink_right_of_gap_ratio") to 0.3f)
        val s = SettingsCodec.decode(p)
        assertEquals(2f, s.ink.config.rightOfGapRatio)
        SettingsCodec.encode(s, p)
        assertEquals(null, p[floatPreferencesKey("ink_right_of_gap_ratio")])
    }

    @Test
    fun oldStoredCapsLockDefaultIsDroppedForModeSwitch() {
        val p = mutablePreferencesOf(stringPreferencesKey("keymap_latin") to "caps_lock")
        val s = SettingsCodec.decode(p)
        assertEquals(LatinMethod.MODE_SWITCH, s.keymap.latin)
        SettingsCodec.encode(s.copy(keymap = s.keymap.copy(latin = LatinMethod.CAPS_LOCK)), p)
        assertEquals(null, p[stringPreferencesKey("keymap_latin")])
        assertEquals(LatinMethod.CAPS_LOCK, SettingsCodec.decode(p).keymap.latin) // 새로 고른 값은 유지
    }

    @Test
    fun outOfRangeGestureValuesAreClamped() {
        val p = mutablePreferencesOf(longPreferencesKey("gesture_tap_max_ms") to 5L, floatPreferencesKey("gesture_swipe_axis_ratio") to 0.2f)
        val g = SettingsCodec.decode(p).gesture
        assertEquals(50L, g.tapMaxMs)
        assertEquals(1f, g.swipeAxisRatio)
    }
}
