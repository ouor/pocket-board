package com.ouor.pocketboard.core.gesture.rules

import com.ouor.pocketboard.core.common.rule.Rule
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.engine.ResolveInput
import com.ouor.pocketboard.core.gesture.engine.Resolution
import com.ouor.pocketboard.core.gesture.engine.SequenceInput
import com.ouor.pocketboard.core.gesture.engine.SequenceVerdict
import kotlin.math.hypot

typealias SequenceRule = Rule<SequenceInput, SequenceVerdict>
typealias ResolveRule = Rule<ResolveInput, Resolution>

/** PRD §4.2 1차 규칙: 짧은 간격에 거의 같은 자리를 다시 치면 멀티 탭이 이어진다. 손가락 수가 같아야 한다. */
class SameSpotMultiTapRule(private val gapMs: Long, private val radiusDp: Float) : SequenceRule {
    override val id = "sequence/same-spot-multitap"
    override fun apply(input: SequenceInput): SequenceVerdict? {
        val p = input.previous
        val n = input.next
        if (p.fingers != n.fingers) return null
        if (n.startMs - p.endMs > gapMs) return null
        if (hypot(n.xDp - p.xDp, n.yDp - p.yDp) > radiusDp) return null
        return SequenceVerdict.CONTINUE
    }
}

/** 한 손가락 멀티 탭이 매핑된 제스처면 → 제스처 확정, 앞서 낸 점 획 retract. 매핑 안 됐으면 점 획으로 남긴다. */
class RetractTapInkRule : ResolveRule {
    override val id = "resolve/retract-tap-ink"
    override fun apply(input: ResolveInput): Resolution? {
        if (input.fingers != 1 || input.count < 2) return null
        val g = Gesture.Tap(1, input.count)
        return Resolution(g, input.dotStrokeIds).takeIf { g in input.mapped }
    }
}

/** 여러 손가락 탭은 점 획이 없으므로 그대로 제스처(매핑 여부는 앱이 본다). */
class MultiFingerTapResolveRule : ResolveRule {
    override val id = "resolve/multi-finger-tap"
    override fun apply(input: ResolveInput) =
        Resolution(Gesture.Tap(input.fingers, input.count), emptyList()).takeIf { input.fingers >= 2 }
}
