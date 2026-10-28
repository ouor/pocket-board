package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.common.rule.Verdict
import com.ouor.pocketboard.core.common.rule.firstMatch
import com.ouor.pocketboard.core.common.rule.transform
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.rules.CandidateInput
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.rules.InkRules
import com.ouor.pocketboard.core.ink.rules.MergeInput
import com.ouor.pocketboard.core.ink.rules.SameSpotRestartRule
import com.ouor.pocketboard.core.ink.rules.SegmentInput
import com.ouor.pocketboard.core.ink.rules.SplitInput
import com.ouor.pocketboard.core.ink.rules.TextInput
import com.ouor.pocketboard.core.ink.rules.defaultCandidate
import com.ouor.pocketboard.core.ink.rules.gap
import com.ouor.pocketboard.core.ink.rules.insideRatio
import com.ouor.pocketboard.core.ink.rules.pauseMs
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class InkLanguage(val tag: String) { KO("ko"), EN("en") }

/** 실제 인식기(ML Kit) 경계. 테스트에서는 가짜로 바꾼다. */
fun interface InkEngine {
    /** 후보 문자열(인식기 순서 그대로). 실패는 빈 목록. [area]는 [strokes]의 상자. */
    suspend fun recognize(language: InkLanguage, strokes: List<InkStroke>, preContext: String, area: Box): List<String>
}

/**
 * 전송 1회 분량 인식(ink.md §4.1): 단어 나누기 → 분할 → 재배치 → ko·en 병렬 인식 → Candidate → (Split 이면 다시 나눠 재인식) → Text.
 * 단어 하나가 실패하면 그 단어만 빠지고, 모든 단어가 실패해야 [RecognitionOutcome.Empty].
 */
