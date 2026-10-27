package com.ouor.pocketboard.core.gesture.engine

import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.AssignResult
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.GestureMapping

/**
 * 매핑 저장 형식(architecture §11): 키 = 제스처 id, 값 = 동작 id 또는 "none". 키가 없으면 기본값.
 * 모르는 제스처·동작 id 는 무시하고 기본값을 쓴다(버전이 바뀌어도 깨지지 않게).
 */
object MappingCodec {
    const val NONE = "none"

    fun decode(stored: Map<String, String>): GestureMapping {
        val actions = GestureMapping.DEFAULT.actions.toMutableMap()
        for ((key, value) in stored) {
            val gesture = Gesture.fromId(key) ?: continue
            if (value == NONE) actions.remove(gesture) else Action.fromId(value)?.let { actions[gesture] = it }
        }
        return GestureMapping(actions)
    }

    fun encode(action: Action?): String = action?.id ?: NONE

    /** 저장 전 검증. 전송의 마지막 제스처를 없애는 변경은 거부(PRD §4.2 필수 동작). */
    fun assign(current: GestureMapping, gesture: Gesture, action: Action?): AssignResult {
        val previous = current.actions[gesture]
        val sendCount = current.actions.values.count { it == Action.SEND }
        if (previous == Action.SEND && action != Action.SEND && sendCount == 1) return AssignResult.SendRequired
        return AssignResult.Assigned(previous.takeIf { it != action })
    }
}
