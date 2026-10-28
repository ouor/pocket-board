package com.ouor.pocketboard.session

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.common.key.KeyStep
import com.ouor.pocketboard.core.common.key.KeyUnit
import com.ouor.pocketboard.core.common.key.LedState
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.GestureConfig
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.GestureOutput
import com.ouor.pocketboard.core.gesture.GestureRecognizer
import com.ouor.pocketboard.core.gesture.GestureStep
import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.core.gesture.Haptics
import com.ouor.pocketboard.core.gesture.TouchAction
import com.ouor.pocketboard.core.gesture.TouchSample
import com.ouor.pocketboard.core.gesture.engine.DefaultGestureRecognizer
import com.ouor.pocketboard.core.hid.HidEvent
import com.ouor.pocketboard.core.hid.HidKeyboard
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.HidSupport
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.core.hid.SendResult
import com.ouor.pocketboard.core.ink.HandwritingRecognizer
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.rules.defaultInkRules
import com.ouor.pocketboard.core.ink.segment.InkPipeline
import com.ouor.pocketboard.core.keymap.HostImeState
import com.ouor.pocketboard.core.keymap.ImeMode
import com.ouor.pocketboard.core.keymap.KeyInput
import com.ouor.pocketboard.core.keymap.KeyPlan
import com.ouor.pocketboard.core.keymap.KeymapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration

class WritingSessionTest {

