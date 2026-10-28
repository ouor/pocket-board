package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.common.rule.Verdict
import com.ouor.pocketboard.core.ink.InkEngineCall
import com.ouor.pocketboard.core.ink.InkMergeCheck
import com.ouor.pocketboard.core.ink.InkSegmentStep
import com.ouor.pocketboard.core.ink.InkSplitAttempt
import com.ouor.pocketboard.core.ink.InkWordReport

/**
 * 보고서용 획 번호(items 중 InkItem.Stroke 순서, 0부터). 재배치·잘린 획도 첫 점 시각·점 수로 원래 획을 찾는다.
 * shortcut: 첫 점 시각과 점 수가 같은 두 획은 앞 것으로 본다(한 손가락 입력이라 실기기에선 안 생김), 멀티터치 필기를 받으면 InkItem.Stroke.id 로 바꾼다.
 */
internal class StrokeIndex(private val strokes: List<InkStroke>) {
    private val byStart = strokes.indices.groupBy { strokes[it].points.first().t }

    fun of(s: InkStroke): Int = byStart[s.points.first().t]?.firstOrNull { strokes[it].points.size == s.points.size } ?: -1

    /** 원래 획에서 x 로 민 양(재배치). 못 찾으면 0. */
    fun dx(s: InkStroke): Float = of(s).let { if (it < 0) 0f else s.points[0].x - strokes[it].points[0].x }

    fun ids(ink: List<InkStroke>) = ink.map(::of)

    companion object {
        fun of(items: List<InkItem>) = StrokeIndex(items.mapNotNull { (it as? InkItem.Stroke)?.stroke })
    }
}

/** 전송 1회 기록. 보고서를 원할 때만 만든다. */
internal class SendRecorder(items: List<InkItem>) {
    private val index = StrokeIndex.of(items)
    val words = ArrayList<InkWordReport>()

    fun word(strokes: List<InkStroke>) = WordRecorder(index, strokes, words)
}

internal class WordRecorder(private val index: StrokeIndex, private val strokes: List<InkStroke>, private val out: MutableList<InkWordReport>) {
    val segments = ArrayList<InkSegmentStep>()
    val merges = ArrayList<InkMergeCheck>()
    val splits = ArrayList<InkSplitAttempt>()
    private val calls = ArrayList<InkEngineCall>()

    /** 가장 최근 ko·en 인식: (넣은 잉크, ko, en). */
    var lastRead: Triple<List<InkStroke>, List<String>, List<String>>? = null

    fun ids(ink: List<InkStroke>) = index.ids(ink)

    fun call(language: InkLanguage, ink: List<InkStroke>, pre: String, candidates: List<String>) {
        calls += InkEngineCall(language.tag, index.ids(ink), ink.map(index::dx), pre, candidates)
    }

    fun read(ink: List<InkStroke>, pre: String, ko: List<String>, en: List<String>) {
        call(InkLanguage.KO, ink, pre, ko)
        call(InkLanguage.EN, ink, pre, en)
        lastRead = Triple(ink, ko, en)
    }

    fun finish(clusters: List<List<InkStroke>>, chosen: Verdict<String>, text: String) {
        val (ink, ko, en) = lastRead ?: Triple(emptyList(), emptyList(), emptyList())
        // 재배치는 획 순서를 안 바꾸므로 ink[i] 는 strokes[i] 를 민 것
        out += InkWordReport(
            strokes = index.ids(strokes),
            segments = segments,
            clusters = clusters.map(index::ids),
            merges = merges,
            splits = splits,
            finalDx = ink.map(index::dx),
            ko = ko,
            en = en,
            chosen = text,
            candidateRuleId = chosen.ruleId,
            calls = calls,
        )
    }
}
