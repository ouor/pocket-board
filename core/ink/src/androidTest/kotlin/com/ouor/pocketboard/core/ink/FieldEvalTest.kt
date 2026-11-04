package com.ouor.pocketboard.core.ink

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.ink.android.MlKitInkModelManager
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.rules.defaultInkRules
import com.ouor.pocketboard.core.ink.segment.InkEngine
import com.ouor.pocketboard.core.ink.segment.InkPipeline
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 현장 사례(src/test/resources/field/cases.tsv)를 실제 ML Kit 으로 전략별로 돌려 글자 오류율(CER)을 표로 낸다(docs/dev/core/ink.md §6).
 * 판정은 하지 않는다(항상 통과). 결과는 `am instrument` 출력과 logcat "pb.ink.eval".
 *
 * 실행(기기에 이 테스트 앱이 ko·en 모델을 처음 한 번 받는다, 와이파이 필요):
 * `gradlew :core:ink:assembleDebugAndroidTest` → `adb install -r core/ink/build/outputs/apk/androidTest/debug/ink-debug-androidTest.apk`
 * → `adb shell am instrument -w [-e strategies rules,raw] [-e dump true] com.ouor.pocketboard.core.ink.test/androidx.test.runner.AndroidJUnitRunner`
 * dump=true 면 사례마다 보고서 JSON 한 줄("REPORT <파일> <json>")을 더 낸다(JVM 재현 테스트용 녹화).
 */
@RunWith(AndroidJUnit4::class)
class FieldEvalTest {
    private class Strategy(val name: String, val mode: SegmentationMode, val config: InkConfig = InkConfig())

    /** 비교할 전략. 이름으로 고른다(-e strategies). legacy = 2026-10 현장 2차 전 기본값(S2 0.3, S5 없음), noStacked = 겹친 음절 나누기 전(ab8f91e), stacked10 = 3f008f4 기본값. */
    private val strategies = listOf(
        Strategy("rules", SegmentationMode.RULES),
        Strategy("noStacked", SegmentationMode.RULES, InkConfig(stackedMinStrokes = 0)),
        Strategy("stacked10", SegmentationMode.RULES, InkConfig(stackedMinStrokes = 10)),
        Strategy("legacy", SegmentationMode.RULES, InkConfig(rightOfGapRatio = 0.3f, topLeftCornerRatio = 0f, stackedMinStrokes = 0)),
        Strategy("raw", SegmentationMode.RAW),
        Strategy("hypothesis", SegmentationMode.HYPOTHESIS),
    )

    @Serializable
    internal class FieldInk(val strokes: List<List<List<Float>>>, val spaces: List<Int>, val report: InkReport) {
        fun items(): List<InkItem> = buildList {
            strokes.forEachIndexed { i, s ->
                repeat(spaces.count { it == i }) { add(InkItem.Space) }
                add(InkItem.Stroke(i.toLong(), InkStroke(s.map { InkPoint(it[0], it[1], it[2].toLong()) })))
            }
            repeat(spaces.count { it == strokes.size }) { add(InkItem.Space) }
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val json = Json { ignoreUnknownKeys = true }

    private fun out(line: String) {
        Log.i(TAG, line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
    }

    @Test
    fun evaluate() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val only = args.getString("strategies")?.split(',')?.toSet()
        val dump = args.getString("dump") == "true"
        val noPre = args.getString("pre") == "none"   // 저장된 앞 문맥(이전 오인식 포함)을 빼고 평가
        val assets = instrumentation.context.assets
        fun labeled(list: String) = assets.open("field/$list").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t').let { (file, truth) -> Triple(file, truth, json.decodeFromString(FieldInk.serializer(), assets.open("field/$file").bufferedReader().readText())) } }
        // 현장 문장(cases) 과 한 음절 안전 확인(singles) 은 CER 을 따로 낸다: cases 숫자를 예전 것과 비교할 수 있게
        val groups = listOf("cases" to labeled("cases.tsv"), "singles" to labeled("singles.tsv"))
        // 정답 없는 현장 기록(회귀 확인용): 전략마다 결과만 낸다("U" 줄)
        val unlabeled = assets.open("field/unlabeled.tsv").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore('\t').let { file -> file to json.decodeFromString(FieldInk.serializer(), assets.open("field/$file").bufferedReader().readText()) } }

        val manager = MlKitInkModelManager(instrumentation.targetContext, CoroutineScope(Dispatchers.Default))
        manager.ensureDownloaded()
        val state = withTimeout(300_000) { manager.state.first { it is ModelState.Ready || it is ModelState.Failed || it is ModelState.Unavailable } }
        check(state == ModelState.Ready) { "models $state" }
        val recognizer = manager.openRecognizer()
        recognizer.warmUp()
        var calls = 0
        val engine = InkEngine { lang, strokes, pre, area -> calls++; (recognizer as InkEngine).recognize(lang, strokes, pre, area) }

        for (s in strategies.filter { only == null || it.name in only }) {
            out("== ${s.name} (${s.mode.id}) ${s.config}")
            for ((group, cases) in groups) {
                var edits = 0; var chars = 0; var editsSp = 0; var charsSp = 0; var ms = 0L; var maxMs = 0L; var extra = 0; var maxExtra = 0
                for ((file, truth, ink) in cases) {
                    val pipeline = InkPipeline(engine, s.config, defaultInkRules(s.config))
                    var report: InkReport? = null
                    calls = 0
                    val t0 = SystemClock.elapsedRealtime()
                    val pre = if (noPre) "" else ink.report.preContext
                    val text = (pipeline.recognize(ink.items(), pre, s.mode) { report = it } as? RecognitionOutcome.Text)?.text.orEmpty()
                    val took = SystemClock.elapsedRealtime() - t0
                    val e = distance(text.filterNot(Char::isWhitespace), truth.filterNot(Char::isWhitespace))
                    val eSp = distance(text.trim(), truth)
                    val x = calls - 2 * (report?.words?.size ?: 0)   // 단어마다 최종 ko·en 2회 밖의 추가 호출
                    extra += x; maxExtra = maxOf(maxExtra, x)
                    edits += e; chars += truth.count { !it.isWhitespace() }; editsSp += eSp; charsSp += truth.length; ms += took; maxMs = maxOf(maxMs, took)
                    out(String.format(Locale.ROOT, "%-24s n=%-3d %5dms +%d  e=%d/%d  %-14s | %s", file, ink.strokes.size, took, x, e, eSp, "\"$text\"", truth))
                    if (dump && report != null) out("REPORT $file ${json.encodeToString(InkReport.serializer(), report!!)}")
                }
                out(String.format(Locale.ROOT, "== %s [%s] CER(공백 무시)=%.3f (%d/%d)  CER(공백 포함)=%.3f  평균 %dms  최대 %dms  추가 호출 평균 %.1f 최대 %d",
                    s.name, group, edits.toFloat() / chars, edits, chars, editsSp.toFloat() / charsSp, ms / cases.size, maxMs, extra.toFloat() / cases.size, maxExtra))
            }
            for ((file, ink) in unlabeled) {
                val text = (InkPipeline(engine, s.config, defaultInkRules(s.config)).recognize(ink.items(), if (noPre) "" else ink.report.preContext, s.mode)
                    as? RecognitionOutcome.Text)?.text.orEmpty()
                out("U ${s.name} $file \"$text\"")
            }
        }
        recognizer.close()
    }

    /** 편집 거리(Levenshtein, 글자 단위). */
    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private companion object { const val TAG = "pb.ink.eval" }
}
