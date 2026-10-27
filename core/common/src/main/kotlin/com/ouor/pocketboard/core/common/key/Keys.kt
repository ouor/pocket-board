package com.ouor.pocketboard.core.common.key

/**
 * 키 입력 표현. :core:keymap 이 만들고 :core:hid 가 보낸다.
 * HID 는 문자·IME 를 모르고, keymap 은 리포트 바이트·전송 타이밍을 모른다.
 * 키 코드는 USB HID Usage Tables 의 Keyboard/Keypad 페이지(0x07) 값 0x00..0xFF.
 */
sealed interface KeyStep {
    /** 누르고 뗀다. [usage] 0 이면 수정키만 누른다(예: 오른쪽 Alt 단독). 해제 리포트는 HID 가 항상 보장한다. */
    data class Press(val usage: Int, val modifiers: Int = 0) : KeyStep {
        init {
            require(usage in 0..0xFF && modifiers in 0..0xFF)
        }
    }

    /** IME 가 따라오도록 기다린다. 기본 키 간격(HID 소유)에 더해지는 keymap 쪽 의도적 대기. */
    data class Pause(val ms: Long) : KeyStep

    /** Caps Lock LED 가 [capsLock]이 될 때까지 기다린다. 시간이 지나면 그냥 진행한다(keymap.md §4 레이스). */
    data class AwaitCapsLock(val capsLock: Boolean, val timeoutMs: Long) : KeyStep
}

/**
 * 전송 단위. 호스트 화면에 글자 하나를 만드는 키 묶음(두벌식 음절 = 여러 키, 대문자 = Shift+키),
 * 또는 글자를 만들지 않는 동작(한/영 토글, 확정, 백스페이스).
 * HID 는 끝까지 보낸 단위 수를 돌려주고(단위 중간에서 멈추면 그 단위는 세지 않는다),
 * keymap 은 그 수로 덩어리 이력(F10)과 한/영 추정 상태를 맞춘다.
 */
data class KeyUnit(val steps: List<KeyStep>, val countsAsChar: Boolean)

/** HID 수정키 비트(입력 리포트 첫 바이트). */
object KeyModifiers {
    const val LEFT_CTRL = 0x01
    const val LEFT_SHIFT = 0x02
    const val LEFT_ALT = 0x04
    const val LEFT_GUI = 0x08
    const val RIGHT_CTRL = 0x10
    const val RIGHT_SHIFT = 0x20
    const val RIGHT_ALT = 0x40
    const val RIGHT_GUI = 0x80
}

/** 호스트가 보낸 키보드 LED 출력 리포트(E14). [raw]를 그대로 두는 것은 Kana 비트 실험(keymap Q4) 때문이다. */
@JvmInline
value class LedState(val raw: Int) {
    val numLock get() = raw and 0x01 != 0
    val capsLock get() = raw and 0x02 != 0
    val scrollLock get() = raw and 0x04 != 0
    val kana get() = raw and 0x10 != 0
}
