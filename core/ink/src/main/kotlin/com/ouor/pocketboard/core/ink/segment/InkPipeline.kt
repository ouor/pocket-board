package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.common.rule.Verdict
import com.ouor.pocketboard.core.common.rule.firstMatch
import com.ouor.pocketboard.core.common.rule.transform
import com.ouor.pocketboard.core.ink.InkMergeCheck
import com.ouor.pocketboard.core.ink.InkReport
import com.ouor.pocketboard.core.ink.InkSegmentStep
import com.ouor.pocketboard.core.ink.InkSplitAttempt
import com.ouor.pocketboard.core.ink.InkSplitTry
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.rules.CandidateInput
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.rules.InkRules
import com.ouor.pocketboard.core.ink.rules.MergeInput
import com.ouor.pocketboard.core.ink.rules.RightOfRule
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

    /** [report] 가 있으면 끝에 디버그 보고서를 한 번 넘긴다. null 이면 보고서용 객체를 만들지 않는다. */
    suspend fun recognize(
        items: List<InkItem>, preContext: String, mode: SegmentationMode, report: ((InkReport) -> Unit)? = null,
    ): RecognitionOutcome {
        val rec = report?.let { SendRecorder(items) }
        val ruleIds = LinkedHashSet<String>()
        val out = StringBuilder()
        val trace = StringBuilder(mode.id)
        var word = ArrayList<InkStroke>()
        var anyWord = false
        var anyText = false

        suspend fun flush() {
            if (word.isEmpty()) return
            anyWord = true
            val text = recognizeWord(word, (preContext + out).takeLast(PRE_CONTEXT_MAX), mode, ruleIds, trace, rec?.word(word))
            if (text.isNotEmpty()) { out.append(text); anyText = true }
            word = ArrayList()
        }

        for (item in items) when (item) {
            is InkItem.Stroke -> word += item.stroke
            InkItem.Space -> { flush(); out.append(' ') }
        }
        flush()
        log(trace.toString())
        val outcome = if ((anyWord && !anyText) || out.isEmpty()) RecognitionOutcome.Empty
        else RecognitionOutcome.Text(out.toString(), ruleIds.toList())
        if (rec != null) report(InkReport(mode.id, config, preContext, rec.words, (outcome as? RecognitionOutcome.Text)?.text.orEmpty(), ruleIds.toList()))
        return outcome
    }

    private suspend fun recognizeWord(
        strokes: List<InkStroke>, pre: String, mode: SegmentationMode, ruleIds: MutableSet<String>, trace: StringBuilder,
        w: WordRecorder?,
    ): String {
        trace.append(" / n=").append(strokes.size)
        var clusters: List<List<InkStroke>>
        var chosen: Verdict<String>
        if (mode == SegmentationMode.RAW) {
            clusters = listOf(strokes)
            chosen = read(strokes, pre, w)
        } else {
            val segmented = segmenter.clusters(strokes).also { trace(it, trace, w) }
            val checked = if (mode == SegmentationMode.HYPOTHESIS) HYPOTHESIS_BOUNDARIES else RULES_BOUNDARIES
            clusters = mergeHypotheses(segmented, pre, ruleIds, checked, w)
            chosen = read(relayout(clusters, config.relayoutGapRatio, config.minCharSize), pre, w)
        }
        chosen.ruleId?.let(ruleIds::add)
        // Text 체인은 글자를 고른 바로 뒤(Split 전): Split 의 글자↔묶음 1:1 대응이 인식기가 붙인 앞 공백에 어긋나지 않게
        val fixed = rules.text.transform(TextInput(chosen.value, clusters))
        ruleIds += fixed.ruleIds
        var text = fixed.value.text
        if (mode != SegmentationMode.RAW) split(clusters, text, pre, trace, w)?.let { (parts, ruleId, splitText) ->
            clusters = parts; text = splitText; ruleIds += ruleId
        }
        w?.finish(clusters, chosen, text)
        return text
    }

    /** ko·en 병렬 인식 → Candidate 체인. */
    private suspend fun read(ink: List<InkStroke>, pre: String, w: WordRecorder?): Verdict<String> {
        val area = ink.bounds()
        val (ko, en) = coroutineScope {
            val ko = async { engine.recognize(InkLanguage.KO, ink, pre, area) }
            val en = async { engine.recognize(InkLanguage.EN, ink, pre, area) }
            ko.await() to en.await()
        }
        w?.read(ink, pre, ko, en)
        return rules.candidate.firstMatch(CandidateInput(ko, en), ::defaultCandidate)
    }

    /**
     * Split 단계: 결과 글자와 묶음이 1:1 일 때 묶음마다 Split 체인을 묻고, 목표 글자가 나오면 앞에서부터 그 글자로
     * 읽히는 가장 짧은 조각으로 자른다(직전 조각 길이부터 시도). 묶음 전체가 목표 글자 조각들로 나뉠 때만 채택.
     * 조각은 하나씩 목표 글자로 확인했으므로 다시 인식하지 않는다: 나뉜 묶음은 목표×조각 수, 나머지는 원래 글자.
     * 현장: 확인된 ㅋ 조각 4개를 재배치해 다시 읽으니 ML Kit 이 "ㅋㅋㅋ"로 하나 빠뜨렸다.
     * 보통 입력은 체인이 null 이라 추가 호출이 없다. 호출은 단어당 [InkConfig.maxSplitCalls] 까지.
     */
    private suspend fun split(
        clusters: List<List<InkStroke>>, text: String, pre: String, trace: StringBuilder, w: WordRecorder?,
    ): Triple<List<List<InkStroke>>, String, String>? {
        if (text.length != clusters.size) return null
        var calls = 0

        suspend fun cut(c: List<InkStroke>, target: String, tries: MutableList<InkSplitTry>?): List<List<InkStroke>>? {
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
                    val ko = engine.recognize(InkLanguage.KO, chunk, pre, chunk.bounds())
                    w?.call(InkLanguage.KO, chunk, pre, ko)
                    tries?.add(InkSplitTry(start, len, ko.firstOrNull()))
                    if (ko.firstOrNull()?.trim() == target) { found = len; break }
                }
                if (found == 0) {
                    // 끝에 남은 짧은 조각(현장: 7획 중 마지막 1획)은 버리지 말고 마지막 조각에 붙인다
                    if (parts.isEmpty() || rem >= last) return null
                    parts[parts.lastIndex] = c.subList(start - last, c.size)
                    break
                }
                parts += c.subList(start, start + found)
                start += found
                last = found
            }
            return parts.takeIf { it.size > 1 }
        }

        val out = ArrayList<List<InkStroke>>()
        val outText = StringBuilder()
        var ruleId: String? = null
        for ((i, c) in clusters.withIndex()) {
            val v = rules.split.firstMatch(SplitInput(c, text[i].toString())) { "" }
            val tries = if (w != null && v.value.isNotEmpty()) ArrayList<InkSplitTry>() else null
            val parts = if (v.value.isEmpty()) null else cut(c, v.value, tries)
            if (w != null && tries != null) w.splits += InkSplitAttempt(i, v.value, tries, parts?.map(w::ids))
            if (v.value.isNotEmpty()) trace.append(" | split#").append(i).append(' ').append(v.value).append(" x").append(parts?.size ?: 0)
            if (parts != null) { out += parts; ruleId = v.ruleId; outText.append(v.value.repeat(parts.size)) } else { out += c; outText.append(text[i]) }
        }
        if (calls > 0) trace.append(" calls=").append(calls)
        return ruleId?.let { Triple(out, it, outText.toString()) }
    }

    /** 획마다 "번호 N(새 글자, 규칙)/S(같은 글자) in=상자 안 비율 p=쉼ms g=간격dp". 측정은 그 획을 판정할 때의 묶음 기준. */
    private fun trace(clusters: List<Cluster>, sb: StringBuilder, w: WordRecorder?) {
        var k = 0
        var prev: List<InkStroke> = emptyList()
        for (c in clusters) for ((j, s) in c.strokes.withIndex()) {
            val current = if (j == 0) prev else c.strokes.subList(0, j)
            sb.append(" | ").append(k++).append(if (j == 0) " N " + c.startedBy?.substringAfter('/') else " S")
            val m = if (current.isNotEmpty()) SegmentInput(current, s) else null
            if (m != null) {
                sb.append(" in=").append("%.2f".format(Locale.ROOT, m.insideRatio(config))).append(" p=").append(m.pauseMs)
                    .append(" g=").append(m.gap.toInt())
            }
            w?.segments?.add(InkSegmentStep(j == 0, if (j == 0) c.startedBy else null, m?.insideRatio(config), m?.pauseMs, m?.gap))
            if (j == c.strokes.lastIndex) prev = c.strokes
        }
    }

    /**
     * [checked] 규칙으로 끊긴 경계마다 앞 묶음과 합쳐 ko 로 인식해 보고 Merge 규칙이 합치라면 합친다.
     * 묶음 전체로 읽으므로 이어 쓴 가|마 는 "가마"(음절 2개)라 안 합친다. 획마다 하면 가+ㅁ 첫 세로획이 "개"로 읽혀 잘못 합친다.
     */
    private suspend fun mergeHypotheses(
        clusters: List<Cluster>, pre: String, ruleIds: MutableSet<String>, checked: Set<String>, w: WordRecorder?,
    ): List<List<InkStroke>> {
        val out = ArrayList<Cluster>()
        var calls = 0
        for (i in clusters.indices) {
            val c = clusters[i]
            val prev = out.lastOrNull()
            if (prev != null && c.startedBy in checked && calls < config.maxHypothesisCalls) {
                calls++
                val merged = prev.strokes + c.strokes
                val ko = engine.recognize(InkLanguage.KO, merged, pre, merged.bounds())
                val v = rules.merge.firstMatch(MergeInput(ko)) { false }
                if (w != null) {
                    w.call(InkLanguage.KO, merged, pre, ko)
                    w.merges += InkMergeCheck(i, c.startedBy.orEmpty(), ko, v.value)
                }
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

        /** 합침을 확인할 경계: B 는 모음이 떨어져 시작한 right-of 만, C 는 같은 자리 재시작도. */
        private val RULES_BOUNDARIES = setOf(RightOfRule.ID)
        private val HYPOTHESIS_BOUNDARIES = setOf(RightOfRule.ID, SameSpotRestartRule.ID)
    }
}
