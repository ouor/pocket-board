package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.ink.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopLeftRestartRuleTest {
    private val rule = TopLeftRestartRule(InkConfig())
    // 묶음: 0..100 칸 음절(ㅇ, ㅏ). 직전 획 끝 x=90, 시각 300. 글자 크기 100 → 영역·되돌아감 40
    private val current = listOf(line(0f, 0f, 60f, 100f, 0), line(70f, 0f, 90f, 100f, 200))

    @Test
    fun backToTopLeft_isNewChar() =   // 다음 음절 초성: 왼쪽 위로 되돌아감, 쉼 짧음(현장 40~150ms)
        assertEquals(Boundary.NEW_CHAR, rule.apply(SegmentInput(current, line(10f, 20f, 50f, 30f, 360))))

    @Test
    fun backToMiddleOrBottom_passes() {
        assertNull(rule.apply(SegmentInput(current, line(5f, 50f, 60f, 50f, 360))))   // ㅋ·ㅌ 가운데 획, ㅗ·ㅡ
        assertNull(rule.apply(SegmentInput(current, line(5f, 90f, 80f, 95f, 360))))   // 받침
    }

    @Test
    fun outsideCorner_orSmallBackJump_passes() {
        assertNull(rule.apply(SegmentInput(current, line(50f, 10f, 60f, 30f, 360))))   // 60 되돌아갔지만 영역(40) 밖
        // 영역 안이어도 되돌아감(직전 획 끝 40 → 10 = 30)이 40 보다 작으면 안 끊는다
        assertNull(rule.apply(SegmentInput(listOf(line(0f, 0f, 60f, 100f, 0), line(20f, 0f, 40f, 20f, 200)), line(10f, 10f, 30f, 10f, 360))))
    }

    @Test
    fun singleStrokeCluster_orDisabled_passes() {
        assertNull(rule.apply(SegmentInput(current.take(1), line(0f, 0f, 30f, 5f, 360))))   // ㅊ·ㅎ·ㅍ 둘째 획
        assertNull(TopLeftRestartRule(InkConfig(topLeftCornerRatio = 0f)).apply(SegmentInput(current, line(0f, 0f, 50f, 30f, 360))))
    }
}
