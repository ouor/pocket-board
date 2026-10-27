package com.ouor.pocketboard.core.gesture

/** 진동 이벤트 토큰(PRD §4.3, design-system §7.3). 패턴 수치는 구현 안에 둔다. */
enum class HapticPattern {
    SENT,
    ENTER,
    SPACE,
    BACKSPACE,
    CHUNK_DELETE,
    RESYNC,
    RECOGNITION_FAILED,
    CONNECTED,
    DISCONNECTED,
    NOT_CONNECTED,
}

enum class HapticStrength(val id: String) { WEAK("weak"), MEDIUM("medium"), STRONG("strong") }

data class HapticSettings(val enabled: Boolean = true, val strength: HapticStrength = HapticStrength.MEDIUM)

/**
 * 진동의 유일한 출구. 앱의 진동 끄기(F12)가 유일한 스위치이며 시스템 "터치 진동" 설정을 따르지 않는다(A1).
 * 어느 스레드에서 불러도 된다. 설정은 생성 시 받은 StateFlow<HapticSettings>를 매번 읽는다.
 */
fun interface Haptics {
    fun play(pattern: HapticPattern)
}
