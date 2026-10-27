package com.ouor.pocketboard.ui.onboarding

import android.app.Application
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.GestureOutput
import com.ouor.pocketboard.core.gesture.GestureRecognizer
import com.ouor.pocketboard.core.gesture.GestureStep
import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.core.gesture.TouchAction
import com.ouor.pocketboard.core.gesture.TouchSample
import com.ouor.pocketboard.core.gesture.android.TouchSurfaceView
import com.ouor.pocketboard.core.gesture.engine.DefaultGestureRecognizer
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.di.container
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.components.Badge
import com.ouor.pocketboard.ui.components.BadgeKind
import com.ouor.pocketboard.ui.components.BackButton
import com.ouor.pocketboard.ui.components.GestureSummary
import com.ouor.pocketboard.ui.components.ONBOARDING_STEPS
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.StatusBadge
import com.ouor.pocketboard.ui.components.haptic
import com.ouor.pocketboard.ui.components.progressLabel
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

// ---------- SC08 연습(F22) ----------

/**
 * 연습: 쓰기 화면과 같은 판정기·인식기를 쓰지만 PC로 보내지 않는다(integration §5 SC08).
 * 동작은 화면의 결과 문자열에 적용한다. 진동은 실제와 같다.
 */
class PracticeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val recognizer = c.models.openRecognizer()
    private var gestures: GestureRecognizer = newGestures(0f, 0f)
    private var timer: Job? = null
    private val order = Mutex() // 동작을 들어온 순서대로 하나씩(인식 중 백스페이스 등)
    private val chunks = ArrayList<Int>() // 보낸 덩어리 길이(덩어리 지우기)
    private var livePointer: Int? = null

    val items = mutableStateListOf<InkItem>()
    val live = mutableStateListOf<Offset>() // 지금 쓰는 획(dp)
    var result by mutableStateOf("")
        private set
    var failed by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { recognizer.warmUp() }
    }

    fun setSurface(widthDp: Float, heightDp: Float) {
        timer?.cancel()
        gestures = newGestures(widthDp, heightDp)
    }

    // 카드 안 영역이라 화면 가장자리 띠는 쓰지 않는다
    private fun newGestures(w: Float, h: Float) = DefaultGestureRecognizer(
        c.settingsRepository.settings.value.gesture.copy(edgeBandDp = 0f, screenWidthDp = w, screenHeightDp = h),
    ).also { it.setMappedGestures(c.mapping.value.actions.keys) }

    fun onTouch(samples: List<TouchSample>) {
        for (s in samples) {
            when (s.action) {
                TouchAction.DOWN -> if (livePointer == null) { livePointer = s.pointerId; live.clear(); live += Offset(s.xDp, s.yDp) }
                TouchAction.MOVE -> if (s.pointerId == livePointer) live += Offset(s.xDp, s.yDp)
                TouchAction.UP, TouchAction.CANCEL -> if (s.pointerId == livePointer || s.action == TouchAction.CANCEL) { livePointer = null; live.clear() }
            }
            handle(gestures.onTouch(s))
        }
    }

    private fun handle(step: GestureStep) {
        for (o in step.outputs) when (o) {
            is GestureOutput.StrokeCompleted -> items += InkItem.Stroke(o.id, o.stroke)
            is GestureOutput.StrokeRetracted -> items.removeAll { it is InkItem.Stroke && it.id == o.id }
            is GestureOutput.Detected -> c.mapping.value.actions[o.gesture]?.let { a -> viewModelScope.launch { order.withLock { act(a) } } }
        }
        timer?.cancel()
        step.wakeAtMs?.let { at ->
            timer = viewModelScope.launch {
                delay(max(0, at - SystemClock.uptimeMillis()))
                handle(gestures.onTimer(SystemClock.uptimeMillis()))
            }
        }
    }

    private suspend fun act(action: Action) {
        failed = false
        when (action) {
            Action.SEND -> if (items.isEmpty()) {
                if (c.settingsRepository.settings.value.emptySendEnter) append("\n", action) else Unit
            } else {
                val snapshot = items.toList()
                when (val r = recognizer.recognize(snapshot, result.takeLast(20))) {
                    is RecognitionOutcome.Text -> { items.removeRange(0, snapshot.size); append(r.text, action) }
                    else -> { items.removeRange(0, snapshot.size); failed = true; c.haptics.play(HapticPattern.RECOGNITION_FAILED) }
                }
            }
            Action.ENTER -> append("\n", action)
            Action.SPACE -> if (items.isEmpty()) append(" ", action) else { items += InkItem.Space; c.haptics.play(action.haptic) }
            Action.BACKSPACE -> {
                if (items.isNotEmpty()) items.removeAt(items.lastIndex) // B7
                else if (result.isNotEmpty()) { result = result.dropLast(1); chunks.lastIndex.takeIf { it >= 0 }?.let { chunks[it] = (chunks[it] - 1).coerceAtLeast(0) } }
                c.haptics.play(action.haptic)
            }
            Action.CHUNK_DELETE -> {
                if (items.isNotEmpty()) items.clear()
                else chunks.removeLastOrNull()?.let { result = result.dropLast(it) }
                c.haptics.play(action.haptic)
            }
            Action.RESYNC -> c.haptics.play(action.haptic) // 연습에선 효과 없음
        }
    }

    private fun append(text: String, action: Action) {
        result += text
        chunks += text.length
        c.haptics.play(action.haptic)
    }

    override fun onCleared() {
        timer?.cancel()
        recognizer.close()
    }
}

