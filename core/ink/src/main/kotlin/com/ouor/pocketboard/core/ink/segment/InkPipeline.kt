package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.common.rule.firstMatch
import com.ouor.pocketboard.core.common.rule.transform
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.rules.CandidateInput
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.rules.InkRules
import com.ouor.pocketboard.core.ink.rules.MergeInput
import com.ouor.pocketboard.core.ink.rules.SameSpotRestartRule
import com.ouor.pocketboard.core.ink.rules.TextInput
import com.ouor.pocketboard.core.ink.rules.defaultCandidate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class InkLanguage(val tag: String) { KO("ko"), EN("en") }

/** 실제 인식기(ML Kit) 경계. 테스트에서는 가짜로 바꾼다. */
fun interface InkEngine {
    /** 후보 문자열(인식기 순서 그대로). 실패는 빈 목록. [area]는 [strokes]의 상자. */
    suspend fun recognize(language: InkLanguage, strokes: List<InkStroke>, preContext: String, area: Box): List<String>
}

/**
 * 전송 1회 분량 인식(ink.md §4.1): 단어 나누기 → 분할 → 재배치 → ko·en 병렬 인식 → Candidate → Text.
 * 단어 하나가 실패하면 그 단어만 빠지고, 모든 단어가 실패해야 [RecognitionOutcome.Empty].
 */
class InkPipeline(private val engine: InkEngine, private val config: InkConfig, private val rules: InkRules) {
    private val segmenter = RuleSegmenter(rules.segment)

    suspend fun recognize(items: List<InkItem>, preContext: String, mode: SegmentationMode): RecognitionOutcome {
        val ruleIds = LinkedHashSet<String>()
        val out = StringBuilder()
        var word = ArrayList<InkStroke>()
        var anyWord = false
        var anyText = false

        suspend fun flush() {
            if (word.isEmpty()) return
            anyWord = true
            val text = recognizeWord(word, (preContext + out).takeLast(PRE_CONTEXT_MAX), mode, ruleIds)
            if (text.isNotEmpty()) { out.append(text); anyText = true }
            word = ArrayList()
        }

        for (item in items) when (item) {
            is InkItem.Stroke -> word += item.stroke
            InkItem.Space -> { flush(); out.append(' ') }
        }
        flush()
        return if ((anyWord && !anyText) || out.isEmpty()) RecognitionOutcome.Empty
        else RecognitionOutcome.Text(out.toString(), ruleIds.toList())
    }

    private suspend fun recognizeWord(
        strokes: List<InkStroke>, pre: String, mode: SegmentationMode, ruleIds: MutableSet<String>,
    ): String {
        val clusters: List<List<InkStroke>> = when (mode) {
            SegmentationMode.RAW -> listOf(strokes)
            SegmentationMode.RULES -> segmenter.clusters(strokes).also { c -> c.mapNotNullTo(ruleIds) { it.startedBy } }.map { it.strokes }
            SegmentationMode.HYPOTHESIS -> mergeHypotheses(segmenter.clusters(strokes), pre, ruleIds)
        }
        val ink = if (mode == SegmentationMode.RAW) strokes else relayout(clusters, config.relayoutGapRatio, config.minCharSize)
        val area = ink.bounds()
        val (ko, en) = coroutineScope {
            val ko = async { engine.recognize(InkLanguage.KO, ink, pre, area) }
            val en = async { engine.recognize(InkLanguage.EN, ink, pre, area) }
            ko.await() to en.await()
        }
        val chosen = rules.candidate.firstMatch(CandidateInput(ko, en), ::defaultCandidate)
        chosen.ruleId?.let(ruleIds::add)
        if (chosen.value.isEmpty()) return ""
        val fixed = rules.text.transform(TextInput(chosen.value, clusters))
        ruleIds += fixed.ruleIds
        return fixed.value.text
    }

    /** 단계 C: 같은 자리 재시작으로 끊긴 경계마다 합쳐 인식해 보고 Merge 규칙이 합치라면 합친다. */
    private suspend fun mergeHypotheses(clusters: List<Cluster>, pre: String, ruleIds: MutableSet<String>): List<List<InkStroke>> {
        val out = ArrayList<Cluster>()
        var calls = 0
        for (c in clusters) {
            val prev = out.lastOrNull()
            if (prev != null && c.startedBy == SameSpotRestartRule.ID && calls < config.maxHypothesisCalls) {
                calls++
                val merged = prev.strokes + c.strokes
                val v = rules.merge.firstMatch(MergeInput(engine.recognize(InkLanguage.KO, merged, pre, merged.bounds()))) { false }
                if (v.value) {
                    out[out.lastIndex] = prev.copy(strokes = merged)
                    v.ruleId?.let(ruleIds::add)
                    continue
                }
            }
            out += c
        }
        out.mapNotNullTo(ruleIds) { it.startedBy }
        return out.map { it.strokes }
    }

    companion object {
        /** ML Kit 은 마지막 20자만 쓴다(ink.md §2.3). */
        const val PRE_CONTEXT_MAX = 20
    }
}
