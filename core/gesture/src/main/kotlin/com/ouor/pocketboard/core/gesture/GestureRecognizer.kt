package com.ouor.pocketboard.core.gesture

import com.ouor.pocketboard.core.common.ink.InkStroke
import kotlinx.serialization.Serializable

/** MotionEvent 를 포인터별로 펼친 것. 어댑터가 getHistorical* 샘플까지 모두 넣는다. 좌표·크기는 dp, 시간은 eventTime ms. */
@Serializable
data class TouchSample(
    val pointerId: Int,
    val action: TouchAction,
    val xDp: Float,
    val yDp: Float,
    val timeMs: Long,
    /** 0 이면 기기가 값을 주지 않는 것으로 본다. */
    val touchMajorDp: Float,
    /** ACTION_CANCEL 이거나 FLAG_CANCELED(API 33+, 팜 판정). */
    val canceled: Boolean = false,
)

@Serializable
enum class TouchAction { DOWN, MOVE, UP, CANCEL }

/**
 * 접촉 → 획/제스처 판정기. 순수 Kotlin, 메인 스레드에서 터치 순서대로 부른다.
 * 시간은 샘플의 timeMs 와 [onTimer]로만 흐른다(시계를 직접 읽지 않음) → JVM 테스트에서 재생 가능.
 * 판정은 Reject → Classify → Sequence → Resolve 규칙 체인(core.common.rule)으로 한다.
 */
interface GestureRecognizer {
    fun onTouch(sample: TouchSample): GestureStep

    /** [GestureStep.wakeAtMs]에 호출자가 부른다(멀티 탭 대기 만료). */
    fun onTimer(nowMs: Long): GestureStep

    /** 매핑이 바뀌면 부른다. 매핑된 최대 탭 수가 더블 탭 즉시 확정 여부를 정한다(gesture.md §3.1). */
    fun setMappedGestures(gestures: Set<Gesture>)

    /** 화면 이탈·화면 꺼짐(E13): 진행 중 접촉과 대기 중 탭 시퀀스를 버린다. */
    fun reset()
}

/**
 * 한 번 호출의 결과.
 * [wakeAtMs]: 이 시각에 [GestureRecognizer.onTimer]를 불러 달라(null = 예약 없음, 이전 예약 취소).
 * [records]: 끝난 접촉의 판정 기록(F30). 기록이 꺼져 있으면 호출자가 버린다.
 */
data class GestureStep(
    val outputs: List<GestureOutput> = emptyList(),
    val wakeAtMs: Long? = null,
    val records: List<ContactRecord> = emptyList(),
)

sealed interface GestureOutput {
    /** 필기 획 확정. 단일 탭도 일단 점 획으로 나온다. */
    data class StrokeCompleted(val id: Long, val stroke: InkStroke) : GestureOutput
    /** 앞서 낸 점 획이 멀티 탭의 일부였음(gesture.md §3.1 retract). */
    data class StrokeRetracted(val id: Long) : GestureOutput
    data class Detected(val gesture: Gesture) : GestureOutput
}

/** 접촉 하나의 기록(F30, B6). JSONL 한 줄로 저장되며 그대로 테스트 픽스처가 된다. */
@Serializable
data class ContactRecord(
    val samples: List<TouchSample>,
    /** "stroke" | 제스처 id | "rejected" | "ignored" */
    val result: String,
    /** 결론을 낸 규칙 id, 단계 순. */
    val ruleIds: List<String>,
)

/** 판정 임계값(dp·ms). 규칙은 자기 값만 읽는다. 수치는 gesture.md §3.4 초안, 실기기로 조정. */
data class GestureConfig(
    val tapSlopDp: Float = 12f,
    val tapMaxMs: Long = 250,
    val multiTapGapMs: Long = 300,
    val multiTapRadiusDp: Float = 24f,
    val multiDownWindowMs: Long = 120,
    val swipeMinDp: Float = 48f,
    val swipeAxisRatio: Float = 1.5f,
    /**
     * 0 = 팜 규칙 끔(기본). 물리값을 주는 기기에서 손끝은 7~10mm ≈ 45~60dp 라 옛 기본 18dp 는 모든 접촉을 버렸다(LG V35 현장).
     * 기기별 단위가 제각각이라 실측 후 개발자 옵션에서만 켠다.
     */
    val palmTouchMajorDp: Float = 0f,
    /** 화면 가장자리 무시 띠. :app 이 max(이 값, 시스템 제스처 인셋)으로 채운다. */
    val edgeBandDp: Float = 16f,
    val screenWidthDp: Float = 0f,
    val screenHeightDp: Float = 0f,
)
