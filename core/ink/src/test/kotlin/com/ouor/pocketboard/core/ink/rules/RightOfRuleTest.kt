package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.ink.box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RightOfRuleTest {
    private val rule = RightOfRule(InkConfig(rightOfGapRatio = 0.3f))
    private val current = listOf(box(0f, 0f, 100f, 0))   // 높이 100 → 간격 30

    @Test
    fun farRight_isNewChar() = assertEquals(Boundary.NEW_CHAR, rule.apply(SegmentInput(current, box(131f, 0f, 50f, 200))))

    @Test
    fun closeRight_orOverlapping_passes() {
        assertNull(rule.apply(SegmentInput(current, box(120f, 0f, 50f, 200))))
        assertNull(rule.apply(SegmentInput(current, box(10f, 0f, 50f, 200))))
    }
}
