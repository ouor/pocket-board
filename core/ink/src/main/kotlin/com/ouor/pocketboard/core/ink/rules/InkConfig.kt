package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.common.ink.InkStroke
import kotlinx.serialization.Serializable

/**
 * 판정 임계값(dp, ms). 초기값은 조사 초안이며 실기기 실험 E5 기록으로 조정한다.
 * "글자 크기" = 묶음 높이, 단 [minCharSize] 보다 작으면 [minCharSize](점·가로획만 있는 묶음 대비).
 */
@Serializable   // InkReport 에 그대로 남긴다
data class InkConfig(
    val minCharSize: Float = 24f,
    /**
     * S2: 새 획 왼쪽이 묶음 오른쪽 + 글자 크기 × 이 값보다 오른쪽이면 새 글자. 현장(빠른 흘림, 2~3음절 줄): 줄 안 음절 간격이
     * 글자 크기의 0.3~0.6 이라 0.3 이면 줄 첫 자음·모음을 잘라냈다. 이어 쓴 음절은 한 묶음이어도 재배치가 안 옮기므로 끊을 이득이 없다.
     */
    val rightOfGapRatio: Float = 2f,
    /** S3: 새 획 점 중 묶음 상자(여유 포함) 안에 든 비율이 이 값 이상이고 */
    val restartOverlapRatio: Float = 0.6f,
    /** S3: 상자 여유 = 글자 크기 × 이 값. */
    val restartMarginRatio: Float = 0.1f,
    /** S3: 직전 획 끝 → 새 획 시작 쉼이 이 값 이상이면 같은 자리 재시작(새 글자). */
    val restartPauseMs: Long = 350,
    /** S4: 직전 획 끝 → 새 획 시작 쉼이 이 값 이상이면 자리와 상관없이 새 글자. 현장: 글자 안 ≤347ms, 글자 사이 575ms~. */
    val newCharPauseMs: Long = 500,
    /** S5: 새 획 시작점이 묶음 상자 왼쪽 위(글자 크기 × 이 값 안)에 있고(0 = 끔) */
    val topLeftCornerRatio: Float = 0.4f,
    /** S5: 직전 획 끝에서 왼쪽으로 글자 크기 × 이 값 이상 되돌아가 시작하면 새 글자(한글 음절은 왼쪽 위 초성부터). */
    val topLeftBackRatio: Float = 0.4f,
    /** 재배치 시 묶음 사이 간격 = 앞 묶음 높이 × 이 값. */
    val relayoutGapRatio: Float = 0.2f,
    /** 합침 확인(B: right-of 경계, C: + same-spot-restart 경계)에서 단어 하나당 추가 인식 호출 상한(지연 예산). */
    val maxHypothesisCalls: Int = 4,
    /** Split: 묶음 획 수 ≥ 읽힌 한글 글자의 표준 획 수 × 이 값이면 같은 글자를 같은 자리에 겹쳐 쓴 것으로 본다. */
    val splitStrokeRatio: Float = 2f,
    /** Split: 단어 하나당 나눌 자리를 찾는 추가 인식 호출 상한(지연 예산). 겹친 음절 나누기(split/stacked-syllables)도 따로 같은 상한. */
    val maxSplitCalls: Int = 8,
    /**
     * 겹친 음절(읽기 전): 글자 하나 넓이(가로 ≤ 세로 × [StackedSyllablesRule.STACKED_MAX_ASPECT])인 묶음의 획 수가 이 값 이상이면
     * 음절 2개 이상이 겹쳤을 수 있다고 보고 나눌 자리를 ML Kit 으로 찾는다(0 = 끔). 현장 3차: 흘려 쓴 2~3음절 묶음이 6~9획
     * ("상담원"의 담원 6, "어버림"의 버림 6, "한민국" 7). 6~9획 한 음절("끊")은 조각이 음절 2~3개로 읽히지 않아 그대로 둔다.
     * 10(말뭉치 음절의 98.4% 가 표준 9획 이하)이던 것을 6 으로 낮췄다(docs/core/ink.md §6.1 회귀 확인).
     */
    val stackedMinStrokes: Int = 6,
) {
    // 범위는 docs/core/ink.md §3 표와 같다. 설정 저장소(:app)가 범위 밖 값을 넘기면 프로그래머 오류.
    init {
        require(minCharSize in 8f..96f) { "minCharSize $minCharSize" }
        require(rightOfGapRatio in 0f..2f) { "rightOfGapRatio $rightOfGapRatio" }
        require(restartOverlapRatio in 0f..1f) { "restartOverlapRatio $restartOverlapRatio" }
        require(restartMarginRatio in 0f..1f) { "restartMarginRatio $restartMarginRatio" }
        require(restartPauseMs in 0L..2000L) { "restartPauseMs $restartPauseMs" }
        require(newCharPauseMs in 0L..3000L) { "newCharPauseMs $newCharPauseMs" }
        require(topLeftCornerRatio in 0f..1f) { "topLeftCornerRatio $topLeftCornerRatio" }
        require(topLeftBackRatio in 0f..2f) { "topLeftBackRatio $topLeftBackRatio" }
        require(relayoutGapRatio in 0f..1f) { "relayoutGapRatio $relayoutGapRatio" }
        require(maxHypothesisCalls in 0..10) { "maxHypothesisCalls $maxHypothesisCalls" }
        require(splitStrokeRatio in 1f..5f) { "splitStrokeRatio $splitStrokeRatio" }
        require(maxSplitCalls in 0..12) { "maxSplitCalls $maxSplitCalls" }
        require(stackedMinStrokes in 0..30) { "stackedMinStrokes $stackedMinStrokes" }
    }

    fun charSize(height: Float) = maxOf(height, minCharSize)
}

enum class Boundary { NEW_CHAR, SAME_CHAR }

/** Segment 단계 입력. [current]가 비어 있으면 단어의 첫 획. */
data class SegmentInput(val current: List<InkStroke>, val stroke: InkStroke)

/** Candidate 단계 입력. 각 목록은 인식기 후보 순서 그대로(점수 없음, ink.md §2.4). */
data class CandidateInput(val ko: List<String>, val en: List<String>)

/** Merge 단계(B·C) 입력: 끊긴 두 묶음을 합쳐 ko 로 인식한 후보. */
data class MergeInput(val ko: List<String>)

/** 겹친 음절 나누기(읽기 전) 입력: 분할·합침 뒤 글자 묶음 하나. */
data class StackInput(val strokes: List<InkStroke>)

/** Split 단계 입력: 인식 결과와 1:1 로 대응한 글자 묶음 하나와 그 묶음이 읽힌 글자. */
data class SplitInput(val strokes: List<InkStroke>, val char: String)

/** Text 단계 입력. [clusters]는 이 단어의 글자 묶음(원래 좌표). 출력 글자 수와 같을 때만 1:1 대응한다고 볼 수 있다. */
data class TextInput(val text: String, val clusters: List<List<InkStroke>>)

internal fun Char.isHangulSyllable() = this in '가'..'힣'
internal fun Char.isHangulJamo() = this in 'ㄱ'..'ㆎ'
