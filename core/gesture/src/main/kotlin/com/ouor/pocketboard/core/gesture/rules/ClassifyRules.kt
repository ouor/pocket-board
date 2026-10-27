package com.ouor.pocketboard.core.gesture.rules

import com.ouor.pocketboard.core.common.rule.Rule
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.SwipeDirection
import com.ouor.pocketboard.core.gesture.engine.Contact
import com.ouor.pocketboard.core.gesture.engine.ContactKind
import kotlin.math.abs

typealias ClassifyRule = Rule<Contact, ContactKind>

private fun Contact.tap() = ContactKind.Tap(
    fingers = fingers,
    xDp = tracks.map { it.first.xDp }.average().toFloat(),
    yDp = tracks.map { it.first.yDp }.average().toFloat(),
    startMs = startMs,
    endMs = endMs,
)

/** 손가락 2개 이상이 거의 같이 닿고, 거의 안 움직이고, 짧게 끝남 → n손가락 탭. */
class MultiFingerTapRule(
    private val downWindowMs: Long,
    private val slopDp: Float,
    private val maxMs: Long,
) : ClassifyRule {
    override val id = "classify/multi-finger-tap"
    override fun apply(input: Contact): ContactKind? {
        if (input.fingers < 2 || input.downSpreadMs > downWindowMs) return null
        if (input.durationMs > maxMs || input.tracks.any { it.maxDistanceDp >= slopDp }) return null
        return input.tap()
    }
}

/** 손가락 2개 이상이 거의 같이 닿고, 평균 이동이 한 축으로 충분히 길면 → n손가락 스와이프(4방향). 속도 조건 없음(이불 속 느린 스와이프). */
class MultiFingerSwipeRule(
    private val downWindowMs: Long,
    private val minDp: Float,
    private val axisRatio: Float,
) : ClassifyRule {
    override val id = "classify/multi-finger-swipe"
    override fun apply(input: Contact): ContactKind? {
        if (input.fingers < 2 || input.downSpreadMs > downWindowMs) return null
        val dx = input.tracks.map { it.dx }.average().toFloat()
        val dy = input.tracks.map { it.dy }.average().toFloat()
        val ax = abs(dx)
        val ay = abs(dy)
        val dir = when {
            ax >= minDp && ax >= ay * axisRatio -> if (dx < 0) SwipeDirection.LEFT else SwipeDirection.RIGHT
            ay >= minDp && ay >= ax * axisRatio -> if (dy < 0) SwipeDirection.UP else SwipeDirection.DOWN
            else -> return null
        }
        return ContactKind.Swipe(Gesture.Swipe(input.fingers, dir))
    }
}

/** 한 손가락, 거의 안 움직이고 짧음 → 탭. 탭은 점 획 후보로도 나간다(엔진이 처리). */
class SingleTapRule(private val slopDp: Float, private val maxMs: Long) : ClassifyRule {
    override val id = "classify/single-tap"
    override fun apply(input: Contact): ContactKind? {
        if (input.fingers != 1) return null
        val t = input.tracks.single()
        return input.tap().takeIf { t.maxDistanceDp < slopDp && input.durationMs <= maxMs }
    }
}

/** 한 손가락의 나머지 → 필기 획. */
class StrokeRule : ClassifyRule {
    override val id = "classify/stroke"
    override fun apply(input: Contact) = ContactKind.Stroke.takeIf { input.fingers == 1 }
}