@Composable
fun PracticeRoute(vm: AppViewModel, onboarding: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    val practice: PracticeViewModel = viewModel()
    val model by vm.model.collectAsStateWithLifecycle()
    val mapping by vm.mapping.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density
    PracticeScreen(
        result = practice.result,
        failed = practice.failed,
        model = model,
        mapping = mapping,
        hapticsOn = settings.haptics.enabled,
        onboarding = onboarding,
        onDone = onDone,
        onBack = onBack,
        canvas = {
            Box(Modifier.fillMaxSize().onSizeChanged { practice.setSurface(it.width / density, it.height / density) }) {
                InkDrawing(practice.items, practice.live)
                AndroidView(
                    factory = { ctx -> TouchSurfaceView(ctx).apply { listener = TouchSurfaceView.Listener(practice::onTouch) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    )
}

/** 연습에서만 잉크를 그린다(낮에 확인용, screens SC08). 좌표는 dp. */
@Composable
private fun InkDrawing(items: List<InkItem>, live: List<Offset>) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxSize()) {
        val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun draw(points: List<Offset>) {
            if (points.size == 1) { drawCircle(color, stroke.width, points[0] * density); return }
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * density, p.y * density) else path.lineTo(p.x * density, p.y * density) }
            drawPath(path, color, style = stroke)
        }
        items.forEach { if (it is InkItem.Stroke) draw(it.stroke.points.map { p -> Offset(p.x, p.y) }) }
        if (live.isNotEmpty()) draw(live)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    result: String,
    failed: Boolean,
    model: ModelState,
    mapping: GestureMapping,
    hapticsOn: Boolean,
    onboarding: Boolean,
    onDone: () -> Unit,
    onBack: () -> Unit,
    canvas: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("연습", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(onBack) },
                actions = { StatusBadge(Badge(BadgeKind.Idle, "PC로 안 보내요"), Modifier.padding(end = Space.l)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.l)) { PrimaryButton("다 했어요", onDone) } },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (onboarding) LinearProgressIndicator({ 7f / ONBOARDING_STEPS }, Modifier.fillMaxWidth(), drawStopIndicator = {})
            Text(
                when {
                    failed -> "알아보지 못했어요"
                    result.isEmpty() -> "아래 영역에 써 보세요"
                    else -> result.lines().takeLast(2).joinToString("\n")
                },
                style = if (result.isEmpty() && !failed) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.displaySmall,
                color = if (result.isEmpty() || failed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(vertical = Space.s),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                if (model == ModelState.Ready) canvas()
                else Box(contentAlignment = Alignment.Center) {
                    Text(model.progressLabel() ?: "인식 모델이 준비되지 않았어요", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("받침, 한영 섞기를 써 보세요 · 한/영 맞추기는 연습에선 효과 없음", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState())) { GestureSummary(mapping, hapticsOn) }
        }
    }
}

@Preview(heightDp = 800)
@Composable
private fun PracticePreview() = PocketTheme {
    PracticeScreen("안녕 slack", false, ModelState.Ready, GestureMapping.DEFAULT, true, true, {}, {}) {}
}
