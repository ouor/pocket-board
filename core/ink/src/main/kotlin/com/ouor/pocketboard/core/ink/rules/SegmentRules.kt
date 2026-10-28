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
    override val id = ID
    override fun apply(input: SegmentInput): Boundary? {
        val box = input.current.bounds()
        val gap = config.charSize(box.height) * config.rightOfGapRatio
        return if (input.stroke.bounds().left > box.right + gap) Boundary.NEW_CHAR else null
    }

    companion object {
        /** 단계 B·C 가 합침을 확인하는 경계(모음이 떨어져 시작해 음절이 끊긴 경우). */
        const val ID = "segment/right-of"
    }
}

/** S3(겹쳐 쓰기): 새 획이 대부분 묶음 자리 안이고, 직전 획 뒤 쉼이 길다 → 같은 자리에서 새 글자 시작. */
class SameSpotRestartRule(private val config: InkConfig) : Rule<SegmentInput, Boundary> {
    override val id = ID
    override fun apply(input: SegmentInput): Boundary? {
        return if (input.insideRatio(config) >= config.restartOverlapRatio && input.pauseMs >= config.restartPauseMs) Boundary.NEW_CHAR else null
    }

    companion object {
        /** 단계 C 가 "애매한 경계"로 보는 규칙 id. */
        const val ID = "segment/same-spot-restart"
    }
}

/** S4: 직전 획 뒤 쉼이 아주 길다 → 자리와 상관없이 새 글자(묶음 상자가 가는 ㅣ 뿐이라 S3 가 안 걸리는 경우). */
class LongPauseRule(private val config: InkConfig) : Rule<SegmentInput, Boundary> {
    override val id = "segment/long-pause"
    override fun apply(input: SegmentInput) = if (input.pauseMs >= config.newCharPauseMs) Boundary.NEW_CHAR else null
}

// 규칙과 분할 로그(pb.ink.segment)가 같이 쓰는 측정값. [SegmentInput.current]가 비어 있으면 부르지 않는다.

/** 새 획 점 중 묶음 상자(여유 = 글자 크기 × restartMarginRatio) 안에 든 비율. */
fun SegmentInput.insideRatio(config: InkConfig): Float {
    val box = current.bounds()
    val margin = config.charSize(box.height) * config.restartMarginRatio
    return stroke.points.count { box.contains(it, margin) }.toFloat() / stroke.points.size
}

/** 직전 획 끝 → 새 획 시작(ms). */
val SegmentInput.pauseMs get() = stroke.points.first().t - current.last().points.last().t

/** 새 획 왼쪽 − 묶음 오른쪽(dp). 음수면 x 로 겹친다. */
val SegmentInput.gap get() = stroke.bounds().left - current.bounds().right
