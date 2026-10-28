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
 * | Segment | segment/long-pause | 쉼 ≥ 600ms(자리 무관) | NEW_CHAR | 현장 "이게": ㅣ 만 남은 묶음 밖에서 1.1~2.0s 뒤 시작 |
 * | Segment | (기본) | 나머지 | SAME_CHAR | |
 * | Candidate | candidate/empty | ko·en 1순위 모두 비어 있음 | "" (Empty) | D9 |
 * | Candidate | candidate/ko-hangul | ko 1순위에 완성 음절 | ko 1순위 | C1 |
 * | Candidate | candidate/en-in-ko | en 1순위가 ko 후보에도 | en 1순위 | C2 |
 * | Candidate | candidate/ko-jamo | ko 1순위가 자모만 | ko 1순위 | C3, "ㅋㅋ" |
 * | Candidate | (기본) | 나머지 | en(ASCII) > ko > en | C4 |
 * | Merge(B·C) | merge/single-syllable | 끊긴 두 묶음(B: right-of, C: + same-spot-restart)을 합쳐 인식한 ko 1순위가 음절 1개 | 합침 | 현장 "이": ㅇ|ㅣ |
 * | Merge | (기본) | 나머지 | 끊은 그대로 | |
 * | Split | split/stroke-excess | 결과 글자 수 = 묶음 수일 때, 묶음 획 수 ≥ 읽힌 한글 글자 표준 획 수 × 2 | 그 글자로 다시 나눔 | 빠른 겹쳐 쓰기 "ㅋㅋㅋㅋ"→"ㅋ" |
 * | Split | (기본) | 나머지 | 그대로 | |
 * | Text | (없음, 1차) | | | F20 혼동은 실기기 사례 후 |
 */
class InkRules(
    val segment: List<Rule<SegmentInput, Boundary>>,
    val candidate: List<Rule<CandidateInput, String>>,
    val merge: List<Rule<MergeInput, Boolean>>,
    val split: List<Rule<SplitInput, String>>,
    val text: List<Rule<TextInput, TextInput>>,
)

fun defaultInkRules(config: InkConfig = InkConfig()) = InkRules(
    segment = listOf(
        FirstStrokeRule,
        RightOfRule(config),          // 이어 쓰기가 먼저: 오른쪽에 떨어진 획은 쉼과 상관없이 새 글자
        SameSpotRestartRule(config),
        LongPauseRule(config),        // 맨 뒤: 같은 자리 재시작이 못 잡은(묶음 밖) 긴 쉼만
    ),
    candidate = listOf(
        EmptyCandidateRule,           // 맨 앞: 뒤 규칙들은 빈 후보를 가정하지 않는다
        KoHangulRule,
        EnInKoRule,
        KoJamoRule,
    ),
    merge = listOf(SingleSyllableMergeRule),
    split = listOf(StrokeExcessSplitRule(config)),
    text = emptyList(),
)
