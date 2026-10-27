package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.common.ink.InkStroke

/**
 * 판정 임계값(dp, ms). 초기값은 조사 초안이며 실기기 실험 E5 기록으로 조정한다.
 * "글자 크기" = 묶음 높이, 단 [minCharSize] 보다 작으면 [minCharSize](점·가로획만 있는 묶음 대비).
 */
data class InkConfig(
    val minCharSize: Float = 24f,
    /** S2: 새 획 왼쪽이 묶음 오른쪽 + 글자 크기 × 이 값보다 오른쪽이면 새 글자. */
    val rightOfGapRatio: Float = 0.3f,
    /** S3: 새 획 점 중 묶음 상자(여유 포함) 안에 든 비율이 이 값 이상이고 */
    val restartOverlapRatio: Float = 0.6f,
    /** S3: 상자 여유 = 글자 크기 × 이 값. */
    val restartMarginRatio: Float = 0.1f,
    /** S3: 직전 획 끝 → 새 획 시작 쉼이 이 값 이상이면 같은 자리 재시작(새 글자). */
    val restartPauseMs: Long = 350,
    /** 재배치 시 묶음 사이 간격 = 앞 묶음 높이 × 이 값. */
    val relayoutGapRatio: Float = 0.2f,
    /** 단계 C 에서 단어 하나당 추가 인식 호출 상한(지연 예산). */
    val maxHypothesisCalls: Int = 4,
) {
    fun charSize(height: Float) = maxOf(height, minCharSize)
}

enum class Boundary { NEW_CHAR, SAME_CHAR }

/** Segment 단계 입력. [current]가 비어 있으면 단어의 첫 획. */
data class SegmentInput(val current: List<InkStroke>, val stroke: InkStroke)

/** Candidate 단계 입력. 각 목록은 인식기 후보 순서 그대로(점수 없음, ink.md §2.4). */
data class CandidateInput(val ko: List<String>, val en: List<String>)

/** Merge 단계(단계 C) 입력: 끊긴 두 묶음을 합쳐 ko 로 인식한 후보. */
data class MergeInput(val ko: List<String>)

/** Text 단계 입력. [clusters]는 이 단어의 글자 묶음(원래 좌표). 출력 글자 수와 같을 때만 1:1 대응한다고 볼 수 있다. */
data class TextInput(val text: String, val clusters: List<List<InkStroke>>)

internal fun Char.isHangulSyllable() = this in '가'..'힣'
internal fun Char.isHangulJamo() = this in 'ㄱ'..'ㆎ'
