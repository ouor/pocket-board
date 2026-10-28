package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.ink.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StackedSyllablesRuleTest {
    private val rule = StackedSyllablesRule(InkConfig())
    /** 높이 100 칸에 세로획 [n]개를 [step] 간격으로(가로 = (n-1)×step). */
    private fun strokes(n: Int, step: Float = 10f) = List(n) { line(it * step, 0f, it * step, 100f, it * 120L) }

    @Test
    fun tenStrokes_inOneCharFootprint_isStacked() = assertEquals(true, rule.apply(StackInput(strokes(10))))

    @Test
    fun fewerStrokes_passes() = assertNull(rule.apply(StackInput(strokes(9))))   // 한 음절(98.4% 가 9획 이하)

    @Test
    fun wideRow_passes() = assertNull(rule.apply(StackInput(strokes(10, step = 20f))))   // 가로 180 > 세로 100 × 1.4: 이어 쓴 줄

    @Test
    fun disabled_passes() = assertNull(StackedSyllablesRule(InkConfig(stackedMinStrokes = 0)).apply(StackInput(strokes(30))))
}