    @Test
    fun actionsRunInOrder_backspaceWaitsForSend() = runTest {
        val f = Fixture(this)
        f.hid.gate = CompletableDeferred()
        f.stroke(1)
        f.session.perform(Action.SEND)
        f.session.perform(Action.BACKSPACE)
        runCurrent()
        assertEquals(listOf("text:안녕"), f.hid.sent) // 전송이 끝나지 않아 백스페이스는 대기 중

        f.hid.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("text:안녕", "backspace"), f.hid.sent)
        assertEquals(listOf("text:안녕" to 1, "backspace" to 1), f.keys.confirmed)
        assertEquals(listOf(HapticPattern.SENT, HapticPattern.BACKSPACE), f.played)
    }

    @Test
    fun backspaceWithUnsentStrokes_removesLastStrokeOnly_B7() = runTest {
        val f = Fixture(this)
        f.stroke(1)
        f.stroke(2)
        f.session.perform(Action.BACKSPACE)
        f.session.perform(Action.SEND)
        runCurrent()
        assertEquals(listOf(listOf(1L)), (f.ink as FakeInk).recognized) // 획 2만 빠짐
        assertEquals(listOf("text:안녕"), f.hid.sent) // 백스페이스는 호스트로 안 감
        assertEquals(listOf(HapticPattern.BACKSPACE, HapticPattern.SENT), f.played)
    }

    @Test
    fun chunkDelete_dropsStrokesWhileWriting_elseDeletesLastChunkOnHost() = runTest {
        val f = Fixture(this)
        f.stroke(1)
        f.session.perform(Action.CHUNK_DELETE) // 쓰는 중: 획만 버림
        f.session.perform(Action.SEND) // 버퍼가 비었으므로 빈 상태 엔터
        f.session.perform(Action.CHUNK_DELETE) // 호스트 덩어리 지우기
        runCurrent()
        assertEquals(emptyList<List<Long>>(), (f.ink as FakeInk).recognized)
        assertEquals(listOf("enter", "chunk"), f.hid.sent)
        assertEquals(listOf(HapticPattern.CHUNK_DELETE, HapticPattern.ENTER, HapticPattern.CHUNK_DELETE), f.played)
    }

    @Test
    fun notConnected_keepsStrokes_andRetriesConnection() = runTest {
        val f = Fixture(this)
        f.hid.result = { SendResult.NotConnected }
        f.stroke(1)
        f.session.perform(Action.SEND)
        runCurrent()
        f.hid.result = { SendResult.Done(it) }
        f.session.perform(Action.SEND)
        runCurrent()
        assertEquals(listOf(listOf(1L), listOf(1L)), (f.ink as FakeInk).recognized) // E1: 획 유지 후 다시 전송
        assertEquals(listOf(HapticPattern.NOT_CONNECTED, HapticPattern.SENT), f.played)
        assertEquals(1, f.hid.warmUps)
    }

    @Test
    fun preview_afterDebounce_clearedOnSend_andOffByDefault() = runTest {
        val f = Fixture(this, preview = true)
        f.stroke(1)
        runCurrent()
        assertEquals(null, f.session.preview.value) // 디바운스 전
        advanceTimeBy(WritingSession.PREVIEW_DEBOUNCE_MS + 1)
        assertEquals("안녕", f.session.preview.value)

        f.session.perform(Action.SEND)
        runCurrent()
        assertEquals(null, f.session.preview.value)
        assertEquals(listOf(listOf(1L), listOf(1L)), (f.ink as FakeInk).recognized) // 미리보기 1회 + 전송 1회

        val off = Fixture(this)
        off.stroke(1)
        advanceTimeBy(WritingSession.PREVIEW_DEBOUNCE_MS * 2)
        assertEquals(null, off.session.preview.value)
        assertEquals(emptyList<List<Long>>(), (off.ink as FakeInk).recognized)
    }

    @Test
    fun strokesMirrorBuffer_lastSentOnlyWhenSent() = runTest {
        val f = Fixture(this)
        f.stroke(1)
        f.stroke(2)
        runCurrent()
        assertEquals(2, f.session.strokes.value.size)
        f.session.perform(Action.BACKSPACE) // B7
        runCurrent()
        assertEquals(1, f.session.strokes.value.size)

        f.hid.result = { SendResult.NotConnected }
        f.session.perform(Action.SEND)
        runCurrent()
        assertEquals(1, f.session.strokes.value.size) // E1: 획 유지
        assertEquals(emptyList<String>(), f.session.lastSent.value)

        f.hid.result = { SendResult.Done(it) }
        f.session.perform(Action.SEND)
        runCurrent()
        assertEquals(emptyList<Any>(), f.session.strokes.value)
        assertEquals(listOf("안녕"), f.session.lastSent.value)
    }

    @Test
    fun events_tellWhyStrokesWent_andHostChunkDeleteDropsLastSent() = runTest {
        val f = Fixture(this)
        val seen = ArrayList<WritingEvent>()
        backgroundScope.launch { f.session.writingEvents.collect { seen += it } }
        runCurrent()
        f.stroke(1)
        f.stroke(2)
        f.session.perform(Action.BACKSPACE) // B7
        f.session.perform(Action.SEND)
        f.session.perform(Action.BACKSPACE) // 획 없음 → 호스트
        f.session.perform(Action.CHUNK_DELETE) // 호스트 덩어리
        runCurrent()
        assertEquals(
            listOf("StrokeRemoved", "Sent", "KeySent(BACKSPACE)", "KeySent(CHUNK_DELETE)"),
            seen.map { if (it is WritingEvent.KeySent) "KeySent(${it.action})" else it::class.simpleName },
        )
        assertEquals(1, (seen[1] as WritingEvent.Sent).strokes.size)
        assertEquals(emptyList<String>(), f.session.lastSent.value)
    }

    /**
     * 현장 버그 재현: 실제 판정기 + 실제 InkPipeline(가짜 엔진)으로 400x800dp 표면, 제스처 인셋 24dp 에서
     * 3획 단어 → 더블 탭. 손가락 touchMajor 는 문서 가정(8dp)과 실기기 물리값(약 7mm ≈ 45dp) 둘 다.
     */
    @Test
    fun replay_wordThenDoubleTap_sendsRecognizedText() = runTest {
        for (major in listOf(8f, 45f)) {
            val engineStrokes = ArrayList<Int>()
            val pipeline = InkPipeline({ _, strokes, _, _ -> engineStrokes += strokes.size; listOf("안녕") }, InkConfig(), defaultInkRules())
            val ink = object : HandwritingRecognizer {
                override suspend fun warmUp() = Unit
                override suspend fun recognize(items: List<InkItem>, preContext: String) =
                    pipeline.recognize(items, preContext, SegmentationMode.RULES)
                override fun close() = Unit
            }
            val f = Fixture(this, ink = ink, newRecognizer = ::DefaultGestureRecognizer)
            f.session.setSurface(400f, 800f, 24f)
            runCurrent()
            var t = testScheduler.currentTime + 1000
            fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
                for (k in 0..12) {
                    val a = when (k) { 0 -> TouchAction.DOWN; 12 -> TouchAction.UP; else -> TouchAction.MOVE }
                    f.session.onTouch(listOf(TouchSample(0, a, x0 + (x1 - x0) * k / 12, y0 + (y1 - y0) * k / 12, t, major)))
                    t += 12
                }
                t += 250
            }
            fun tap(x: Float, y: Float) {
                f.session.onTouch(listOf(TouchSample(0, TouchAction.DOWN, x, y, t, major)))
                f.session.onTouch(listOf(TouchSample(0, TouchAction.UP, x + 1.5f, y + 1f, t + 80, major)))
                t += 80 + 120
            }
            line(140f, 330f, 140f, 400f) // ㄴ 세로
            line(140f, 400f, 200f, 400f) // ㄴ 가로
            line(230f, 320f, 230f, 410f) // ㅣ
            tap(220f, 520f)
            tap(223f, 522f)
            runCurrent()
            assertEquals("major=$major", listOf(3, 3), engineStrokes) // ko·en 각각 3획(점 획은 retract). 기본 S2(2.0)는 ㄴ|ㅣ 를 안 끊어 합침 확인 없음
            assertEquals("major=$major", listOf("text:안녕"), f.hid.sent)
            assertEquals("major=$major", listOf(HapticPattern.SENT), f.played)
        }
    }

    // --- fakes ---

    private class Fixture(
        scope: TestScope,
        preview: Boolean = false,
        val ink: HandwritingRecognizer = FakeInk(),
        newRecognizer: ((GestureConfig) -> GestureRecognizer)? = null,
    ) {
        val keys = FakeKeys()
        val hid = FakeHid(keys)
        val gestures = FakeGestures()
        val played = ArrayList<HapticPattern>()
        val session = WritingSession(
            hid = hid,
            keyInput = keys,
            recognizer = ink,
            haptics = Haptics { played += it },
            log = SessionLog.NONE,
            starter = ConnectionStarter { },
            mapping = MutableStateFlow(GestureMapping.DEFAULT),
            gestureConfig = MutableStateFlow(GestureConfig()),
            emptySendEnter = { true },
            devlogEnabled = { false },
            previewEnabled = MutableStateFlow(preview),
            newRecognizer = newRecognizer ?: { gestures },
            scope = scope.backgroundScope,
            now = { scope.testScheduler.currentTime },
        )

        /** 제스처 판정기가 획 [id]를 확정한 것처럼 넣는다. */
        fun stroke(id: Long) {
            val p = InkPoint(0f, 0f, id)
            gestures.next = GestureStep(listOf(GestureOutput.StrokeCompleted(id, InkStroke(listOf(p)))))
            session.onTouch(listOf(TouchSample(0, TouchAction.UP, 0f, 0f, id, 8f)))
        }
    }

    private class FakeGestures : GestureRecognizer {
        var next = GestureStep()
        override fun onTouch(sample: TouchSample) = next.also { next = GestureStep() }
        override fun onTimer(nowMs: Long) = GestureStep()
        override fun setMappedGestures(gestures: Set<Gesture>) = Unit
        override fun reset() = Unit
    }

    private class FakeInk : HandwritingRecognizer {
        val recognized = ArrayList<List<Long>>()
        override suspend fun warmUp() = Unit
        override suspend fun recognize(items: List<InkItem>, preContext: String): RecognitionOutcome {
            recognized += items.filterIsInstance<InkItem.Stroke>().map { it.id }
            return RecognitionOutcome.Text("안녕")
        }
        override fun close() = Unit
    }

    private class Plan(val label: String) : KeyPlan {
        override val units: List<KeyUnit> = listOf(KeyUnit(listOf(KeyStep.Press(0x04)), countsAsChar = true))
        override val skipped = ""
        override val ruleIds = emptyList<String>()
    }

    private class FakeKeys : KeyInput {
        val confirmed = ArrayList<Pair<String, Int>>()
        /** hid 가 받은 단위 목록(인스턴스) → plan 라벨. */
        val labels = java.util.IdentityHashMap<List<KeyUnit>, String>()
        private fun Plan(label: String) = WritingSessionTest.Plan(label).also { labels[it.units] = label }
        override val imeState = MutableStateFlow(HostImeState(ImeMode.LATIN, null))
        override fun text(text: String): KeyPlan = Plan("text:$text")
        override fun space(): KeyPlan = Plan("space")
        override fun enter(): KeyPlan = Plan("enter")
        override fun backspace(): KeyPlan = Plan("backspace")
        override fun chunkDelete(): KeyPlan = Plan("chunk")
        override fun confirm(plan: KeyPlan, unitsSent: Int) { confirmed += (plan as Plan).label to unitsSent }
        override fun resync() = Unit
        override fun onLeds(leds: LedState?) = Unit
        override fun onHostConnected() = Unit
        override fun preContext(max: Int) = ""
        override fun updateSettings(settings: KeymapSettings) = Unit
    }

    private class FakeHid(private val keys: FakeKeys) : HidKeyboard {
        val sent = ArrayList<String>()
        var gate: CompletableDeferred<Unit>? = null
        var result: (Int) -> SendResult = { SendResult.Done(it) }
        var warmUps = 0
        override val state = MutableStateFlow<HidState>(HidState.Connected(HostDevice("a", "pc", null)))
        override val leds = MutableStateFlow<LedState?>(null)
        override val events: Flow<HidEvent> = emptyFlow()
        override val hosts = MutableStateFlow(emptyList<HostDevice>())
        override fun phoneName(): String? = null
        override suspend fun checkSupport() = HidSupport.SUPPORTED
        override fun start() = Unit
        override fun stop() = Unit
        override fun connect(host: HostDevice) = Unit
        override fun forget(host: HostDevice) = Unit
        override fun setIdleTimeout(timeout: Duration?) = Unit
        override fun warmUp() { warmUps++ }
        override fun noteActivity() = Unit
        override fun cancelSending() = Unit
        override suspend fun send(units: List<KeyUnit>): SendResult {
            val r = result(units.size)
            if (r is SendResult.NotConnected) return r
            sent += keys.labels.getValue(units)
            gate?.await()
            gate = null
            return r
        }
    }
}
