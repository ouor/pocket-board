package com.ouor.pocketboard.core.gesture

import kotlinx.coroutines.flow.Flow

/**
 * 판정된 제스처. [id]는 저장(매핑)·기록에 쓰는 고정 문자열이다. 형식을 바꾸지 않는다.
 * 한 손가락 단일 탭·드래그는 필기 전용이라 매핑 대상이 아니다(PRD §4.2).
 */
sealed interface Gesture {
    val id: String

    data class Tap(val fingers: Int, val count: Int) : Gesture {
        override val id get() = "tap.f$fingers.x$count"
    }

    data class Swipe(val fingers: Int, val direction: SwipeDirection) : Gesture {
        override val id get() = "swipe.f$fingers.${direction.id}"
    }

    companion object {
        /** 매핑 화면(SC13)에 나오는 목록, 표시 순서. */
        val MAPPABLE: List<Gesture> = listOf(
            Tap(1, 2), Tap(1, 3), Tap(1, 4),
            Tap(2, 1), Tap(3, 1),
            Swipe(2, SwipeDirection.LEFT), Swipe(2, SwipeDirection.RIGHT),
            Swipe(2, SwipeDirection.UP), Swipe(2, SwipeDirection.DOWN),
            Swipe(3, SwipeDirection.LEFT),
        )

        fun fromId(id: String): Gesture? = MAPPABLE.find { it.id == id }
    }
}

enum class SwipeDirection(val id: String) { LEFT("left"), RIGHT("right"), UP("up"), DOWN("down") }

/** 재매핑 가능한 동작(PRD §4.2). 저장은 [id]로 한다. */
enum class Action(val id: String) {
    SEND("send"),
    ENTER("enter"),
    BACKSPACE("backspace"),
    SPACE("space"),
    CHUNK_DELETE("chunk_delete"),
    RESYNC("resync");

    companion object {
        fun fromId(id: String): Action? = entries.find { it.id == id }
    }
}

/** 제스처당 동작 하나(F11). 한 동작은 여러 제스처에 있을 수 있다(A3). */
data class GestureMapping(val actions: Map<Gesture, Action>) {
    companion object {
        val DEFAULT = GestureMapping(
            mapOf(
                Gesture.Tap(1, 2) to Action.SEND,
                Gesture.Swipe(2, SwipeDirection.LEFT) to Action.BACKSPACE,
                Gesture.Tap(2, 1) to Action.SPACE,
                Gesture.Swipe(2, SwipeDirection.UP) to Action.CHUNK_DELETE,
                Gesture.Tap(3, 1) to Action.RESYNC,
            ),
        )
    }
}

/** 매핑 저장소. 구현은 Preferences DataStore 파일 "gesture_mapping"(키 = 제스처 id, 값 = 동작 id). */
interface GestureMappingRepository {
    val mapping: Flow<GestureMapping>

    /** [action] null = 해제. */
    suspend fun assign(gesture: Gesture, action: Action?): AssignResult

    suspend fun resetToDefault()
}

sealed interface AssignResult {
    /** [replaced]: 이 제스처에 있다가 해제된 동작("기존 ○○에서 해제됩니다"). */
    data class Assigned(val replaced: Action?) : AssignResult
    /** 전송의 마지막 제스처를 없애려 함. 저장하지 않는다. */
    data object SendRequired : AssignResult
}
