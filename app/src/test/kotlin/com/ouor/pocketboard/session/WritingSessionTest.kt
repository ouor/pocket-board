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
import com.ouor.pocketboard.core.hid.HidEvent
import com.ouor.pocketboard.core.hid.HidKeyboard
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.HidSupport
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.core.hid.SendResult
import com.ouor.pocketboard.core.ink.HandwritingRecognizer
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.keymap.HostImeState
import com.ouor.pocketboard.core.keymap.ImeMode
import com.ouor.pocketboard.core.keymap.KeyInput
import com.ouor.pocketboard.core.keymap.KeyPlan
import com.ouor.pocketboard.core.keymap.KeymapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
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
        assertEquals(listOf(listOf(1L)), f.ink.recognized) // 획 2만 빠짐
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
        assertEquals(emptyList<List<Long>>(), f.ink.recognized)
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
        assertEquals(listOf(listOf(1L), listOf(1L)), f.ink.recognized) // E1: 획 유지 후 다시 전송
        assertEquals(listOf(HapticPattern.NOT_CONNECTED, HapticPattern.SENT), f.played)
        assertEquals(1, f.hid.warmUps)
    }

    // --- fakes ---

    private class Fixture(scope: TestScope) {
        val keys = FakeKeys()
        val hid = FakeHid(keys)
        val ink = FakeInk()
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
            logContacts = { false },
            newRecognizer = { gestures },
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
