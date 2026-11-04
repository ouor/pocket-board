package com.ouor.pocketboard.core.gesture.engine

import com.ouor.pocketboard.core.gesture.GestureConfig

/**
 * [GestureConfig] 임계값 9개의 허용 범위(docs/dev/core/gesture.md §3). 개발자 옵션(SC17)과 설정 저장소는 이 값을 그대로 쓴다.
 * 화면 크기(screenWidthDp/HeightDp)는 :app 이 측정해 넣는 값이라 범위가 없다(0 = 모름).
 */
object GestureRanges {
    val tapSlopDp = 2f..48f
    val tapMaxMs = 50L..1000L
    val multiTapGapMs = 100L..1000L
    val multiTapRadiusDp = 4f..96f
    val multiDownWindowMs = 20L..500L
    val swipeMinDp = 16f..200f
    /** 1 미만이면 대각선이 두 방향 조건을 모두 만족할 수 있다. */
    val swipeAxisRatio = 1f..5f
    /** 0 = 팜 규칙 끔. */
    val palmTouchMajorDp = 0f..60f
    /** 0 = 가장자리 규칙 끔. */
    val edgeBandDp = 0f..64f
}

/** 저장된 값(구버전·손상 포함)을 범위 안으로 자른다. 설정 저장소가 읽을 때 쓴다. */
fun GestureConfig.coerced(): GestureConfig = copy(
    tapSlopDp = tapSlopDp.coerceIn(GestureRanges.tapSlopDp),
    tapMaxMs = tapMaxMs.coerceIn(GestureRanges.tapMaxMs),
    multiTapGapMs = multiTapGapMs.coerceIn(GestureRanges.multiTapGapMs),
    multiTapRadiusDp = multiTapRadiusDp.coerceIn(GestureRanges.multiTapRadiusDp),
    multiDownWindowMs = multiDownWindowMs.coerceIn(GestureRanges.multiDownWindowMs),
    swipeMinDp = swipeMinDp.coerceIn(GestureRanges.swipeMinDp),
    swipeAxisRatio = swipeAxisRatio.coerceIn(GestureRanges.swipeAxisRatio),
    palmTouchMajorDp = palmTouchMajorDp.coerceIn(GestureRanges.palmTouchMajorDp),
    edgeBandDp = edgeBandDp.coerceIn(GestureRanges.edgeBandDp),
)

/** 범위 밖이면 프로그래머 오류(InkConfig 와 같은 방식). [DefaultGestureRecognizer] 생성 시 검사한다. */
fun GestureConfig.requireInRange() {
    require(this == coerced()) { "GestureConfig 범위 밖: $this (GestureRanges 참고)" }
}
