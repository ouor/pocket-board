package com.ouor.pocketboard.core.gesture.engine

import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.AssignResult
import com.ouor.pocketboard.core.gesture.ContactRecord
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.GestureConfig
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.GestureOutput
import com.ouor.pocketboard.core.gesture.GestureOutput.Detected
import com.ouor.pocketboard.core.gesture.GestureOutput.StrokeCompleted
import com.ouor.pocketboard.core.gesture.GestureOutput.StrokeRetracted
import com.ouor.pocketboard.core.gesture.GestureStep
import com.ouor.pocketboard.core.gesture.SwipeDirection
import com.ouor.pocketboard.core.gesture.TouchAction
import com.ouor.pocketboard.core.gesture.TouchAction.DOWN
import com.ouor.pocketboard.core.gesture.TouchAction.MOVE
import com.ouor.pocketboard.core.gesture.TouchAction.UP
import com.ouor.pocketboard.core.gesture.TouchSample
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 합성 터치 시퀀스로 1차 규칙 판정 확인. 좌표 dp, 화면 400x800 가정. */
class DefaultGestureRecognizerTest {
    private val config = GestureConfig(screenWidthDp = 400f, screenHeightDp = 800f)

    private fun s(id: Int, a: TouchAction, x: Float, y: Float, t: Long, major: Float = 8f) = TouchSample(id, a, x, y, t, major)

    private fun tap(x: Float, y: Float, t: Long, major: Float = 8f) =
        listOf(s(0, DOWN, x, y, t, major), s(0, UP, x + 1, y, t + 60, major))

    /** [fingers]개 손가락이 [stagger]ms 간격으로 착지해 [moveMs] 동안 (dx, dy)만큼 5단계로 움직이고 차례로 뗌. */
    private fun multi(fingers: Int, t: Long, dx: Float = 0f, dy: Float = 0f, stagger: Long = 20, moveMs: Long = 60): List<TouchSample> {
        val xs = (0 until fingers).map { 150f + it * 40f }
        val out = ArrayList<TouchSample>()
        xs.forEachIndexed { i, x -> out += s(i, DOWN, x, 400f, t + i * stagger) }
        val start = t + (fingers - 1) * stagger
        for (k in 1..5) xs.forEachIndexed { i, x -> out += s(i, MOVE, x + dx * k / 5, 400f + dy * k / 5, start + moveMs * k / 5) }
        xs.forEachIndexed { i, x -> out += s(i, UP, x + dx, 400f + dy, start + moveMs + 5 + i * 5) }
        return out
    }

    private class Run(val outputs: List<GestureOutput>, val records: List<ContactRecord>, val lastWake: Long?)

    private fun run(r: DefaultGestureRecognizer, samples: List<TouchSample>, flushAt: Long? = null): Run {
        val steps = ArrayList<GestureStep>()
        steps += r.onTouchAll(samples)
        flushAt?.let { steps += r.onTimer(it) }
        return Run(steps.flatMap { it.outputs }, steps.flatMap { it.records }, steps.last().wakeAtMs)
    }

    @Test
    fun doubleTap_sameSpot_detectedImmediately_andDotsRetracted() {
        val r = DefaultGestureRecognizer(config)
        val out = run(r, tap(200f, 400f, 1000) + tap(205f, 402f, 1200))
        val strokes = out.outputs.filterIsInstance<StrokeCompleted>().map { it.id }
        assertEquals(2, strokes.size)
        assertEquals(strokes.map { StrokeRetracted(it) }, out.outputs.filterIsInstance<StrokeRetracted>())
        assertEquals(Detected(Gesture.Tap(1, 2)), out.outputs.last())
        assertNull("기본 매핑에 트리플 탭이 없으면 기다리지 않는다", out.lastWake)
        assertEquals(listOf("classify/single-tap", "sequence/same-spot-multitap", "resolve/retract-tap-ink"), out.records.last().ruleIds)
        assertEquals("tap.f1.x2", out.records.last().result)
    }

    @Test
    fun colonDots_farApart_stayAsStrokes() {
        val r = DefaultGestureRecognizer(config)
        val out = run(r, tap(200f, 380f, 1000) + tap(200f, 420f, 1150))
        assertEquals(2, out.outputs.filterIsInstance<StrokeCompleted>().size)
        assertTrue(out.outputs.none { it is Detected || it is StrokeRetracted })
    }

    @Test
    fun tripleTapMapped_waitsForTimer_thenSingleTapStaysDot() {
        val r = DefaultGestureRecognizer(config)
        r.setMappedGestures(GestureMapping.DEFAULT.actions.keys + Gesture.Tap(1, 3))
        val first = run(r, tap(200f, 400f, 1000) + tap(200f, 400f, 1200))
        assertEquals(1260L + config.multiTapGapMs, first.lastWake)
        assertTrue(first.outputs.none { it is Detected })
        val fired = r.onTimer(first.lastWake!!)
        assertEquals(Detected(Gesture.Tap(1, 2)), fired.outputs.last())
        assertNull(fired.wakeAtMs)
    }

