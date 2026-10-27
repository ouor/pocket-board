package com.ouor.pocketboard.core.keymap.rules

/**
 * keymap Token 단계 규칙 목록(변환형, A19). 순서가 의미를 가진다.
 *
 * | id | 조건 | 동작 |
 * |---|---|---|
 * | keymap/skip-unmappable | 키로 바꿀 수 없는 문자 | 그 토큰 제거(D12) |
 * | keymap/jamo-boundary-commit | 자모 단독이 앞 한글과 합쳐질 수 있음 | 사이에 Commit |
 * | keymap/chunk-end-commit | 마지막 토큰이 한글 | 끝에 Commit |
 * | keymap/caps-restore | 한글 또는 영문이 있음 | 끝에 RestoreCaps |
 */
internal object DefaultRules {
    val token: List<TokenRule> = listOf(
        SkipUnmappableRule, // 먼저: 건너뛴 글자 양옆이 이웃이 된다
        JamoBoundaryCommitRule,
        ChunkEndCommitRule,
        CapsRestoreRule, // 마지막: 확정 뒤에 Caps 를 되돌린다
    )
}
