package com.ouor.pocketboard.core.gesture.rules

import com.ouor.pocketboard.core.common.rule.Rule
import com.ouor.pocketboard.core.gesture.engine.Contact
import com.ouor.pocketboard.core.gesture.engine.RejectVerdict

typealias RejectRule = Rule<Contact, RejectVerdict>

/** 시스템이 가로챈 접촉(ACTION_CANCEL, API 33+ FLAG_CANCELED 팜 판정)은 통째로 버린다. */
class SystemCancelRule : RejectRule {
    override val id = "reject/system-cancel"
    override fun apply(input: Contact) = RejectVerdict.REJECT.takeIf { input.canceled }
}

/** 접촉 면적이 손가락보다 크면 손바닥(F14). [palmTouchMajorDp] 0 = 끔(touchMajor 를 믿을 수 없는 기기). */
class PalmSizeRule(private val palmTouchMajorDp: Float) : RejectRule {
    override val id = "reject/palm-size"
    override fun apply(input: Contact) =
        RejectVerdict.REJECT.takeIf { palmTouchMajorDp > 0f && input.maxTouchMajorDp > palmTouchMajorDp }
}

/** 첫 착지가 화면 가장자리 띠 안이면 스침으로 본다(F14, A4). 화면 크기 0 이면 그 변은 검사하지 않는다. */
class EdgeStartRule(
    private val bandDp: Float,
    private val screenWidthDp: Float,
    private val screenHeightDp: Float,
) : RejectRule {
    override val id = "reject/edge-start"
    override fun apply(input: Contact): RejectVerdict? {
        if (bandDp <= 0f) return null
        val x = input.first.xDp
        val y = input.first.yDp
        val near = x < bandDp || y < bandDp ||
            (screenWidthDp > 0f && x > screenWidthDp - bandDp) ||
            (screenHeightDp > 0f && y > screenHeightDp - bandDp)
        return RejectVerdict.REJECT.takeIf { near }
    }
}

/** 손가락 4개 이상은 쥐거나 손바닥이 닿은 것으로 본다. */
class TooManyFingersRule(private val maxFingers: Int = 3) : RejectRule {
    override val id = "reject/too-many-fingers"
    override fun apply(input: Contact) = RejectVerdict.REJECT.takeIf { input.fingers > maxFingers }
}
