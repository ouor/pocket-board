package com.ouor.pocketboard.session

import android.util.Log
import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.GestureConfig
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.GestureOutput
import com.ouor.pocketboard.core.gesture.GestureRecognizer
import com.ouor.pocketboard.core.gesture.GestureStep
import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.core.gesture.Haptics
import com.ouor.pocketboard.core.gesture.TouchAction
import com.ouor.pocketboard.core.gesture.TouchSample
import com.ouor.pocketboard.core.gesture.engine.coerced
import com.ouor.pocketboard.core.hid.HidKeyboard
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.SendResult
import com.ouor.pocketboard.core.ink.HandwritingRecognizer
import com.ouor.pocketboard.core.ink.InkReport
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.keymap.KeyInput
import com.ouor.pocketboard.core.keymap.KeyPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/** 세션 → 화면 일회성 알림(SC09 v2 §5.1). 화면이 효과·동작 표시를 고르는 데만 쓴다. */
sealed interface WritingEvent {
    /** 인식 글을 보냄(Done·Aborted). [strokes]는 보낸 획. */
    data class Sent(val text: String, val strokes: List<InkStroke>) : WritingEvent
    /** B7: 보내지 않은 마지막 획 하나를 지움. */
    data class StrokeRemoved(val stroke: InkStroke) : WritingEvent
    /** 쓰던 획을 버림: 덩어리 지우기, 또는 인식 실패([failed]). */
    data class StrokesDiscarded(val strokes: List<InkStroke>, val failed: Boolean) : WritingEvent
    /** 호스트로 키만 보냄: ENTER(빈 상태 보내기 포함)·SPACE·BACKSPACE·CHUNK_DELETE. */
    data class KeySent(val action: Action) : WritingEvent
    data object Resynced : WritingEvent
    /** 전송 시 미연결(E1). 획은 그대로. */
    data object NotConnected : WritingEvent
}

/**
 * 쓰기 화면 한 번의 수명 동안 터치 → 제스처/잉크 → 인식 → 키 변환 → HID 전송 → 이력/진동을 잇는다(architecture §5).
 *
 * 스레딩: 모든 공개 메서드는 [scope]의 스레드(메인)에서 부른다. 터치 판정은 즉시(동기), 그 결과(획 추가·취소, 동작)는
 * 하나의 [Channel]로 들어가 **하나씩 끝까지** 처리된다. 그래서 전송 중에 온 백스페이스는 전송이 끝난 뒤 실행되고,
 * 전송 뒤에 쓴 획은 그 전송에 섞이지 않는다.
 */
