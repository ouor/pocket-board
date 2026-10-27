package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.common.rule.Rule
import com.ouor.pocketboard.core.common.rule.firstMatch
import com.ouor.pocketboard.core.ink.Segmenter
import com.ouor.pocketboard.core.ink.rules.Boundary
import com.ouor.pocketboard.core.ink.rules.SegmentInput

/** 글자 묶음. [startedBy]는 이 묶음을 새로 시작하게 한 Segment 규칙 id(F30 기록, 단계 C 후보 판단). */
data class Cluster(val strokes: List<InkStroke>, val startedBy: String?)

/** Segment 규칙 체인(판정형). 획 순서대로 "현재 묶음에 붙는다 / 새 글자"를 정한다. 기본은 SAME_CHAR. */
class RuleSegmenter(private val rules: List<Rule<SegmentInput, Boundary>>) : Segmenter {

    override fun segment(strokes: List<InkStroke>) = clusters(strokes).map { it.strokes }

    fun clusters(strokes: List<InkStroke>): List<Cluster> {
        val out = ArrayList<Cluster>()
        var current = ArrayList<InkStroke>()
        var startedBy: String? = null
        for (stroke in strokes) {
            val v = rules.firstMatch(SegmentInput(current, stroke)) { Boundary.SAME_CHAR }
            if (v.value == Boundary.NEW_CHAR && current.isNotEmpty()) {
                out += Cluster(current, startedBy)
                current = ArrayList()
            }
            if (current.isEmpty()) startedBy = v.ruleId
            current += stroke
        }
        if (current.isNotEmpty()) out += Cluster(current, startedBy)
        return out
    }
}
