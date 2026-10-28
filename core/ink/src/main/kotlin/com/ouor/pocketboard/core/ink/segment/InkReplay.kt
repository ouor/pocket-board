package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.ink.InkReport
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.rules.InkRules
import com.ouor.pocketboard.core.ink.rules.defaultInkRules

/**
 * 저장한 보고서의 인식기 호출([com.ouor.pocketboard.core.ink.InkWordReport.calls])을 그대로 돌려주는 엔진(현장 사례 재현, JVM 테스트).
 * 언어·획 번호·획별 dx·앞 문맥이 같은 호출이면 기록된 후보, 없으면 빈 목록을 주고 [misses] 에 센다.
 * 규칙을 바꿔 보고서에 없던 묶음을 읽게 되면 miss 가 난다(그 호출만 실패로 본다).
 */
class RecordedInkEngine(items: List<InkItem>, report: InkReport) : InkEngine {
    private val index = StrokeIndex.of(items)
    private val answers = report.words.flatMap { it.calls }.associate { Key(it.language, it.strokes, it.dx, it.preContext) to it.candidates }

    var misses = 0
        private set

    override suspend fun recognize(language: InkLanguage, strokes: List<InkStroke>, preContext: String, area: Box): List<String> =
        answers[Key(language.tag, index.ids(strokes), strokes.map(index::dx), preContext)] ?: emptyList<String>().also { misses++ }

    private data class Key(val language: String, val strokes: List<Int>, val dx: List<Float>, val pre: String)
}

/**
 * 저장한 [items](원래 좌표)와 [report]로 파이프라인을 다시 돌려 새 보고서를 돌려준다. 설정·방식·앞 문맥은 보고서 값.
 * [rules] 를 바꿔 넣으면 같은 현장 입력에 새 규칙을 시험할 수 있다(인식기 답은 기록된 것만).
 */
suspend fun replayInk(
    items: List<InkItem>, report: InkReport, engine: InkEngine = RecordedInkEngine(items, report),
    rules: InkRules = defaultInkRules(report.config),
): InkReport {
    var out: InkReport? = null
    InkPipeline(engine, report.config, rules).recognize(items, report.preContext, SegmentationMode.entries.first { it.id == report.mode }) { out = it }
    return out!!
}
