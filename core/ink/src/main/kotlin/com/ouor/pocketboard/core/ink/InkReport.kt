package com.ouor.pocketboard.core.ink

import com.ouor.pocketboard.core.ink.rules.InkConfig
import kotlinx.serialization.Serializable

// 전송 1회 디버그 보고서(docs/core/ink.md §3.2). 획은 좌표 대신 "획 번호"로 가리킨다:
// 전송한 items 중 InkItem.Stroke 만 순서대로 센 0부터의 번호(InkItem.Stroke.id 아님). 좌표는 :app 이 따로 저장한다.
// 필드 이름·뜻은 저장용으로 고정. 새 정보는 기본값 있는 필드로만 더한다.

/** [HandwritingRecognizer.recognize] 한 번. Empty 면 [text] 는 "". */
@Serializable
data class InkReport(
    /** [SegmentationMode.id] */
    val mode: String,
    val config: InkConfig,
    val preContext: String,
    val words: List<InkWordReport>,
    val text: String,
    val ruleIds: List<String>,
)

/** 스페이스 사이 단어 하나. */
@Serializable
data class InkWordReport(
    /** 이 단어의 획 번호(연속). */
    val strokes: List<Int>,
    /** 획마다 Segment 판정(RAW 면 빈 목록). pb.ink.segment 줄과 같은 값. */
    val segments: List<InkSegmentStep>,
    /** 최종 글자 묶음(합침·나누기 뒤), 획 번호. */
    val clusters: List<List<Int>>,
    val merges: List<InkMergeCheck>,
    val splits: List<InkSplitAttempt>,
    /** 최종 인식에 넣은 획 = 원래 획을 x 로 이만큼 민 것(재배치). [strokes] 와 같은 순서. y·시각은 그대로. */
    val finalDx: List<Float>,
    /** 최종 인식의 ko·en 후보(인식기 순서). */
    val ko: List<String>,
    val en: List<String>,
    /** Candidate·Split 뒤 고른 글자(Text 변환 전). "" 면 단어 실패. */
    val chosen: String,
    val candidateRuleId: String?,
    /** 이 단어의 인식기 호출 전부(순서대로). 재현(RecordedInkEngine)용. */
    val calls: List<InkEngineCall>,
)

/** [newChar] 면 [ruleId] 규칙이 새 글자를 시작. 측정값은 첫 획이면 null. */
@Serializable
data class InkSegmentStep(
    val newChar: Boolean,
    val ruleId: String?,
    /** 새 획 점 중 묶음 상자 안 비율 */
    val inside: Float?,
    val pauseMs: Long?,
    /** 새 획 왼쪽 − 묶음 오른쪽(dp) */
    val gap: Float?,
)

/** 분할 결과 [cluster]번째 묶음(그 경계를 [boundary] 규칙이 만듦)을 앞 묶음과 합쳐 읽어 본 것. */
@Serializable
data class InkMergeCheck(val cluster: Int, val boundary: String, val ko: List<String>, val merged: Boolean)

/**
 * [ruleId] 규칙이 나누라 한 [cluster]번째 묶음. [parts] null = 나누기 실패.
 * split/stroke-excess: [target] = 나눌 글자. split/stacked-syllables(읽기 전, [cluster] 는 그때 묶음 번호): [target] = 자른 두 조각을 읽은 1순위.
 */
@Serializable
data class InkSplitAttempt(
    val cluster: Int, val target: String, val tries: List<InkSplitTry>, val parts: List<List<Int>>?,
    val ruleId: String = "split/stroke-excess",
)

/**
 * 묶음 안 [start]부터 [length]획을 ko 로 읽은 1순위(없으면 null).
 * split/stacked-syllables 는 [start] 획 앞에서 자른 두 조각([length] = 뒤 조각 획 수)을 나란히 읽은 1순위.
 */
@Serializable
data class InkSplitTry(val start: Int, val length: Int, val reading: String?)

/** 인식기 호출 하나: [strokes] 획을 각각 [dx] 만큼 x 로 민 잉크. [language] = "ko" / "en". */
@Serializable
data class InkEngineCall(
    val language: String,
    val strokes: List<Int>,
    val dx: List<Float>,
    val preContext: String,
    val candidates: List<String>,
)
