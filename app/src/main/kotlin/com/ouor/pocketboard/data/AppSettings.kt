package com.ouor.pocketboard.data

import com.ouor.pocketboard.core.gesture.GestureConfig
import com.ouor.pocketboard.core.gesture.HapticSettings
import com.ouor.pocketboard.core.ink.InkSettings
import com.ouor.pocketboard.core.keymap.KeymapSettings

/**
 * 앱 설정 전체(architecture §11). 저장은 [SettingsRepository], 키 이름은 [SettingsCodec].
 * 코어 설정 값 객체를 그대로 품고, AppContainer 가 각 코어에 나눠 준다.
 */
data class AppSettings(
    val onboardingDone: Boolean = false,
    /** 0 = 끄지 않음(F15). */
    val idleTimeoutMin: Int = 30,
    val emptySendEnter: Boolean = true,
    val haptics: HapticSettings = HapticSettings(),
    val previewEnabled: Boolean = false,
    // 아래는 개발자 옵션(SC17): A21/A32 keymap 스위치, A36 인식·제스처 임계값, B6 기록
    val keymap: KeymapSettings = KeymapSettings(),
    val ink: InkSettings = InkSettings(),
    /** 판정 임계값(A36). 화면 크기 필드는 저장하지 않는다(쓰기 화면이 채움). */
    val gesture: GestureConfig = GestureConfig(),
    val devOptionsVisible: Boolean = false,
    val devlogEnabled: Boolean = false,
)
