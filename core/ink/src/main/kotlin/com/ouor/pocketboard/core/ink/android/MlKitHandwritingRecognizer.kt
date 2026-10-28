package com.ouor.pocketboard.core.ink.android

import android.util.Log
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.ink.HandwritingRecognizer
import com.ouor.pocketboard.core.ink.InkReport
import com.ouor.pocketboard.core.ink.InkSettings
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.ink.rules.defaultInkRules
import com.ouor.pocketboard.core.ink.segment.Box
import com.ouor.pocketboard.core.ink.segment.InkEngine
import com.ouor.pocketboard.core.ink.segment.InkLanguage
import com.ouor.pocketboard.core.ink.segment.InkPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** 화면 수명 동안의 인식기. ko·en 클라이언트를 하나씩 들고 있다가 [close]에서 닫는다. */
internal class MlKitHandwritingRecognizer(
    models: Map<InkLanguage, DigitalInkRecognitionModel>?,
    private val modelState: StateFlow<ModelState>,
    private val settings: StateFlow<InkSettings>,
) : HandwritingRecognizer, InkEngine {

    private val clients: Map<InkLanguage, DigitalInkRecognizer> = try {
        models.orEmpty().mapValues { (_, m) -> DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build()) }
    } catch (e: Throwable) {
        Log.e(TAG, "getClient", e); emptyMap()
    }

    private val ready get() = modelState.value == ModelState.Ready && clients.size == InkLanguage.entries.size

    override suspend fun warmUp() {
        if (!ready) return
        val dot = listOf(InkStroke(listOf(InkPoint(0f, 0f, 0), InkPoint(10f, 10f, 16))))
        withContext(Dispatchers.Default) {
            for (lang in InkLanguage.entries) recognize(lang, dot, "", Box(0f, 0f, 10f, 10f))
        }
    }

    override suspend fun recognize(items: List<InkItem>, preContext: String) = run(items, preContext, null)

    override suspend fun recognize(items: List<InkItem>, preContext: String, report: (InkReport) -> Unit) = run(items, preContext, report)

    private suspend fun run(items: List<InkItem>, preContext: String, report: ((InkReport) -> Unit)?): RecognitionOutcome {
        if (!ready) return RecognitionOutcome.ModelNotReady
        val s = settings.value   // 전송마다 현재 설정(A36). 규칙 생성 비용은 무시할 만하다
        return withContext(Dispatchers.Default) {
            InkPipeline(this@MlKitHandwritingRecognizer, s.config, defaultInkRules(s.config)) { Log.i(SEGMENT_TAG, it) }
                .recognize(items, preContext, s.segmentation, report)
        }
    }

    /** ML Kit 호출. 실패는 빈 목록(→ Candidate 규칙이 Empty 로 판정). */
    override suspend fun recognize(language: InkLanguage, strokes: List<InkStroke>, preContext: String, area: Box): List<String> {
        val client = clients[language] ?: return emptyList()
        // 상자 왼쪽 위를 원점으로 옮긴다: WritingArea 는 크기만 받으므로 좌표를 그 안에 둔다.
        val ink = Ink.builder().apply {
            for (s in strokes) addStroke(Ink.Stroke.builder().apply {
                for (p in s.points) addPoint(Ink.Point.create(p.x - area.left, p.y - area.top, p.t))
                // 1점 획(점 찍기)은 같은 점을 한 번 더 넣는다(ink.md 함정 #8).
                if (s.points.size == 1) s.points[0].let { addPoint(Ink.Point.create(it.x - area.left, it.y - area.top, it.t)) }
            }.build())
        }.build()
        val context = RecognitionContext.builder()
            .setPreContext(preContext)
            .setWritingArea(WritingArea(maxOf(area.width, 1f), maxOf(area.height, 1f)))
            .build()
        return try {
            client.recognize(ink, context).await().candidates.map { it.text }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "recognize ${language.tag}", e); emptyList()
        }
    }

    override fun close() = clients.values.forEach { it.close() }

    private companion object {
        const val TAG = "pb.ink.recognizer"
        const val SEGMENT_TAG = "pb.ink.segment"
    }
}