class InkPipeline(
    private val engine: InkEngine,
    private val config: InkConfig,
    private val rules: InkRules,
    /** 전송마다 한 줄(분할 판정·측정값, 다시 나눈 결과). 기기에서는 Log "pb.ink.segment". */
    private val log: (String) -> Unit = {},
) {
    private val segmenter = RuleSegmenter(rules.segment)

    suspend fun recognize(items: List<InkItem>, preContext: String, mode: SegmentationMode): RecognitionOutcome {
        val ruleIds = LinkedHashSet<String>()
        val out = StringBuilder()
        val trace = StringBuilder(mode.id)
        var word = ArrayList<InkStroke>()
        var anyWord = false
        var anyText = false

        suspend fun flush() {
            if (word.isEmpty()) return
            anyWord = true
            val text = recognizeWord(word, (preContext + out).takeLast(PRE_CONTEXT_MAX), mode, ruleIds, trace)
            if (text.isNotEmpty()) { out.append(text); anyText = true }
            word = ArrayList()
        }

        for (item in items) when (item) {
            is InkItem.Stroke -> word += item.stroke
            InkItem.Space -> { flush(); out.append(' ') }
        }
        flush()
        log(trace.toString())
        return if ((anyWord && !anyText) || out.isEmpty()) RecognitionOutcome.Empty
        else RecognitionOutcome.Text(out.toString(), ruleIds.toList())
    }

    private suspend fun recognizeWord(
        strokes: List<InkStroke>, pre: String, mode: SegmentationMode, ruleIds: MutableSet<String>, trace: StringBuilder,
    ): String {
        trace.append(" / n=").append(strokes.size)
        if (mode == SegmentationMode.RAW) return finish(listOf(strokes), read(strokes, pre), ruleIds)
        val segmented = segmenter.clusters(strokes).also { trace(it, trace) }
        var clusters: List<List<InkStroke>> = when (mode) {
            SegmentationMode.HYPOTHESIS -> mergeHypotheses(segmented, pre, ruleIds)
            else -> segmented.also { c -> c.mapNotNullTo(ruleIds) { it.startedBy } }.map { it.strokes }
        }
        var chosen = read(relayout(clusters, config.relayoutGapRatio, config.minCharSize), pre)
        split(clusters, chosen.value, pre, trace)?.let { (parts, ruleId) ->
            val again = read(relayout(parts, config.relayoutGapRatio, config.minCharSize), pre)
            if (again.value.isNotEmpty()) { clusters = parts; chosen = again; ruleIds += ruleId }
        }
        return finish(clusters, chosen, ruleIds)
    }

    /** ko·en 병렬 인식 → Candidate 체인. */
    private suspend fun read(ink: List<InkStroke>, pre: String): Verdict<String> {
        val area = ink.bounds()
        val (ko, en) = coroutineScope {
            val ko = async { engine.recognize(InkLanguage.KO, ink, pre, area) }
            val en = async { engine.recognize(InkLanguage.EN, ink, pre, area) }
            ko.await() to en.await()
        }
        return rules.candidate.firstMatch(CandidateInput(ko, en), ::defaultCandidate)
    }

    private fun finish(clusters: List<List<InkStroke>>, chosen: Verdict<String>, ruleIds: MutableSet<String>): String {
        chosen.ruleId?.let(ruleIds::add)
        if (chosen.value.isEmpty()) return ""
        val fixed = rules.text.transform(TextInput(chosen.value, clusters))
        ruleIds += fixed.ruleIds
        return fixed.value.text
    }

    /**
     * Split 단계: 결과 글자와 묶음이 1:1 일 때 묶음마다 Split 체인을 묻고, 목표 글자가 나오면 앞에서부터 그 글자로
     * 읽히는 가장 짧은 조각으로 자른다(직전 조각 길이부터 시도). 묶음 전체가 목표 글자 조각들로 나뉠 때만 채택.
     * 보통 입력은 체인이 null 이라 추가 호출이 없다. 호출은 단어당 [InkConfig.maxSplitCalls] 까지.
     */
    private suspend fun split(
        clusters: List<List<InkStroke>>, text: String, pre: String, trace: StringBuilder,
    ): Pair<List<List<InkStroke>>, String>? {
        if (text.length != clusters.size) return null
        var calls = 0

        suspend fun cut(c: List<InkStroke>, target: String): List<List<InkStroke>>? {
            val parts = ArrayList<List<InkStroke>>()
            var start = 0
            var last = 0
            while (start < c.size) {
                val rem = c.size - start
                var found = 0
                for (len in (listOf(last) + (1..rem)).filter { it in 1..rem }.distinct()) {
                    if (calls >= config.maxSplitCalls) return null
                    calls++
                    val chunk = c.subList(start, start + len)
                    if (engine.recognize(InkLanguage.KO, chunk, pre, chunk.bounds()).firstOrNull()?.trim() == target) { found = len; break }
                }
                if (found == 0) return null
                parts += c.subList(start, start + found)
                start += found
                last = found
            }
            return parts.takeIf { it.size > 1 }
        }

        val out = ArrayList<List<InkStroke>>()
        var ruleId: String? = null
        for ((i, c) in clusters.withIndex()) {
            val v = rules.split.firstMatch(SplitInput(c, text[i].toString())) { "" }
            val parts = if (v.value.isEmpty()) null else cut(c, v.value)
            if (v.value.isNotEmpty()) trace.append(" | split#").append(i).append(' ').append(v.value).append(" x").append(parts?.size ?: 0)
            if (parts != null) { out += parts; ruleId = v.ruleId } else out += c
        }
        if (calls > 0) trace.append(" calls=").append(calls)
        return ruleId?.let { out to it }
    }

    /** 획마다 "번호 N(새 글자, 규칙)/S(같은 글자) in=상자 안 비율 p=쉼ms g=간격dp". 측정은 그 획을 판정할 때의 묶음 기준. */
    private fun trace(clusters: List<Cluster>, sb: StringBuilder) {
        var k = 0
        var prev: List<InkStroke> = emptyList()
        for (c in clusters) for ((j, s) in c.strokes.withIndex()) {
            val current = if (j == 0) prev else c.strokes.subList(0, j)
            sb.append(" | ").append(k++).append(if (j == 0) " N " + c.startedBy?.substringAfter('/') else " S")
            if (current.isNotEmpty()) {
                val m = SegmentInput(current, s)
                sb.append(" in=").append("%.2f".format(Locale.ROOT, m.insideRatio(config))).append(" p=").append(m.pauseMs)
                    .append(" g=").append(m.gap.toInt())
            }
            if (j == c.strokes.lastIndex) prev = c.strokes
        }
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
