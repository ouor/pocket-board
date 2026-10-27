package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.common.rule.Rule
import com.ouor.pocketboard.core.ink.segment.bounds

/** S1: 단어의 첫 획 → 새 글자. */
object FirstStrokeRule : Rule<SegmentInput, Boundary> {
    override val id = "segment/first"
    override fun apply(input: SegmentInput) = if (input.current.isEmpty()) Boundary.NEW_CHAR else null
}

/** S2(이어 쓰기): 새 획이 묶음 오른쪽으로 글자 크기 × 비율 이상 떨어져 시작 → 새 글자. */
class RightOfRule(private val config: InkConfig) : Rule<SegmentInput, Boundary> {
    override val id = "segment/right-of"
    override fun apply(input: SegmentInput): Boundary? {
        val box = input.current.bounds()
        val gap = config.charSize(box.height) * config.rightOfGapRatio
        return if (input.stroke.bounds().left > box.right + gap) Boundary.NEW_CHAR else null
    }
}

/** S3(겹쳐 쓰기): 새 획이 대부분 묶음 자리 안이고, 직전 획 뒤 쉼이 길다 → 같은 자리에서 새 글자 시작. */
class SameSpotRestartRule(private val config: InkConfig) : Rule<SegmentInput, Boundary> {
    override val id = ID
    override fun apply(input: SegmentInput): Boundary? {
        val box = input.current.bounds()
        val margin = config.charSize(box.height) * config.restartMarginRatio
        val points = input.stroke.points
        val inside = points.count { box.contains(it, margin) }.toFloat() / points.size
        val pause = points.first().t - input.current.last().points.last().t
        return if (inside >= config.restartOverlapRatio && pause >= config.restartPauseMs) Boundary.NEW_CHAR else null
    }

    companion object {
        /** 단계 C 가 "애매한 경계"로 보는 규칙 id. */
        const val ID = "segment/same-spot-restart"
    }
}
