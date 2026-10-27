package com.ouor.pocketboard.core.ink.rules

import com.ouor.pocketboard.core.common.rule.Verdict
import com.ouor.pocketboard.core.common.rule.firstMatch
import org.junit.Assert.assertEquals
import org.junit.Test

/** Candidate 체인 전체를 순서대로 표로 확인한다(규칙끼리 순서가 의미를 가지므로). */
class CandidateRulesTest {
    private val chain = defaultInkRules().candidate

    private fun choose(ko: List<String>, en: List<String>) = chain.firstMatch(CandidateInput(ko, en), ::defaultCandidate)

    @Test
    fun table() {
        val cases = listOf(
            Triple(listOf(), listOf(""), Verdict("", "candidate/empty")),
            Triple(listOf("안녕", "안냥"), listOf("ott"), Verdict("안녕", "candidate/ko-hangul")),
            Triple(listOf("slack에"), listOf("slackol"), Verdict("slack에", "candidate/ko-hangul")),
            Triple(listOf("OK", "0K"), listOf("OK"), Verdict("OK", "candidate/en-in-ko")),
            Triple(listOf("ㅋㅋ"), listOf("77"), Verdict("ㅋㅋ", "candidate/ko-jamo")),
            Triple(listOf("ㄹ6"), listOf("slack"), Verdict<String>("slack", null)),
            Triple(listOf("ㄹ6"), listOf("café"), Verdict<String>("ㄹ6", null)),
        )
        for ((ko, en, expected) in cases) assertEquals("$ko / $en", expected, choose(ko, en))
    }
}
