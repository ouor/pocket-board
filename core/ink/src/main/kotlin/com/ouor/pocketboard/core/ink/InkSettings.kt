package com.ouor.pocketboard.core.ink

/**
 * 개발자 옵션에서 바꾸는 인식 방식(A32와 같은 취지). 저장은 :app, 값은 MlKitInkModelManager 에 StateFlow 로 넘긴다.
 * 인식할 때마다 현재 값을 읽으므로 인식기를 다시 열 필요가 없다.
 */
data class InkSettings(
    val segmentation: SegmentationMode = SegmentationMode.RULES,
)

/** 겹쳐 쓰기 처리 방식(ink.md §4.3 실험 A/B/C). [id]는 저장용 고정 문자열. */
enum class SegmentationMode(val id: String) {
    /** A: 분할·재배치 없이 원본 잉크 그대로 인식. */
    RAW("raw"),
    /** B(기본): Segment 규칙으로 글자 경계 → 겹친 묶음만 오른쪽으로 재배치. */
    RULES("rules"),
    /** C: B + 같은 자리 재시작으로 끊은 경계를 합쳐 인식해 보고 Merge 규칙으로 되돌릴지 판정. */
    HYPOTHESIS("hypothesis"),
}
