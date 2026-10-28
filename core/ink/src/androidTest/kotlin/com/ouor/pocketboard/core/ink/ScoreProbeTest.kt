package com.ouor.pocketboard.core.ink

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.segment.bounds
import com.ouor.pocketboard.core.ink.segment.relayout
import com.ouor.pocketboard.core.ink.segment.translateX
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 조사용(docs/research/ink-rerank.md). 실제 ML Kit 후보 점수(RecognitionCandidate.score)·호출 지연·병렬 효과를 잰다. 판정 없음.
 * `am instrument -w -e class com.ouor.pocketboard.core.ink.ScoreProbeTest [-e parts 1,2,3,4] ...` (모델은 FieldEvalTest 가 받아 둔 것을 쓴다)
 * 4 의 "WIN" 줄은 PC 실험(scratch 의 rerank.py)의 입력. 2026-10-28 결과: score 는 ko·en 모두 null.
 */
@RunWith(AndroidJUnit4::class)
class ScoreProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val json = Json { ignoreUnknownKeys = true }

    private fun out(line: String) {
        Log.i("pb.ink.probe", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
    }

    private fun client(tag: String): DigitalInkRecognizer {
        val model = DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)!!).build()
        return DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }

    private fun ink(strokes: List<InkStroke>): Pair<Ink, WritingArea> {
        val a = strokes.bounds()
        val ink = Ink.builder().apply {
            for (s in strokes) addStroke(Ink.Stroke.builder().apply {
                for (p in s.points) addPoint(Ink.Point.create(p.x - a.left, p.y - a.top, p.t))
                if (s.points.size == 1) s.points[0].let { addPoint(Ink.Point.create(it.x - a.left, it.y - a.top, it.t)) }
            }.build())
        }.build()
        return ink to WritingArea(maxOf(a.width, 1f), maxOf(a.height, 1f))
    }

    private suspend fun rec(c: DigitalInkRecognizer, strokes: List<InkStroke>, pre: String, area: Boolean = true): List<Pair<String, Float?>> {
        val (ink, wa) = ink(strokes)
        val ctx = RecognitionContext.builder().setPreContext(pre).apply { if (area) setWritingArea(wa) }.build()
        return c.recognize(ink, ctx).await().candidates.map { it.text to it.score }
    }

    private fun fmt(c: List<Pair<String, Float?>>, n: Int = 10) = c.take(n).joinToString(" / ") { "\"${it.first}\":${it.second?.let { s -> "%.3f".format(Locale.ROOT, s) }}" }

    private fun cases() = instrumentation.context.assets.let { a ->
        a.open("field/cases.tsv").bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
            it.split('\t').let { (f, t) -> Triple(f, t, json.decodeFromString(FieldEvalTest.FieldInk.serializer(), a.open("field/$f").bufferedReader().readText())) }
        }
    }

    private fun strokes(ink: FieldEvalTest.FieldInk) = ink.items().mapNotNull { (it as? InkItem.Stroke)?.stroke }

    @Test
    fun probe() = runBlocking {
        val ko = client("ko"); val en = client("en")
        val warm = strokes(cases().first().third).take(2)
        repeat(2) { rec(ko, warm, ""); rec(en, warm, "") }
        val cases = cases()
        val parts = InstrumentationRegistry.getArguments().getString("parts")?.split(',')?.toSet() ?: setOf("1", "2", "3", "4")

        if ("1" in parts) run {
            // 1. 녹화된 최종 인식 잉크(재배치 뒤)를 다시 넣어 점수를 본다
            out("== 1. scores (final relaid-out ink per word)")
            for ((file, truth, fi) in cases) {
                val all = strokes(fi)
                for (w in fi.report.words) {
                    val final = w.strokes.mapIndexed { i, id -> all[id].translateX(w.finalDx[i]) }
                    val t0 = SystemClock.elapsedRealtime()
                    val k = rec(ko, final, fi.report.preContext)
                    val t1 = SystemClock.elapsedRealtime()
                    val e = rec(en, final, fi.report.preContext)
                    val t2 = SystemClock.elapsedRealtime()
                    out("$file [$truth] n=${final.size} ko ${t1 - t0}ms en ${t2 - t1}ms")
                    out("  ko: ${fmt(k)}")
                    out("  en: ${fmt(e, 3)}")
                }
            }
        }

        if ("2" in parts) run {
            // 2. 지연: 획 수별(묶음 앞에서부터 늘림), WritingArea 유무. 3회 중앙값
            out("== 2. latency by strokes (median of 3), ko, area / no-area")
            for ((file, _, fi) in cases.filter { it.third.strokes.size >= 30 }) {
                val all = strokes(fi)
                for (n in listOf(1, 2, 3, 5, 8, 12, 20, 30, all.size).filter { it <= all.size }.distinct()) {
                    val part = all.take(n)
                    val withArea = List(3) { val t = SystemClock.elapsedRealtime(); rec(ko, part, ""); SystemClock.elapsedRealtime() - t }.sorted()[1]
                    val noArea = List(3) { val t = SystemClock.elapsedRealtime(); rec(ko, part, "", area = false); SystemClock.elapsedRealtime() - t }.sorted()[1]
                    out("$file n=$n  area ${withArea}ms  no-area ${noArea}ms")
                }
            }
        }

        if ("3" in parts) run {
            // 3. 병렬: 한 사례의 묶음(최종 clusters)마다 ko 1회 = 분할 탐색의 가설 호출 묶음. 직렬 / 한 클라이언트 동시 / 2·4 클라이언트
            out("== 3. parallel (cluster-sized calls)")
            val extra = List(3) { client("ko") }
            for ((file, _, fi) in cases.filter { it.third.strokes.size >= 30 }) {
                val all = strokes(fi)
                val chunks = fi.report.words.flatMap { w -> w.clusters.map { c -> c.map { all[it] } } } +
                    fi.report.words.flatMap { w -> w.clusters.windowed(2).map { p -> p.flatten().map { all[it] } } }
                suspend fun timed(block: suspend () -> Unit) = SystemClock.elapsedRealtime().let { t -> block(); SystemClock.elapsedRealtime() - t }
                repeat(2) {
                    val serial = timed { chunks.forEach { rec(ko, it, "") } }
                    val oneClient = timed { coroutineScope { chunks.map { async { rec(ko, it, "") } }.awaitAll() } }
                    val two = timed { coroutineScope { chunks.mapIndexed { i, c -> async { rec(if (i % 2 == 0) ko else extra[0], c, "") } }.awaitAll() } }
                    val four = timed { coroutineScope { chunks.mapIndexed { i, c -> async { rec((listOf(ko) + extra)[i % 4], c, "") } }.awaitAll() } }
                    out("$file calls=${chunks.size} serial ${serial}ms  1-client-async ${oneClient}ms  2-clients ${two}ms  4-clients ${four}ms")
                }
            }
            extra.forEach { it.close() }
        }

        // 4. 묶음 창(연속 1~3 묶음, 재배치) 마다 ko n-best: PC 에서 묶음 단위 격자 + 언어 모델 재순위의 상한·효과를 잰다
        if ("4" in parts) run {
            val config = InkConfig()
            for ((file, _, fi) in cases) {
                val all = strokes(fi)
                val recorded = json.decodeFromString(InkReport.serializer(), instrumentation.context.assets.open("field/recorded/$file").bufferedReader().readText())
                val sb = StringBuilder("[")
                var oneMs = 0L
                for ((wi, w) in recorded.words.withIndex()) {
                    val cl = w.clusters.map { c -> c.map { all[it] } }
                    for (len in 1..3) for (start in 0..cl.size - len) {
                        val win = cl.subList(start, start + len)
                        val t = SystemClock.elapsedRealtime()
                        val k = rec(ko, relayout(win, config.relayoutGapRatio, config.minCharSize), recorded.preContext)
                        if (len == 1) oneMs += SystemClock.elapsedRealtime() - t
                        if (sb.length > 1) sb.append(',')
                        sb.append("{\"w\":").append(wi).append(",\"s\":").append(start).append(",\"n\":").append(len).append(",\"ko\":")
                            .append(k.joinToString(",", "[", "]") { kotlinx.serialization.json.JsonPrimitive(it.first).toString() }).append('}')
                    }
                }
                out("WIN $file ${oneMs}ms ${sb.append(']')}")
            }
        }
        ko.close(); en.close()
    }
}