    @Test
    fun twoFingerTap_staggeredLanding_isSpace() {
        val out = run(DefaultGestureRecognizer(config), multi(2, 1000, stagger = 80))
        assertEquals(listOf(Detected(Gesture.Tap(2, 1))), out.outputs)
    }

    @Test
    fun threeFingerTap() {
        val out = run(DefaultGestureRecognizer(config), multi(3, 1000))
        assertEquals(listOf(Detected(Gesture.Tap(3, 1))), out.outputs)
    }

    @Test
    fun twoFingerSwipes_leftAndUp() {
        val r = DefaultGestureRecognizer(config)
        assertEquals(listOf(Detected(Gesture.Swipe(2, SwipeDirection.LEFT))), run(r, multi(2, 1000, dx = -80f, moveMs = 200)).outputs)
        assertEquals(listOf(Detected(Gesture.Swipe(2, SwipeDirection.UP))), run(r, multi(2, 2000, dy = -90f, moveMs = 200)).outputs)
    }

    @Test
    fun remainingSwipes_twoFingerRightDown_threeFingerLeft() {
        val r = DefaultGestureRecognizer(config)
        assertEquals(listOf(Detected(Gesture.Swipe(2, SwipeDirection.RIGHT))), run(r, multi(2, 1000, dx = 80f, moveMs = 200)).outputs)
        assertEquals(listOf(Detected(Gesture.Swipe(2, SwipeDirection.DOWN))), run(r, multi(2, 2000, dy = 90f, moveMs = 200)).outputs)
        assertEquals(listOf(Detected(Gesture.Swipe(3, SwipeDirection.LEFT))), run(r, multi(3, 3000, dx = -80f, moveMs = 200)).outputs)
    }

    @Test
    fun palm_rejected_withoutBreakingWriting() {
        val r = DefaultGestureRecognizer(config.copy(palmTouchMajorDp = 18f)) // 기본은 꺼짐
        val out = run(r, tap(200f, 400f, 1000, major = 30f))
        assertTrue(out.outputs.isEmpty())
        assertEquals(ContactRecord(tap(200f, 400f, 1000, major = 30f), "rejected", listOf("reject/palm-size")), out.records.single())
    }

    @Test
    fun stroke_isEmitted_andEdgeStartIgnored() {
        val r = DefaultGestureRecognizer(config)
        val stroke = listOf(s(0, DOWN, 100f, 300f, 0), s(0, MOVE, 130f, 320f, 30), s(0, UP, 160f, 340f, 60))
        assertEquals(3, (run(r, stroke).outputs.single() as StrokeCompleted).stroke.points.size)
        assertTrue(run(r, tap(5f, 400f, 1000)).outputs.isEmpty())
    }

    /** F30: 기록(JSONL 한 줄)을 그대로 다시 넣으면 같은 판정이 나온다 → 기록이 곧 테스트 픽스처. */
    @Test
    fun records_roundTripAsFixtures() {
        val samples = tap(200f, 400f, 1000) + multi(2, 2000, dx = -80f, moveMs = 200) + tap(200f, 400f, 3000) + tap(201f, 400f, 3150)
        val original = run(DefaultGestureRecognizer(config), samples, flushAt = 9999)
        val jsonl = original.records.joinToString("\n") { Json.encodeToString(ContactRecord.serializer(), it) }
        val replayed = jsonl.lines().map { Json.decodeFromString(ContactRecord.serializer(), it) }.flatMap { it.samples }
        val again = run(DefaultGestureRecognizer(config), replayed, flushAt = 9999)
        assertEquals(original.outputs, again.outputs)
        assertEquals(original.records, again.records)
        assertEquals(listOf("tap.f1.x1", "swipe.f2.left", "tap.f1.x1", "tap.f1.x2"), original.records.map { it.result })
    }

    @Test
    fun configRanges_defaultsInside_outsideRejectedOrCoerced() {
        GestureConfig().requireInRange()
        val bad = GestureConfig(tapSlopDp = 500f, multiTapGapMs = 0)
        assertEquals(GestureConfig(tapSlopDp = 48f, multiTapGapMs = 100), bad.coerced())
        assertTrue(runCatching { DefaultGestureRecognizer(bad) }.isFailure)
    }

    @Test
    fun mapping_sendRequired_andDecodeIgnoresUnknown() {
        val m = MappingCodec.decode(mapOf("tap.f1.x4" to "enter", "bogus" to "send", "tap.f2.x1" to "none", "tap.f3.x1" to "??"))
        assertEquals(Action.ENTER, m.actions[Gesture.Tap(1, 4)])
        assertNull(m.actions[Gesture.Tap(2, 1)])
        assertEquals(Action.RESYNC, m.actions[Gesture.Tap(3, 1)])
        assertEquals(AssignResult.SendRequired, MappingCodec.assign(m, Gesture.Tap(1, 2), null))
        assertEquals(AssignResult.Assigned(Action.SPACE), MappingCodec.assign(GestureMapping.DEFAULT, Gesture.Tap(2, 1), Action.SEND))
    }
}
