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
import com.ouor.pocketboard.core.gesture.engine.coerced
import com.ouor.pocketboard.core.ink.InkSettings
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.rules.InkConfig
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
    private val ONBOARDING_STEP = intPreferencesKey("onboarding_step")
    private val IDLE_TIMEOUT_MIN = intPreferencesKey("idle_timeout_min")
    private val EMPTY_SEND_ENTER = booleanPreferencesKey("empty_send_enter")
    private val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    private val HAPTICS_STRENGTH = stringPreferencesKey("haptics_strength")
    private val PREVIEW_ENABLED = booleanPreferencesKey("preview_enabled")
    private val HOST_OS = stringPreferencesKey("host_os")
    private val IME_TOGGLE = stringPreferencesKey("keymap_ime_toggle")
    private val COMMIT = stringPreferencesKey("keymap_commit")
    // v2: 옛 키에는 encode 가 늘 써 둔 옛 기본값 caps_lock 이 남아 있어 사용자 선택과 구분이 안 된다. 버리고 새 기본값(MODE_SWITCH)을 쓴다.
    private val LATIN = stringPreferencesKey("keymap_latin_v2")
    private val LATIN_V1 = stringPreferencesKey("keymap_latin")
    private val SEGMENTATION = stringPreferencesKey("ink_segmentation")
    private val INK_RESTART_PAUSE = longPreferencesKey("ink_restart_pause_ms")
    // v2: encode 가 늘 써 둔 옛 기본값 600 이 현장 575ms 글자 경계를 놓쳤다. 버리고 새 기본값(500)을 쓴다.
    private val INK_NEW_CHAR_PAUSE = longPreferencesKey("ink_new_char_pause_ms_v2")
    private val INK_NEW_CHAR_PAUSE_V1 = longPreferencesKey("ink_new_char_pause_ms")
    private val INK_RESTART_OVERLAP = floatPreferencesKey("ink_restart_overlap_ratio")
    private val INK_RESTART_MARGIN = floatPreferencesKey("ink_restart_margin_ratio")
    // v2: encode 가 늘 써 둔 옛 기본값 0.3 이 빠른 줄 쓰기에서 줄 첫 자음을 잘랐다. 버리고 새 기본값(2.0)을 쓴다.
    private val INK_RIGHT_OF_GAP = floatPreferencesKey("ink_right_of_gap_ratio_v2")
    private val INK_RIGHT_OF_GAP_V1 = floatPreferencesKey("ink_right_of_gap_ratio")
    private val INK_TOP_LEFT_CORNER = floatPreferencesKey("ink_top_left_corner_ratio")
    private val INK_TOP_LEFT_BACK = floatPreferencesKey("ink_top_left_back_ratio")
    private val INK_RELAYOUT_GAP = floatPreferencesKey("ink_relayout_gap_ratio")
    private val INK_MIN_CHAR_SIZE = floatPreferencesKey("ink_min_char_size_dp")
    private val INK_MAX_HYPOTHESIS = intPreferencesKey("ink_max_hypothesis_calls")
    private val INK_SPLIT_STROKE_RATIO = floatPreferencesKey("ink_split_stroke_ratio")
    private val INK_MAX_SPLIT = intPreferencesKey("ink_max_split_calls")
    private val INK_STACKED_MIN = intPreferencesKey("ink_stacked_min_strokes")
    private val TAP_SLOP = floatPreferencesKey("gesture_tap_slop_dp")
    private val TAP_MAX = longPreferencesKey("gesture_tap_max_ms")
    private val MULTI_TAP_GAP = longPreferencesKey("gesture_multi_tap_gap_ms")
    private val MULTI_TAP_RADIUS = floatPreferencesKey("gesture_multi_tap_radius_dp")
    private val MULTI_DOWN_WINDOW = longPreferencesKey("gesture_multi_down_window_ms")
    private val SWIPE_MIN = floatPreferencesKey("gesture_swipe_min_dp")
    private val SWIPE_AXIS_RATIO = floatPreferencesKey("gesture_swipe_axis_ratio")
    // v2: encode 가 늘 써 둔 옛 기본값 18 이 모든 손가락 접촉을 버렸다. 버리고 새 기본값(0=끔)을 쓴다.
    private val PALM_MAJOR = floatPreferencesKey("gesture_palm_touch_major_dp_v2")
    private val PALM_MAJOR_V1 = floatPreferencesKey("gesture_palm_touch_major_dp")
    private val EDGE_BAND = floatPreferencesKey("gesture_edge_band_dp")
    private val DEV_OPTIONS_VISIBLE = booleanPreferencesKey("dev_options_visible")
    private val DEVLOG_ENABLED = booleanPreferencesKey("devlog_enabled")

    fun decode(p: Preferences): AppSettings {
        val d = AppSettings()
        val k = d.keymap
        val g = d.gesture
        val i = d.ink.config
        return AppSettings(
            onboardingDone = p[ONBOARDING_DONE] ?: d.onboardingDone,
            onboardingStep = p[ONBOARDING_STEP] ?: d.onboardingStep,
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
            // 범위 밖 값은 InkConfig 생성 시 예외(ink.md §3.1)이므로 읽을 때 범위로 자른다
            ink = InkSettings(
                segmentation = SegmentationMode.entries.find { it.id == p[SEGMENTATION] } ?: d.ink.segmentation,
                config = InkConfig(
                    minCharSize = (p[INK_MIN_CHAR_SIZE] ?: i.minCharSize).coerceIn(InkRanges.minCharSize),
                    rightOfGapRatio = (p[INK_RIGHT_OF_GAP] ?: i.rightOfGapRatio).coerceIn(InkRanges.rightOfGapRatio),
                    restartOverlapRatio = (p[INK_RESTART_OVERLAP] ?: i.restartOverlapRatio).coerceIn(InkRanges.ratio),
                    restartMarginRatio = (p[INK_RESTART_MARGIN] ?: i.restartMarginRatio).coerceIn(InkRanges.ratio),
                    restartPauseMs = (p[INK_RESTART_PAUSE] ?: i.restartPauseMs).coerceIn(InkRanges.restartPauseMs),
                    newCharPauseMs = (p[INK_NEW_CHAR_PAUSE] ?: i.newCharPauseMs).coerceIn(InkRanges.newCharPauseMs),
                    topLeftCornerRatio = (p[INK_TOP_LEFT_CORNER] ?: i.topLeftCornerRatio).coerceIn(InkRanges.ratio),
                    topLeftBackRatio = (p[INK_TOP_LEFT_BACK] ?: i.topLeftBackRatio).coerceIn(InkRanges.topLeftBackRatio),
                    relayoutGapRatio = (p[INK_RELAYOUT_GAP] ?: i.relayoutGapRatio).coerceIn(InkRanges.ratio),
                    maxHypothesisCalls = (p[INK_MAX_HYPOTHESIS] ?: i.maxHypothesisCalls).coerceIn(InkRanges.maxHypothesisCalls),
                    splitStrokeRatio = (p[INK_SPLIT_STROKE_RATIO] ?: i.splitStrokeRatio).coerceIn(InkRanges.splitStrokeRatio),
                    maxSplitCalls = (p[INK_MAX_SPLIT] ?: i.maxSplitCalls).coerceIn(InkRanges.maxSplitCalls),
                    stackedMinStrokes = (p[INK_STACKED_MIN] ?: i.stackedMinStrokes).coerceIn(InkRanges.stackedMinStrokes),
                ),
            ),
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
            ).coerced(), // 범위 밖 저장값이면 판정기 생성 시 예외(gesture.md §3)
            devOptionsVisible = p[DEV_OPTIONS_VISIBLE] ?: d.devOptionsVisible,
            devlogEnabled = p[DEVLOG_ENABLED] ?: d.devlogEnabled,
        )
    }

    fun encode(s: AppSettings, p: MutablePreferences) {
        p[ONBOARDING_DONE] = s.onboardingDone
        p[ONBOARDING_STEP] = s.onboardingStep
        p[IDLE_TIMEOUT_MIN] = s.idleTimeoutMin
        p[EMPTY_SEND_ENTER] = s.emptySendEnter
        p[HAPTICS_ENABLED] = s.haptics.enabled
        p[HAPTICS_STRENGTH] = s.haptics.strength.id
        p[PREVIEW_ENABLED] = s.previewEnabled
        p[HOST_OS] = s.keymap.os.id
        p[IME_TOGGLE] = s.keymap.imeToggle.id
        p[COMMIT] = s.keymap.commit.id
        p[LATIN] = s.keymap.latin.id
        p.remove(LATIN_V1)
        p[SEGMENTATION] = s.ink.segmentation.id
        p[INK_RESTART_PAUSE] = s.ink.config.restartPauseMs
        p[INK_NEW_CHAR_PAUSE] = s.ink.config.newCharPauseMs
        p.remove(INK_NEW_CHAR_PAUSE_V1)
        p[INK_RESTART_OVERLAP] = s.ink.config.restartOverlapRatio
        p[INK_RESTART_MARGIN] = s.ink.config.restartMarginRatio
        p[INK_RIGHT_OF_GAP] = s.ink.config.rightOfGapRatio
        p.remove(INK_RIGHT_OF_GAP_V1)
        p[INK_TOP_LEFT_CORNER] = s.ink.config.topLeftCornerRatio
        p[INK_TOP_LEFT_BACK] = s.ink.config.topLeftBackRatio
        p[INK_RELAYOUT_GAP] = s.ink.config.relayoutGapRatio
        p[INK_MIN_CHAR_SIZE] = s.ink.config.minCharSize
        p[INK_MAX_HYPOTHESIS] = s.ink.config.maxHypothesisCalls
        p[INK_SPLIT_STROKE_RATIO] = s.ink.config.splitStrokeRatio
        p[INK_MAX_SPLIT] = s.ink.config.maxSplitCalls
        p[INK_STACKED_MIN] = s.ink.config.stackedMinStrokes
        p[TAP_SLOP] = s.gesture.tapSlopDp
        p[TAP_MAX] = s.gesture.tapMaxMs
        p[MULTI_TAP_GAP] = s.gesture.multiTapGapMs
        p[MULTI_TAP_RADIUS] = s.gesture.multiTapRadiusDp
        p[MULTI_DOWN_WINDOW] = s.gesture.multiDownWindowMs
        p[SWIPE_MIN] = s.gesture.swipeMinDp
        p[SWIPE_AXIS_RATIO] = s.gesture.swipeAxisRatio
        p[PALM_MAJOR] = s.gesture.palmTouchMajorDp
        p.remove(PALM_MAJOR_V1)
        p[EDGE_BAND] = s.gesture.edgeBandDp
        p[DEV_OPTIONS_VISIBLE] = s.devOptionsVisible
        p[DEVLOG_ENABLED] = s.devlogEnabled
    }
}

/** InkConfig 허용 범위(docs/core/ink.md §3.1). 저장소와 개발자 옵션 화면이 같이 쓴다. */
object InkRanges {
    val minCharSize = 8f..96f
    val rightOfGapRatio = 0f..2f
    val ratio = 0f..1f
    val restartPauseMs = 0L..2000L
    val newCharPauseMs = 0L..3000L
    val topLeftBackRatio = 0f..2f
    val maxHypothesisCalls = 0..10
    val splitStrokeRatio = 1f..5f
    val maxSplitCalls = 0..12
    val stackedMinStrokes = 0..30
}
