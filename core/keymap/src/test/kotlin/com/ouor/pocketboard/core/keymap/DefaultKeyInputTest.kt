package com.ouor.pocketboard.core.keymap

import com.ouor.pocketboard.core.common.key.KeyModifiers.LEFT_SHIFT
import com.ouor.pocketboard.core.common.key.KeyStep
import com.ouor.pocketboard.core.common.key.KeyStep.Press
import com.ouor.pocketboard.core.common.key.LedState
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultKeyInputTest {
    private val input = DefaultKeyInput().apply { onLeds(LedState(0)) }
    private val capsOn = listOf(Press(0x39), KeyStep.AwaitCapsLock(true, 300))
    private val capsOff = listOf(Press(0x39), KeyStep.AwaitCapsLock(false, 300))
    private val lang1 = listOf(Press(0x90))

    private fun KeyPlan.send() = also { input.confirm(it, it.units.size) }

    @Test
    fun latinAndDigits_capsLockMethod() {
        val plan = input.text("Hello 123")
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
        assertEquals(3, plan.units.size) // a, b, caps-restore
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
