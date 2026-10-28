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

/**
 * S5(빠른 겹쳐 쓰기): 묶음이 이미 2획 이상인데 새 획이 직전 획 끝에서 왼쪽으로 크게 되돌아가 묶음 상자 왼쪽 위에서 시작 → 새 글자.
 * 한글 음절은 왼쪽 위 초성부터 쓰고, 같은 음절 안의 되돌아감(받침, ㅗ·ㅜ·ㅡ, ㅋ·ㅌ·ㅂ 가운데 획)은 아래·가운데에서 시작한다.
 * 쉼이 40~150ms 로 글자 안과 같아 S3 이 못 잡는 현장 사례(docs/research/ink-overlap.md). 2획 미만이면 보지 않는다(ㅊ·ㅎ·ㅍ 둘째 획).
 */
class TopLeftRestartRule(private val config: InkConfig) : Rule<SegmentInput, Boundary> {
    override val id = "segment/top-left-restart"
    override fun apply(input: SegmentInput): Boundary? {
        if (input.current.size < 2 || config.topLeftCornerRatio == 0f) return null
        val box = input.current.bounds()
        val size = config.charSize(box.height)
        val start = input.stroke.points.first()
        val back = input.current.last().points.last().x - start.x
        val corner = size * config.topLeftCornerRatio
        return if (start.x <= box.left + corner && start.y <= box.top + corner && back >= size * config.topLeftBackRatio) Boundary.NEW_CHAR else null
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
