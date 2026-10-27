package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.ink.box
import com.ouor.pocketboard.core.ink.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SameSpotRestartRuleTest {
    private val rule = SameSpotRestartRule(InkConfig())
    private val current = listOf(box(0f, 0f, 100f, 0))   // 끝 시각 100ms

    @Test
    fun sameSpot_longPause_isNewChar() =
        assertEquals(Boundary.NEW_CHAR, rule.apply(SegmentInput(current, box(10f, 10f, 80f, 100 + 350))))

    @Test
    fun sameSpot_shortPause_passes() = assertNull(rule.apply(SegmentInput(current, box(10f, 10f, 80f, 100 + 200))))

    @Test
    fun outsideSpot_longPause_passes() = assertNull(rule.apply(SegmentInput(current, box(150f, 0f, 80f, 2000))))

    @Test
    fun dot_insideSpot_longPause_isNewChar() =
        assertEquals(Boundary.NEW_CHAR, rule.apply(SegmentInput(current, line(50f, 50f, 50f, 50f, 1000, 0))))
}