class WritingSession(
    private val hid: HidKeyboard,
    private val keyInput: KeyInput,
    private val recognizer: HandwritingRecognizer,
    private val haptics: Haptics,
    private val log: SessionLog,
    private val starter: ConnectionStarter,
    private val mapping: StateFlow<GestureMapping>,
    private val gestureConfig: StateFlow<GestureConfig>,
    private val emptySendEnter: () -> Boolean,
    /** 오판정 기록(F30) 켜짐: 접촉과 잉크 보고서를 남긴다. */
    private val devlogEnabled: () -> Boolean,
    /** F25 미리보기 설정. 꺼져 있으면 미리보기 인식을 하지 않는다. */
    private val previewEnabled: StateFlow<Boolean>,
    private val newRecognizer: (GestureConfig) -> GestureRecognizer,
    private val scope: CoroutineScope,
    /** `MotionEvent.eventTime` 과 같은 시계(SystemClock.uptimeMillis). */
    private val now: () -> Long,
) {
    private sealed interface Event {
        data class AddStroke(val item: InkItem.Stroke) : Event
        data class Retract(val id: Long) : Event
        data class Act(val action: Action) : Event
        data object Clear : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val buffer = ArrayList<InkItem>()
    private var surface = Surface(0f, 0f, 0f)
    private var gestures: GestureRecognizer = newRecognizer(effectiveConfig())
    private var timer: Job? = null
    private val pointersDown = HashSet<Int>()
    private val jobs = ArrayList<Job>()
    private var previewJob: Job? = null
    private var previewed: List<InkItem> = emptyList()

    private val _preview = MutableStateFlow<String?>(null)

    /**
     * F25(P2): 보내기 전 인식 예상 결과. 설정이 꺼져 있거나 쓴 글씨가 없으면 null.
     * 버퍼가 바뀌고 [PREVIEW_DEBOUNCE_MS] 동안 더 바뀌지 않으면 한 번 인식한다. 획을 지우거나(B7) 덩어리 지우기·전송으로
     * 버퍼가 줄면 즉시 null 로 비운 뒤 남은 획으로 다시 계산한다.
     */
    val preview: StateFlow<String?> = _preview.asStateFlow()

    private val _strokes = MutableStateFlow<List<InkStroke>>(emptyList())

    /** 아직 보내지 않은 획(버퍼가 원본). 취소된 탭·B7·덩어리 지우기·전송이 곧바로 반영된다. 화면 잉크용. */
    val strokes: StateFlow<List<InkStroke>> = _strokes.asStateFlow()

    private val _lastSent = MutableStateFlow<List<String>>(emptyList())

    /** 최근 보낸 글(Done·Aborted), 오래된 것부터 최대 [LAST_SENT_MAX]개. 호스트 덩어리 지우기는 마지막 것을 뺀다(SC09 v2 Q3). */
    val lastSent: StateFlow<List<String>> = _lastSent.asStateFlow()

    private val _writingEvents = MutableSharedFlow<WritingEvent>(extraBufferCapacity = 16)

    /** 화면 피드백용 일회성 알림. 구독자가 없으면 버린다. */
    val writingEvents: SharedFlow<WritingEvent> = _writingEvents.asSharedFlow()

    private val _edgeBandDp = MutableStateFlow(0f)

    /** 실제 판정에 쓰는 가장자리 띠(dp, 시스템 제스처 인셋 포함). 이 안쪽에서 시작한 획만 글씨가 된다. */
    val edgeBandDp: StateFlow<Float> = _edgeBandDp.asStateFlow()

    private data class Surface(val widthDp: Float, val heightDp: Float, val edgeInsetDp: Float)

    init {
        jobs += scope.launch {
            for (e in events) {
                if (e is Event.Act && e.action == Action.SEND) previewJob?.cancel() // 전송과 인식을 겹치지 않음
                process(e)
                _strokes.value = buffer.strokes()
                refreshPreview()
            }
        }
        jobs += scope.launch { mapping.collect { gestures.setMappedGestures(it.actions.keys) } }
        jobs += scope.launch { gestureConfig.collect { rebuildGestures() } }
        jobs += scope.launch { recognizer.warmUp() }
        jobs += scope.launch { previewEnabled.collect { previewed = emptyList(); refreshPreview() } }
    }

    /** 입력 표면 크기(dp)와 시스템 제스처 인셋(dp)이 정해지거나 바뀔 때(gesture 명세 §2, 판단 4). */
    fun setSurface(widthDp: Float, heightDp: Float, systemGestureInsetDp: Float) {
        val s = Surface(widthDp, heightDp, systemGestureInsetDp)
        if (s != surface) { surface = s; rebuildGestures() }
    }

    /** `TouchSurfaceView.Listener` 에서 부른다. */
    fun onTouch(samples: List<TouchSample>) {
        for (s in samples) {
            when (s.action) {
                TouchAction.DOWN -> {
                    hid.noteActivity()
                    if (pointersDown.isEmpty()) firstDown()
                    pointersDown += s.pointerId
                }
                TouchAction.UP -> pointersDown -= s.pointerId
                TouchAction.CANCEL -> pointersDown.clear()
                TouchAction.MOVE -> Unit
            }
            handle(gestures.onTouch(s))
        }
    }

    /** 제스처 없이 동작을 직접 넣는다(튜토리얼, 테스트). */
    fun perform(action: Action) {
        events.trySend(Event.Act(action))
    }

    /** 화면 이탈·화면 꺼짐(E13): 보내지 않은 획을 버린다. 연결은 유지. */
    fun onLeave() {
        gestures.reset()
        timer?.cancel()
        pointersDown.clear()
        events.trySend(Event.Clear)
    }

    /** ViewModel 소멸 시. */
    fun close() {
        onLeave()
        previewJob?.cancel()
        jobs.forEach { it.cancel() }
        events.close()
        recognizer.close()
    }

    // 접촉의 첫 손가락: 획이 될 수 있으므로 링크를 깨운다(A13, hid R4). 멈춰 있으면 서비스부터.
    private fun firstDown() {
        if (hid.state.value is HidState.Stopped) starter.ensureStarted()
        hid.warmUp()
    }

    private fun handle(step: GestureStep) {
        for (o in step.outputs) when (o) {
            is GestureOutput.StrokeCompleted -> events.trySend(Event.AddStroke(InkItem.Stroke(o.id, o.stroke)))
            is GestureOutput.StrokeRetracted -> events.trySend(Event.Retract(o.id))
            is GestureOutput.Detected -> mapping.value.actions[o.gesture].let { a ->
                Log.i(TAG, "gesture ${o.gesture.id} -> $a")
                a?.let { events.trySend(Event.Act(it)) }
            }
        }
        step.records.forEach { if (it.result == "rejected") Log.i(TAG, "rejected ${it.ruleIds} major=${it.samples.maxOf { s -> s.touchMajorDp }}") }
        if (devlogEnabled()) step.records.forEach(log::contact)
        timer?.cancel()
        step.wakeAtMs?.let { at ->
            timer = scope.launch {
                delay(max(0, at - now()))
                handle(gestures.onTimer(now()))
            }
        }
    }

    private fun rebuildGestures() {
        timer?.cancel()
        _edgeBandDp.value = effectiveConfig().edgeBandDp
        gestures = newRecognizer(effectiveConfig()).also { it.setMappedGestures(mapping.value.actions.keys) }
    }

    private fun effectiveConfig(): GestureConfig = gestureConfig.value.let {
        it.copy(
            edgeBandDp = max(it.edgeBandDp, surface.edgeInsetDp),
            screenWidthDp = surface.widthDp,
            screenHeightDp = surface.heightDp,
        ).coerced() // 시스템 제스처 인셋이 edgeBand 범위(64dp)를 넘으면 판정기 생성이 예외로 죽는다
    }

    private fun refreshPreview() {
        val items = buffer.toList()
        if (!previewEnabled.value || items.isEmpty()) {
            previewJob?.cancel()
            previewed = emptyList()
            _preview.value = null
            return
        }
        val job = previewJob
        if (items == previewed && job != null && !job.isCancelled) return // 그대로면 진행 중이거나 이미 계산됨
        if (items.size < previewed.size) _preview.value = null // 지움: 옛 결과를 남기지 않음
        previewed = items
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val outcome = recognizer.recognize(items, keyInput.preContext())
            _preview.value = (outcome as? RecognitionOutcome.Text)?.text
        }
    }

    private suspend fun process(e: Event) {
        when (e) {
            is Event.AddStroke -> buffer += e.item
            is Event.Retract -> buffer.removeAll { it is InkItem.Stroke && it.id == e.id }
            Event.Clear -> buffer.clear()
            is Event.Act -> act(e.action)
        }
    }

    private suspend fun act(action: Action) {
        Log.i(TAG, "act $action buffer=${buffer.size}")
        when (action) {
            Action.SEND -> if (buffer.isEmpty()) {
                if (emptySendEnter()) sendKey(Action.ENTER, keyInput.enter(), HapticPattern.ENTER)
            } else sendInk()
            Action.ENTER -> sendKey(Action.ENTER, keyInput.enter(), HapticPattern.ENTER)
            Action.SPACE -> if (buffer.isEmpty()) sendKey(Action.SPACE, keyInput.space(), HapticPattern.SPACE) else {
                buffer += InkItem.Space // 단어 경계(ink §3)
                play(HapticPattern.SPACE)
            }
            Action.BACKSPACE -> if (buffer.isEmpty()) sendKey(Action.BACKSPACE, keyInput.backspace(), HapticPattern.BACKSPACE) else {
                val removed = buffer.removeAt(buffer.lastIndex) // B7: 마지막 획(또는 그 뒤 스페이스) 하나만, 호스트로는 안 보냄
                if (removed is InkItem.Stroke) emit(WritingEvent.StrokeRemoved(removed.stroke))
                play(HapticPattern.BACKSPACE)
            }
            Action.CHUNK_DELETE -> if (buffer.isEmpty()) {
                val plan = keyInput.chunkDelete()
                // 비면 E11: 아무것도 안 함
                if (plan.units.isNotEmpty() && sendKey(Action.CHUNK_DELETE, plan, HapticPattern.CHUNK_DELETE)) {
                    _lastSent.value = _lastSent.value.dropLast(1) // 지운 덩어리를 최근 글로 보여 주지 않음
                }
            } else {
                emit(WritingEvent.StrokesDiscarded(_strokes.value, failed = false))
                buffer.clear() // PRD §3.3: 쓰던 획 전부 버림
                play(HapticPattern.CHUNK_DELETE)
            }
            Action.RESYNC -> {
                keyInput.resync()
                play(HapticPattern.RESYNC)
                emit(WritingEvent.Resynced)
            }
        }
    }

    private suspend fun sendInk() {
        val items = buffer.toList()
        var report: InkReport? = null
        val outcome = if (devlogEnabled()) recognizer.recognize(items, keyInput.preContext()) { report = it }
        else recognizer.recognize(items, keyInput.preContext())
        Log.i(TAG, "recognize ${items.size} items -> $outcome")
        when (outcome) {
            is RecognitionOutcome.Text -> {
                val plan = keyInput.text(outcome.text)
                val result = send(plan, HapticPattern.SENT)
                if (result !is SendResult.NotConnected) {
                    buffer.subList(0, items.size).clear()
                    _lastSent.value = (_lastSent.value + outcome.text).takeLast(LAST_SENT_MAX)
                    emit(WritingEvent.Sent(outcome.text, items.strokes()))
                }
                log.send(outcome.text, outcome.ruleIds, plan.ruleIds, result.toString(), items, report)
            }
            RecognitionOutcome.Empty, RecognitionOutcome.ModelNotReady -> {
                emit(WritingEvent.StrokesDiscarded(items.strokes(), failed = true))
                buffer.subList(0, items.size).clear() // E3, D9
                play(HapticPattern.RECOGNITION_FAILED)
                log.send("", emptyList(), emptyList(), outcome.toString(), items, report)
            }
        }
    }

    /** 보내고 보낸 만큼 confirm. 미연결이면 아무것도 바꾸지 않고 재연결을 재촉한다(E1). */
    private suspend fun send(plan: KeyPlan, success: HapticPattern): SendResult {
        val result = hid.send(plan.units)
        Log.i(TAG, "send ${plan.units.size} units -> $result")
        when (result) {
            is SendResult.Done -> {
                keyInput.confirm(plan, result.unitsSent)
                play(success)
            }
            is SendResult.Aborted -> {
                keyInput.confirm(plan, result.unitsSent)
                // 끊김은 HidEvent.LostUnexpectedly 가 진동한다. 혼잡·취소만 여기서 알린다.
                if (result.reason != SendResult.Reason.DISCONNECTED) play(HapticPattern.NOT_CONNECTED)
            }
            SendResult.NotConnected -> {
                play(HapticPattern.NOT_CONNECTED)
                emit(WritingEvent.NotConnected)
                if (hid.state.value is HidState.Stopped) starter.ensureStarted()
                hid.warmUp()
            }
        }
        return result
    }

    /** 키만 보내고, 호스트에 닿았으면(미연결 아님) 알린다. */
    private suspend fun sendKey(action: Action, plan: KeyPlan, success: HapticPattern): Boolean {
        val reached = send(plan, success) !is SendResult.NotConnected
        if (reached) emit(WritingEvent.KeySent(action))
        return reached
    }

    private fun emit(e: WritingEvent) {
        _writingEvents.tryEmit(e)
    }

    private fun List<InkItem>.strokes() = mapNotNull { (it as? InkItem.Stroke)?.stroke }

    private fun play(p: HapticPattern) {
        haptics.play(p)
    }

    companion object {
        /** 마지막 획 뒤 미리보기 인식까지 기다리는 시간. 실기기에서 조정. */
        const val PREVIEW_DEBOUNCE_MS = 400L
        const val LAST_SENT_MAX = 3
        private const val TAG = "pb.session"
    }
}
