package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.ink.box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LongPauseRuleTest {
    private val rule = LongPauseRule(InkConfig())
    private val current = listOf(box(100f, 0f, 20f, 0))   // 끝 시각 100ms, 가는 묶음(현장의 ㅣ)

    @Test
    fun otherSpot_longPause_isNewChar() =
        assertEquals(Boundary.NEW_CHAR, rule.apply(SegmentInput(current, box(0f, 0f, 60f, 100 + 600))))

    @Test
    fun otherSpot_shortPause_passes() = assertNull(rule.apply(SegmentInput(current, box(0f, 0f, 60f, 100 + 347))))   // 현장 글자 안 최대

    @Test
    fun fieldPause575_isNewChar() =   // 현장 at=1793163446102 획 9: 575ms 쉬고 새 글자였는데 600 기본값에 안 걸림
        assertEquals(Boundary.NEW_CHAR, rule.apply(SegmentInput(current, box(0f, 0f, 60f, 100 + 575))))

    @Test
    fun threshold_isConfigurable() =
        assertNull(LongPauseRule(InkConfig(newCharPauseMs = 2000)).apply(SegmentInput(current, box(0f, 0f, 60f, 100 + 1500))))
}
