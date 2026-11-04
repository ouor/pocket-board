package com.ouor.pocketboard.core.gesture.rules

import com.ouor.pocketboard.core.gesture.GestureConfig

/** 단계별 규칙 목록. 순서가 의미를 가진다(firstMatch: 처음 결론을 낸 규칙이 이김). */
data class GestureRules(
    val reject: List<RejectRule>,
    val classify: List<ClassifyRule>,
    val sequence: List<SequenceRule>,
    val resolve: List<ResolveRule>,
)

/**
 * 1차 최소 규칙(gesture.md §3.3, architecture §9.2). 규칙 추가 절차는 architecture §9.3, docs/dev/core/gesture.md.
 *
 * | 규칙 id | 조건 | 결론 | 근거 사례 |
 * |---|---|---|---|
 * | reject/system-cancel | CANCEL 또는 FLAG_CANCELED 샘플 | 접촉 버림 | 시스템 제스처·팜 판정 |
 * | reject/palm-size | touchMajor > palmTouchMajorDp(0 이면 끔) | 접촉 버림 | F14 |
 * | reject/edge-start | 첫 착지가 가장자리 띠 안 | 접촉 버림 | F14, A4 |
 * | reject/too-many-fingers | 손가락 4개 이상 | 접촉 버림 | 쥠·손바닥 |
 * | classify/multi-finger-tap | 2+손가락, 착지 편차 ≤ window, 이동 < slop, 길이 ≤ tapMax | Tap(n) | PRD §4.2 |
 * | classify/multi-finger-swipe | 2+손가락, 착지 편차 ≤ window, 평균 이동 ≥ swipeMin, 축 비 ≥ ratio | Swipe(n, 방향) | PRD §4.2 |
 * | classify/single-tap | 1손가락, 이동 < slop, 길이 ≤ tapMax | Tap(1) + 점 획 | PRD §4.2 |
 * | classify/stroke | 1손가락 나머지 | 획 | - |
 * | (기본) | 나머지(예: 착지가 엇갈린 여러 손가락) | Ignore | - |
 * | sequence/same-spot-multitap | 같은 손가락 수, 간격 ≤ multiTapGap, 거리 ≤ multiTapRadius | 이어짐 | PRD §4.2 1차 규칙 |
 * | resolve/retract-tap-ink | 1손가락 n≥2 탭이 매핑됨 | 제스처 + 점 획 retract | gesture.md §3.1 |
 * | resolve/multi-finger-tap | 2+손가락 탭 | 제스처 | - |
 * | (기본) | 나머지 | 제스처 아님, 점 획 유지 | 매핑 안 된 멀티 탭, 단일 탭 |
 */
object DefaultRules {
    fun create(c: GestureConfig) = GestureRules(
        reject = listOf(
            SystemCancelRule(),
            PalmSizeRule(c.palmTouchMajorDp),
            EdgeStartRule(c.edgeBandDp, c.screenWidthDp, c.screenHeightDp),
            TooManyFingersRule(),
        ),
        classify = listOf(
            // 탭을 스와이프보다 먼저: 거의 안 움직인 접촉은 스와이프 조건에 걸릴 일이 없지만 의도를 분명히 한다.
            MultiFingerTapRule(c.multiDownWindowMs, c.tapSlopDp, c.tapMaxMs),
            MultiFingerSwipeRule(c.multiDownWindowMs, c.swipeMinDp, c.swipeAxisRatio),
            SingleTapRule(c.tapSlopDp, c.tapMaxMs),
            StrokeRule(),
        ),
        sequence = listOf(SameSpotMultiTapRule(c.multiTapGapMs, c.multiTapRadiusDp)),
        resolve = listOf(RetractTapInkRule(), MultiFingerTapResolveRule()),
    )
}
