package com.ouor.pocketboard.core.keymap

import com.ouor.pocketboard.core.common.key.KeyModifiers.LEFT_SHIFT
import com.ouor.pocketboard.core.common.key.KeyStep
import com.ouor.pocketboard.core.common.key.KeyStep.Press
import com.ouor.pocketboard.core.common.key.LedState
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultKeyInputTest {
    private val input = DefaultKeyInput().apply { onLeds(LedState(0)) }
    private val capsMethod = DefaultKeyInput(KeymapSettings(latin = LatinMethod.CAPS_LOCK)).apply { onLeds(LedState(0)) }
    private val capsOn = listOf(Press(0x39), KeyStep.AwaitCapsLock(true, 300))
    private val capsOff = listOf(Press(0x39), KeyStep.AwaitCapsLock(false, 300))
    private val lang1 = listOf(Press(0x90))

    private fun KeyPlan.send() = also { input.confirm(it, it.units.size) }

    @Test
    fun latinAndDigits_capsLockMethod() {
        val plan = capsMethod.text("Hello 123")
        assertEquals(
            listOf(
                capsOn + Press(0x0B), // H: Caps 켬 상태에서 키만
                listOf(Press(0x08, LEFT_SHIFT)), // e: Shift = 소문자
                listOf(Press(0x0F, LEFT_SHIFT)),
                listOf(Press(0x0F, LEFT_SHIFT)),
                listOf(Press(0x12, LEFT_SHIFT)),
                listOf(Press(0x2C)),
                listOf(Press(0x1E)), listOf(Press(0x1F)), listOf(Press(0x20)),
                capsOff, // caps-restore
            ),
            plan.units.map { it.steps },
        )
        assertEquals(listOf("keymap/caps-restore"), plan.ruleIds)
    }

    /**
     * HID 사용 코드표: a~z = 0x04~0x1D, 1~9 = 0x1E~0x26, 0 = 0x27.
     * CAPS_LOCK 방식(A21): 영문 앞에 Caps 를 켜고(한글 IME 우회), 소문자는 Shift, 숫자는 그대로, 끝에 Caps 복원.
     */
    @Test
    fun allLowercaseLettersAndDigits_mapToHidUsages() {
        val letters = (0 until 26).map { listOf(Press(0x04 + it, LEFT_SHIFT)) }
        val digits = (0 until 10).map { listOf(Press(0x1E + it)) }
        val expected = listOf(capsOn + letters[0]) + letters.drop(1) + digits + listOf(capsOff)
        assertEquals(expected, capsMethod.text("abcdefghijklmnopqrstuvwxyz1234567890").units.map { it.steps })
    }

    @Test
    fun allUppercaseLetters_useCapsLockThenRestore() {
        val plan = capsMethod.text("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val keys = (0 until 26).map { listOf(Press(0x04 + it)) }
        assertEquals(listOf(capsOn + keys[0]) + keys.drop(1) + listOf(capsOff), plan.units.map { it.steps })
    }

    @Test
    fun hangul_togglesOnce_andCommitsAtEnd() {
        val plan = input.text("안녕").send()
        assertEquals(
            listOf(
                lang1,
                listOf(Press(0x07), Press(0x0E), Press(0x16)), // d k s
                listOf(Press(0x16), Press(0x18), Press(0x07)), // s u d
                lang1 + lang1, // chunk-end-commit (TOGGLE_TWICE)
            ),
            plan.units.map { it.steps },
        )
        assertEquals(ImeMode.HANGUL, input.imeState.value.assumedMode)
        assertEquals(1, input.text("가").units.count { it.steps == lang1 + lang1 }) // 이미 한글 모드: 토글 없음
    }

    @Test
    fun jamoBoundary_commitsOnlyWhereImeWouldMerge() {
        input.resync() // HANGUL
        val commit = lang1 + lang1
        assertEquals(1, input.text("ㅋㅋ").units.count { it.steps == commit }) // 끝 확정만
        assertEquals(2, input.text("각ㅏ").units.count { it.steps == commit })
        assertEquals(listOf(listOf(Press(0x14, LEFT_SHIFT)), listOf(Press(0x14, LEFT_SHIFT))), input.text("ㅃㅃ").units.take(2).map { it.steps })
    }

    @Test
    fun unmappable_skipped() {
        val plan = input.text("a☃b")
        assertEquals("☃", plan.skipped)
        assertEquals(2, plan.units.size) // a, b (MODE_SWITCH: Caps 를 건드리지 않아 복원 없음)
    }

    private fun shift(u: Int) = Press(u, LEFT_SHIFT)
    private fun steps(p: KeyPlan) = p.units.map { it.steps }

    @Test
    fun defaultIsModeSwitch() = assertEquals(LatinMethod.MODE_SWITCH, KeymapSettings().latin)

    /** SC06 연결 테스트 문자열(Windows 11 MS IME 실기기에서 CAPS_LOCK 이 "ㅗ띠ㅣㅒ" 를 낸 경우). */
    @Test
    fun modeSwitch_connectionTestString() {
        val plan = input.text("안녕 Hello 123").send()
        assertEquals(
            listOf(
                lang1,
                listOf(Press(0x07), Press(0x0E), Press(0x16)),
                listOf(Press(0x16), Press(0x18), Press(0x07)),
                listOf(Press(0x2C)), // 스페이스가 조합을 확정
                lang1 + shift(0x0B), // 영문 전환 + H
                listOf(Press(0x08)), listOf(Press(0x0F)), listOf(Press(0x0F)), listOf(Press(0x12)),
                listOf(Press(0x2C)),
                listOf(Press(0x1E)), listOf(Press(0x1F)), listOf(Press(0x20)),
            ),
            steps(plan),
        )
        // 한글로 안 끝나 확정 없음. caps-restore 토큰은 붙지만 Caps 가 그대로라 키는 없다(위 단위 목록).
        assertEquals(listOf("keymap/caps-restore"), plan.ruleIds)
        assertEquals(HostImeState(ImeMode.LATIN, false), input.imeState.value)
    }

    @Test
    fun modeSwitch_latinThenHangul() {
        assertEquals(
            listOf(
                listOf(Press(0x04)), listOf(Press(0x05)), listOf(Press(0x06)), // 이미 LATIN: 토글 없음
                lang1, listOf(Press(0x15), Press(0x0E)), // 가 = r k
                listOf(Press(0x16), Press(0x0E)), // 나 = s k
                lang1 + lang1, // chunk-end-commit, 끝 모드 HANGUL
            ),
            steps(input.text("abc가나").send()),
        )
        assertEquals(ImeMode.HANGUL, input.imeState.value.assumedMode)
    }

    @Test
    fun modeSwitch_hangulLatinHangul() {
        assertEquals(
            listOf(
                lang1, listOf(Press(0x15), Press(0x0E)),
                lang1 + Press(0x04), // 토글이 '가' 조합도 확정한다: 따로 Commit 없음
                lang1, listOf(Press(0x15), Press(0x0E)),
                lang1 + lang1,
            ),
            steps(input.text("가a가")),
        )
    }

    @Test
    fun modeSwitch_uppercase_capsLedOff_usesShift_noCapsPresses() {
        assertEquals(listOf(listOf(shift(0x04)), listOf(Press(0x05)), listOf(shift(0x06))), steps(input.text("AbC")))
    }

    @Test
    fun modeSwitch_uppercase_capsLedOn_invertsShift_noCapsPresses() {
        input.onLeds(LedState(0x02))
        assertEquals(listOf(listOf(Press(0x04)), listOf(shift(0x05)), listOf(Press(0x06))), steps(input.text("AbC")))
    }

    @Test
    fun modeSwitch_capsOn_hangulTurnsCapsOff_thenLatinUsesTrackedCaps_thenRestores() {
        input.onLeds(LedState(0x02))
        assertEquals(
            listOf(
                capsOff, lang1, listOf(Press(0x15), Press(0x0E)),
                lang1 + shift(0x04), // Caps 를 껐으므로 대문자는 Shift
                capsOn, // caps-restore: 한 번만 되돌림
            ),
            steps(input.text("가A")),
        )
    }

    @Test
    fun chunkDelete_stack_partialConfirm_andEnterBoundary() {
        input.text("안녕").send()
        input.space().send()
        input.text("ok").send()
        assertEquals("안녕 ok", input.preContext())

        input.backspace().send()
        val del = input.chunkDelete()
        assertEquals(1, del.units.size) // "ok" 에서 하나 남음
        del.send()

        val del2 = input.chunkDelete()
        assertEquals(3, del2.units.size) // "안녕 "
        input.confirm(del2, 2)
        assertEquals(1, input.chunkDelete().units.size)
        assertEquals("안", input.preContext())

        input.enter().send()
        assertEquals(0, input.chunkDelete().units.size) // A22: 엔터를 넘지 않음
        assertEquals("", input.preContext())
    }

    @Test
    fun ledCapsWinsOverPlanAfterLedKnown() {
        input.text("A").send() // plan 은 Caps 를 켰다가 되돌림
        input.onLeds(LedState(0x02))
        input.text("가").send()
        assertEquals(true, input.imeState.value.capsLock)
    }
}
