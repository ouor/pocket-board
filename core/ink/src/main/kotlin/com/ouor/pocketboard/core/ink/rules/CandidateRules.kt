package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.common.rule.Rule

// 출력 "" = 인식 실패(Empty, D9).

/** C0: 두 인식기 모두 쓸 만한 후보가 없음 → 실패. */
object EmptyCandidateRule : Rule<CandidateInput, String> {
    override val id = "candidate/empty"
    override fun apply(input: CandidateInput) =
        if (input.ko.firstOrNull().isNullOrBlank() && input.en.firstOrNull().isNullOrBlank()) "" else null
}

/** C1: ko 1순위에 완성 한글 음절이 있음 → ko 1순위("slack에"처럼 섞인 단어 포함). */
object KoHangulRule : Rule<CandidateInput, String> {
    override val id = "candidate/ko-hangul"
    override fun apply(input: CandidateInput) =
        input.ko.firstOrNull()?.takeIf { s -> s.any { it.isHangulSyllable() } }
}

/** C2: en 1순위가 ko 후보 목록에도 있음 → en 1순위(두 모델이 동의한 라틴 단어). */
object EnInKoRule : Rule<CandidateInput, String> {
    override val id = "candidate/en-in-ko"
    override fun apply(input: CandidateInput) =
        input.en.firstOrNull()?.takeIf { it.isNotBlank() && it in input.ko }
}

/** C3: ko 1순위가 한글 자모만 → ko 1순위("ㅋㅋ", S3 시나리오). */
object KoJamoRule : Rule<CandidateInput, String> {
    override val id = "candidate/ko-jamo"
    override fun apply(input: CandidateInput) =
        input.ko.firstOrNull()?.takeIf { s -> s.isNotEmpty() && s.all { it.isHangulJamo() } }
}

/** C4(기본, 규칙 아님): en 1순위가 ASCII 면 en, 아니면 ko 1순위, 그것도 없으면 en 1순위. */
fun defaultCandidate(input: CandidateInput): String {
    val en = input.en.firstOrNull().orEmpty()
    val ko = input.ko.firstOrNull().orEmpty()
    return when {
        en.isNotBlank() && en.all { it.code < 128 } -> en
        ko.isNotBlank() -> ko
        else -> en
    }
}
