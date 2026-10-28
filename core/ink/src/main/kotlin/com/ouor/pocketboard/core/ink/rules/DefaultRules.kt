package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.common.rule.Rule

/**
 * :core:ink 의 단계별 규칙 목록(F28, A19). 순서가 의미를 가진다. 추가 절차는 architecture.md §9.3, docs/core/ink.md.
 *
 * | 단계 | id | 조건 | 결론 | 근거 |
 * |---|---|---|---|---|
 * | Segment | segment/first | 단어 첫 획 | NEW_CHAR | - |
 * | Segment | segment/right-of | 새 획 왼쪽 > 묶음 오른쪽 + 글자 크기×2 | NEW_CHAR | 멀리 떨어진 새 글자(S2). 0.3 이던 기본값은 빠른 줄 쓰기에서 첫 자음을 잘랐다 |
 * | Segment | segment/same-spot-restart | 새 획 점 60% 이상이 묶음 상자 안 + 쉼 ≥ 350ms | NEW_CHAR | 겹쳐 쓰기(S3) |
 * | Segment | segment/top-left-restart | 묶음 2획 이상 + 새 획이 직전 획 끝에서 글자 크기×0.4 이상 왼쪽으로 되돌아가 묶음 상자 왼쪽 위(×0.4 안)에서 시작 | NEW_CHAR | 빠른 겹쳐 쓰기(쉼 40~150ms), ink-overlap.md |
 * | Segment | segment/long-pause | 쉼 ≥ 500ms(자리 무관) | NEW_CHAR | 현장 "이게": ㅣ 만 남은 묶음 밖에서 1.1~2.0s 뒤 시작 |
 * | Segment | (기본) | 나머지 | SAME_CHAR | |
 * | Candidate | candidate/empty | ko·en 1순위 모두 비어 있음 | "" (Empty) | D9 |
 * | Candidate | candidate/ko-hangul | ko 1순위에 완성 음절 | ko 1순위 | C1 |
 * | Candidate | candidate/en-in-ko | en 1순위가 ko 후보에도 | en 1순위 | C2 |
 * | Candidate | candidate/ko-jamo | ko 1순위가 자모만 | ko 1순위 | C3, "ㅋㅋ" |
 * | Candidate | (기본) | 나머지 | en(ASCII) > ko > en | C4 |
 * | Merge(B·C) | merge/single-syllable | 끊긴 두 묶음(B: right-of, C: + same-spot-restart)을 합쳐 인식한 ko 1순위가 음절 1개 | 합침 | 현장 "이": ㅇ|ㅣ |
 * | Merge | (기본) | 나머지 | 끊은 그대로 | |
 * | Split(읽기 전) | split/stacked-syllables | 글자 하나 넓이(가로 ≤ 세로×1.4) 묶음이 10획 이상 | 직전 획 끝에서 왼쪽 위로 가장 많이 되돌아간 자리부터 최대 3곳을 잘라 두 조각을 나란히 ko 로 읽어, 1순위가 완성 음절 2~3개면 그 자리에서 나눔(조각도 다시 확인) | 현장 3차 "상담"→"학", "마트에"→"토" |
 * | Split(읽기 전) | (기본) | 나머지 | 그대로 | |
 * | Split | split/stroke-excess | 결과 글자 수 = 묶음 수일 때, 묶음 획 수 ≥ 읽힌 한글 글자 표준 획 수 × 2 | 그 글자로 다시 나눔 | 빠른 겹쳐 쓰기 "ㅋㅋㅋㅋ"→"ㅋ" |
 * | Split | (기본) | 나머지 | 그대로 | |
 * | Text | text/trim-leading | 단어 글자 앞 공백(인식기가 앞 문맥 때문에 붙임) | 앞 공백 지움 | 현장 " 테스트" |
 * | Text | (그 밖) | | | F20 혼동은 실기기 사례 후 |
 */
class InkRules(
    val segment: List<Rule<SegmentInput, Boundary>>,
    val candidate: List<Rule<CandidateInput, String>>,
    val merge: List<Rule<MergeInput, Boolean>>,
    /** Split(읽기 전): Merge 뒤·재배치 전에 겹친 음절 묶음을 나눌지. */
    val stacked: List<Rule<StackInput, Boolean>>,
    val split: List<Rule<SplitInput, String>>,
    val text: List<Rule<TextInput, TextInput>>,
)

fun defaultInkRules(config: InkConfig = InkConfig()) = InkRules(
    segment = listOf(
        FirstStrokeRule,
        RightOfRule(config),          // 이어 쓰기가 먼저: 오른쪽에 떨어진 획은 쉼과 상관없이 새 글자
        SameSpotRestartRule(config),
        TopLeftRestartRule(config),   // 쉼 없는 겹쳐 쓰기: 위치(왼쪽 위로 되돌아감)만으로
        LongPauseRule(config),        // 맨 뒤: 같은 자리 재시작이 못 잡은(묶음 밖) 긴 쉼만
    ),
    candidate = listOf(
        EmptyCandidateRule,           // 맨 앞: 뒤 규칙들은 빈 후보를 가정하지 않는다
        KoHangulRule,
        EnInKoRule,
        KoJamoRule,
    ),
    merge = listOf(SingleSyllableMergeRule),
    stacked = listOf(StackedSyllablesRule(config)),
    split = listOf(StrokeExcessSplitRule(config)),
    text = listOf(TrimLeadingRule),   // Candidate 직후·Split 전에 돈다(InkPipeline)
)
