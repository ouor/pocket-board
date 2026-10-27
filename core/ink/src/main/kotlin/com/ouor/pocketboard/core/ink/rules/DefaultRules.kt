package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.common.rule.Rule

/**
 * :core:ink 의 단계별 규칙 목록(F28, A19). 순서가 의미를 가진다. 추가 절차는 architecture.md §9.3, docs/core/ink.md.
 *
 * | 단계 | id | 조건 | 결론 | 근거 |
 * |---|---|---|---|---|
 * | Segment | segment/first | 단어 첫 획 | NEW_CHAR | - |
 * | Segment | segment/right-of | 새 획 왼쪽 > 묶음 오른쪽 + 글자 크기×0.3 | NEW_CHAR | 이어 쓰기(ink.md §4.3 S2) |
 * | Segment | segment/same-spot-restart | 새 획 점 60% 이상이 묶음 상자 안 + 쉼 ≥ 350ms | NEW_CHAR | 겹쳐 쓰기(S3) |
 * | Segment | (기본) | 나머지 | SAME_CHAR | |
 * | Candidate | candidate/empty | ko·en 1순위 모두 비어 있음 | "" (Empty) | D9 |
 * | Candidate | candidate/ko-hangul | ko 1순위에 완성 음절 | ko 1순위 | C1 |
 * | Candidate | candidate/en-in-ko | en 1순위가 ko 후보에도 | en 1순위 | C2 |
 * | Candidate | candidate/ko-jamo | ko 1순위가 자모만 | ko 1순위 | C3, "ㅋㅋ" |
 * | Candidate | (기본) | 나머지 | en(ASCII) > ko > en | C4 |
 * | Merge(단계 C) | merge/single-syllable | 합쳐 인식한 ko 1순위가 음절 1개 | 합침 | |
 * | Merge | (기본) | 나머지 | 끊은 그대로 | |
 * | Text | (없음, 1차) | | | F20 혼동은 실기기 사례 후 |
 */
class InkRules(
    val segment: List<Rule<SegmentInput, Boundary>>,
    val candidate: List<Rule<CandidateInput, String>>,
    val merge: List<Rule<MergeInput, Boolean>>,
    val text: List<Rule<TextInput, TextInput>>,
)

fun defaultInkRules(config: InkConfig = InkConfig()) = InkRules(
    segment = listOf(
        FirstStrokeRule,
        RightOfRule(config),          // 이어 쓰기가 먼저: 오른쪽에 떨어진 획은 쉼과 상관없이 새 글자
        SameSpotRestartRule(config),
    ),
    candidate = listOf(
        EmptyCandidateRule,           // 맨 앞: 뒤 규칙들은 빈 후보를 가정하지 않는다
        KoHangulRule,
        EnInKoRule,
        KoJamoRule,
    ),
    merge = listOf(SingleSyllableMergeRule),
    text = emptyList(),
)
