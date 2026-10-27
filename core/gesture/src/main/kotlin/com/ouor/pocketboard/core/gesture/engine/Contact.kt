package com.ouor.pocketboard.core.gesture.engine

import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.TouchAction
import com.ouor.pocketboard.core.gesture.TouchSample
import kotlin.math.hypot

/**
 * 접촉 하나: 첫 손가락 DOWN 부터 마지막 손가락 UP(또는 CANCEL)까지. Reject·Classify 규칙의 입력이다.
 * 같은 접촉 안에서 손가락을 뗐다 다시 대면 포인터 ID가 재사용되므로 DOWN 마다 새 [PointerTrack]을 만든다.
 */
class Contact(val samples: List<TouchSample>) {
    val tracks: List<PointerTrack>

    init {
        require(samples.isNotEmpty())
        val all = ArrayList<MutableList<TouchSample>>()
        val current = HashMap<Int, MutableList<TouchSample>>()
        for (s in samples) {
            if (s.action == TouchAction.DOWN || s.pointerId !in current) {
                current[s.pointerId] = mutableListOf<TouchSample>().also { all += it }
            }
            current.getValue(s.pointerId) += s
        }
        tracks = all.map { PointerTrack(it) }
    }

    val startMs: Long get() = samples.first().timeMs
    val endMs: Long get() = samples.last().timeMs
    val durationMs: Long get() = endMs - startMs
    /** 접촉에 참여한 손가락 수(착지 횟수). */
    val fingers: Int get() = tracks.size
    /** 첫 착지와 마지막 착지 사이 시간. 여러 손가락이 "같이" 닿았는지 본다. */
    val downSpreadMs: Long get() = tracks.maxOf { it.downMs } - tracks.minOf { it.downMs }
    val canceled: Boolean get() = samples.any { it.canceled || it.action == TouchAction.CANCEL }
    val maxTouchMajorDp: Float get() = samples.maxOf { it.touchMajorDp }
    val first: TouchSample get() = samples.first()
}

class PointerTrack(val samples: List<TouchSample>) {
    val first: TouchSample get() = samples.first()
    val last: TouchSample get() = samples.last()
    val downMs: Long get() = first.timeMs
    val dx: Float get() = last.xDp - first.xDp
    val dy: Float get() = last.yDp - first.yDp
    /** 시작점에서 가장 멀리 간 거리(되돌아온 흔들림도 잡는다). */
    val maxDistanceDp: Float get() = samples.maxOf { hypot(it.xDp - first.xDp, it.yDp - first.yDp) }
}

/** Reject 단계 결론. 규칙은 거부할 때만 [REJECT]를 낸다. */
enum class RejectVerdict { REJECT, PASS }

/** Classify 단계 결론. */
sealed interface ContactKind {
    data object Stroke : ContactKind
    /** [xDp]/[yDp]: 착지점 중심. 멀티 탭 "같은 자리" 판정에 쓴다. */
    data class Tap(val fingers: Int, val xDp: Float, val yDp: Float, val startMs: Long, val endMs: Long) : ContactKind
    data class Swipe(val gesture: Gesture.Swipe) : ContactKind
    data object Ignore : ContactKind
}

/** Sequence 단계 입력: 진행 중 시퀀스의 마지막 탭과 새 탭. */
data class SequenceInput(val previous: ContactKind.Tap, val next: ContactKind.Tap, val countSoFar: Int)

enum class SequenceVerdict { CONTINUE, NEW }

/** Resolve 단계 입력: 확정된 탭 시퀀스. [dotStrokeIds]: 이 탭들이 낸 점 획 id. */
data class ResolveInput(
    val fingers: Int,
    val count: Int,
    val dotStrokeIds: List<Long>,
    val mapped: Set<Gesture>,
)

/** [gesture] null = 제스처 아님(점 획은 그대로 남음). */
data class Resolution(val gesture: Gesture?, val retract: List<Long>) {
    companion object {
        val NONE = Resolution(null, emptyList())
    }
}
