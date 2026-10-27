package com.ouor.pocketboard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ouor.pocketboard.core.gesture.GestureConfig
import com.ouor.pocketboard.core.gesture.HapticSettings
import com.ouor.pocketboard.core.gesture.HapticStrength
import com.ouor.pocketboard.core.ink.InkSettings
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.keymap.CommitMethod
import com.ouor.pocketboard.core.keymap.HostOs
import com.ouor.pocketboard.core.keymap.ImeToggleKey
import com.ouor.pocketboard.core.keymap.KeymapSettings
import com.ouor.pocketboard.core.keymap.LatinMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 앱 설정 저장소(DataStore 파일 "settings"). 진실의 출처는 파일이고 [settings]는 그 사본이다. */
class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.settingsStore

    private val data = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(SettingsCodec::decode)

    /** 파일을 읽기 전에는 기본값이다. 첫 화면 분기처럼 실제 값이 꼭 필요하면 [load]. */
    val settings: StateFlow<AppSettings> = data.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun load(): AppSettings = data.first()

    /** 읽고 고쳐 쓴다(트랜잭션). 예: `update { it.copy(emptySendEnter = false) }`. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> SettingsCodec.encode(transform(SettingsCodec.decode(prefs)), prefs) }
    }
}

/** 키 이름이 저장 형식이다. 바꾸지 않는다. enum 은 id 로 저장하고 모르는 id 는 기본값. */
internal object SettingsCodec {
    private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    private val IDLE_TIMEOUT_MIN = intPreferencesKey("idle_timeout_min")
    private val EMPTY_SEND_ENTER = booleanPreferencesKey("empty_send_enter")
    private val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    private val HAPTICS_STRENGTH = stringPreferencesKey("haptics_strength")
    private val PREVIEW_ENABLED = booleanPreferencesKey("preview_enabled")
    private val HOST_OS = stringPreferencesKey("host_os")
    private val IME_TOGGLE = stringPreferencesKey("keymap_ime_toggle")
    private val COMMIT = stringPreferencesKey("keymap_commit")
    private val LATIN = stringPreferencesKey("keymap_latin")
    private val SEGMENTATION = stringPreferencesKey("ink_segmentation")
    private val TAP_SLOP = floatPreferencesKey("gesture_tap_slop_dp")
    private val TAP_MAX = longPreferencesKey("gesture_tap_max_ms")
    private val MULTI_TAP_GAP = longPreferencesKey("gesture_multi_tap_gap_ms")
    private val MULTI_TAP_RADIUS = floatPreferencesKey("gesture_multi_tap_radius_dp")
    private val MULTI_DOWN_WINDOW = longPreferencesKey("gesture_multi_down_window_ms")
    private val SWIPE_MIN = floatPreferencesKey("gesture_swipe_min_dp")
    private val SWIPE_AXIS_RATIO = floatPreferencesKey("gesture_swipe_axis_ratio")
    private val PALM_MAJOR = floatPreferencesKey("gesture_palm_touch_major_dp")
    private val EDGE_BAND = floatPreferencesKey("gesture_edge_band_dp")
    private val DEV_OPTIONS_VISIBLE = booleanPreferencesKey("dev_options_visible")
    private val DEVLOG_ENABLED = booleanPreferencesKey("devlog_enabled")

    fun decode(p: Preferences): AppSettings {
        val d = AppSettings()
        val k = d.keymap
        val g = d.gesture
        return AppSettings(
            onboardingDone = p[ONBOARDING_DONE] ?: d.onboardingDone,
            idleTimeoutMin = p[IDLE_TIMEOUT_MIN] ?: d.idleTimeoutMin,
            emptySendEnter = p[EMPTY_SEND_ENTER] ?: d.emptySendEnter,
            haptics = HapticSettings(
                enabled = p[HAPTICS_ENABLED] ?: d.haptics.enabled,
                strength = HapticStrength.entries.find { it.id == p[HAPTICS_STRENGTH] } ?: d.haptics.strength,
            ),
            previewEnabled = p[PREVIEW_ENABLED] ?: d.previewEnabled,
            keymap = KeymapSettings(
                os = HostOs.entries.find { it.id == p[HOST_OS] } ?: k.os,
                imeToggle = ImeToggleKey.entries.find { it.id == p[IME_TOGGLE] } ?: k.imeToggle,
                commit = CommitMethod.entries.find { it.id == p[COMMIT] } ?: k.commit,
                latin = LatinMethod.entries.find { it.id == p[LATIN] } ?: k.latin,
            ),
            ink = InkSettings(SegmentationMode.entries.find { it.id == p[SEGMENTATION] } ?: d.ink.segmentation),
            gesture = GestureConfig(
                tapSlopDp = p[TAP_SLOP] ?: g.tapSlopDp,
                tapMaxMs = p[TAP_MAX] ?: g.tapMaxMs,
                multiTapGapMs = p[MULTI_TAP_GAP] ?: g.multiTapGapMs,
                multiTapRadiusDp = p[MULTI_TAP_RADIUS] ?: g.multiTapRadiusDp,
                multiDownWindowMs = p[MULTI_DOWN_WINDOW] ?: g.multiDownWindowMs,
                swipeMinDp = p[SWIPE_MIN] ?: g.swipeMinDp,
                swipeAxisRatio = p[SWIPE_AXIS_RATIO] ?: g.swipeAxisRatio,
                palmTouchMajorDp = p[PALM_MAJOR] ?: g.palmTouchMajorDp,
                edgeBandDp = p[EDGE_BAND] ?: g.edgeBandDp,
            ),
            devOptionsVisible = p[DEV_OPTIONS_VISIBLE] ?: d.devOptionsVisible,
            devlogEnabled = p[DEVLOG_ENABLED] ?: d.devlogEnabled,
        )
    }

    fun encode(s: AppSettings, p: MutablePreferences) {
        p[ONBOARDING_DONE] = s.onboardingDone
        p[IDLE_TIMEOUT_MIN] = s.idleTimeoutMin
        p[EMPTY_SEND_ENTER] = s.emptySendEnter
        p[HAPTICS_ENABLED] = s.haptics.enabled
        p[HAPTICS_STRENGTH] = s.haptics.strength.id
        p[PREVIEW_ENABLED] = s.previewEnabled
        p[HOST_OS] = s.keymap.os.id
        p[IME_TOGGLE] = s.keymap.imeToggle.id
        p[COMMIT] = s.keymap.commit.id
        p[LATIN] = s.keymap.latin.id
        p[SEGMENTATION] = s.ink.segmentation.id
        p[TAP_SLOP] = s.gesture.tapSlopDp
        p[TAP_MAX] = s.gesture.tapMaxMs
        p[MULTI_TAP_GAP] = s.gesture.multiTapGapMs
        p[MULTI_TAP_RADIUS] = s.gesture.multiTapRadiusDp
        p[MULTI_DOWN_WINDOW] = s.gesture.multiDownWindowMs
        p[SWIPE_MIN] = s.gesture.swipeMinDp
        p[SWIPE_AXIS_RATIO] = s.gesture.swipeAxisRatio
        p[PALM_MAJOR] = s.gesture.palmTouchMajorDp
        p[EDGE_BAND] = s.gesture.edgeBandDp
        p[DEV_OPTIONS_VISIBLE] = s.devOptionsVisible
        p[DEVLOG_ENABLED] = s.devlogEnabled
    }
}
