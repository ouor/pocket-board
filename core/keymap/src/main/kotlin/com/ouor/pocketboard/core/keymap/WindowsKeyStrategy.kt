package com.ouor.pocketboard.core.keymap

import com.ouor.pocketboard.core.common.key.KeyModifiers
import com.ouor.pocketboard.core.common.key.KeyStep

/** keymap 내부 확장: 한글 입력 전 Caps Lock 끄기와 두벌식 키 문자를 OS 전략에서 얻는다. */
internal interface KeyStrategy : OsKeyStrategy {
    fun setCapsLock(on: Boolean): List<KeyStep>

    /** QWERTY 문자 하나(a-z, A-Z) → 키. 대문자는 Shift. Caps Lock 은 꺼져 있다고 본다. */
    fun letterKey(c: Char, shift: Boolean = c.isUpperCase()): KeyStep
}

/**
 * Windows + Microsoft 한국어 IME(US 배치). HID usage 는 Keyboard page 0x07.
 *
 * A21 기본값 근거(실기기 판정 전 가정, research/keymap.md §2.3~2.5):
 * - 한/영 키 LANG1(0x90): Windows 한국어 레이아웃에서 VK_HANGUL. 레이어 드라이버에 따라 안 되면 RIGHT_ALT.
 * - 조합 확정 TOGGLE_TWICE: 텍스트 흔적이 없고 앱 자동완성을 건드리지 않는다. 유실 시 한/영이 어긋날 수 있어 SPACE_BACKSPACE 를 대체로 둔다.
 * - 영문 CAPS_LOCK: 한/영 상태를 몰라도 영문이 나온다는 가정(Q1). 틀리면 MODE_SWITCH.
 */
internal class WindowsKeyStrategy : KeyStrategy {
    override val os = HostOs.WINDOWS

    override fun toggleIme(settings: KeymapSettings): List<KeyStep> = listOf(
        when (settings.imeToggle) {
            ImeToggleKey.LANG1 -> KeyStep.Press(LANG1)
            ImeToggleKey.RIGHT_ALT -> KeyStep.Press(0, KeyModifiers.RIGHT_ALT)
            ImeToggleKey.SHIFT_SPACE -> KeyStep.Press(SPACE, KeyModifiers.LEFT_SHIFT)
        }
    )

    override fun commitComposition(settings: KeymapSettings): List<KeyStep> = when (settings.commit) {
        CommitMethod.TOGGLE_TWICE -> toggleIme(settings) + toggleIme(settings)
        CommitMethod.SPACE_BACKSPACE -> listOf(KeyStep.Press(SPACE), KeyStep.Press(BACKSPACE))
    }

    override fun latinLetter(c: Char, state: HostImeState, settings: KeymapSettings): Pair<List<KeyStep>, HostImeState> =
        when (settings.latin) {
            // Caps Lock 켬: 대문자 = 키만, 소문자 = Shift+키. IME 한/영 상태는 건드리지 않는다.
            LatinMethod.CAPS_LOCK -> {
                val prefix = if (state.capsLock == true) emptyList() else setCapsLock(true)
                (prefix + letterKey(c, shift = c.isLowerCase())) to state.copy(capsLock = true)
            }
            LatinMethod.MODE_SWITCH -> {
                val prefix = if (state.assumedMode == ImeMode.LATIN) emptyList() else toggleIme(settings)
                val shift = c.isUpperCase() != (state.capsLock == true)
                (prefix + letterKey(c, shift)) to state.copy(assumedMode = ImeMode.LATIN)
            }
        }

    override fun symbol(c: Char): List<KeyStep>? {
        PLAIN.indexOf(c).takeIf { it >= 0 }?.let { return listOf(KeyStep.Press(PLAIN_USAGE[it])) }
        SHIFTED.indexOf(c).takeIf { it >= 0 }?.let { return listOf(KeyStep.Press(SHIFTED_USAGE[it], KeyModifiers.LEFT_SHIFT)) }
        return null
    }

    override fun setCapsLock(on: Boolean): List<KeyStep> =
        listOf(KeyStep.Press(CAPS_LOCK), KeyStep.AwaitCapsLock(on, CAPS_TIMEOUT_MS))

    override fun letterKey(c: Char, shift: Boolean): KeyStep =
        KeyStep.Press(0x04 + (c.lowercaseChar() - 'a'), if (shift) KeyModifiers.LEFT_SHIFT else 0)

    private companion object {
        const val LANG1 = 0x90
        const val SPACE = 0x2C
        const val BACKSPACE = 0x2A
        const val CAPS_LOCK = 0x39
        const val CAPS_TIMEOUT_MS = 300L

        // US 배치. 같은 위치의 글자는 같은 usage(Shift 여부만 다름).
        const val PLAIN = "1234567890 -=[]\\;'`,./"
        const val SHIFTED = "!@#$%^&*()_+{}|:\"~<>?"
        val PLAIN_USAGE = (0x1E..0x27).toList() + listOf(SPACE, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
        val SHIFTED_USAGE = (0x1E..0x27).toList() + listOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
    }
}
