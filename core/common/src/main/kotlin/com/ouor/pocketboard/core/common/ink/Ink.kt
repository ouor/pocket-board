package com.ouor.pocketboard.core.common.ink

/**
 * 필기 획 표현. :core:gesture 가 만들고 :core:ink 가 소비한다.
 * 좌표는 dp(쓰기 화면 왼쪽 위 원점), 시간은 단조 증가 ms(MotionEvent.eventTime = uptimeMillis 기준).
 * 인식기는 획 순서와 시간을 쓰므로(ink.md §2.2) 합성 타임스탬프를 만들지 않는다.
 */
data class InkPoint(val x: Float, val y: Float, val t: Long)

/** 획 하나. 1점 획(점 찍기)을 허용한다: `.` `:` `i` 에 필요하다. */
data class InkStroke(val points: List<InkPoint>) {
    init {
        require(points.isNotEmpty()) { "빈 획" }
    }
}

/** 전송 전까지 쌓이는 입력. [Space]는 쓰는 중 들어온 스페이스로, 단어 경계가 된다(ink.md §4.1 단계 1). */
sealed interface InkItem {
    data class Stroke(val id: Long, val stroke: InkStroke) : InkItem
    data object Space : InkItem
}
