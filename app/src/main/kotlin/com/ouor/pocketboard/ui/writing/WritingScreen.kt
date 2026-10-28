package com.ouor.pocketboard.ui.writing

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.TouchAction
import com.ouor.pocketboard.core.gesture.TouchSample
import com.ouor.pocketboard.core.gesture.android.TouchSurfaceView
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.di.container
import com.ouor.pocketboard.session.WritingEvent
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.components.Badge
import com.ouor.pocketboard.ui.components.BadgeKind
import com.ouor.pocketboard.ui.components.BlockedScaffold
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.StatusDot
import com.ouor.pocketboard.ui.components.flashLabel
import com.ouor.pocketboard.ui.components.gesturesFor
import com.ouor.pocketboard.ui.components.label
import com.ouor.pocketboard.ui.components.progressLabel
import com.ouor.pocketboard.ui.components.writingStatus
import com.ouor.pocketboard.ui.hasRequiredPermissions
import com.ouor.pocketboard.ui.theme.PocketColors
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.pocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

// ---------- SC09 쓰기 화면 v2 (docs/design/sc09-writing-v2.md) ----------

/** 사용자 결정: 쓰기 화면 창 밝기 10%(§4.1). */
private const val WRITING_BRIGHTNESS = 0.10f

/** integration §6. 세션이 획·제스처·인식·전송·진동을 한다. 화면은 세션 상태를 흐리게 비춰 줄 뿐(P1). */
class WritingViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val session = c.openWritingSession(viewModelScope)
    val hid = c.hid.state
    val model = c.models.state
    val mapping = c.mapping

    fun onStart() = c.connection.ensureStarted()
    fun onStop() = session.onLeave() // E13: 미전송 획 버림, 연결 유지
    override fun onCleared() = session.close()
}

@Composable
fun WritingRoute(
    vm: AppViewModel,
    onExit: () -> Unit,
    onNeedModel: () -> Unit,
    onModelUnavailable: () -> Unit,
    onNeedPermission: () -> Unit,
) {
    val context = LocalContext.current
    val model by vm.model.collectAsStateWithLifecycle()
    val permitted = remember { context.hasRequiredPermissions() }
    if (!permitted) {
        LaunchedEffect(Unit) { onNeedPermission() } // 앱 실행 중 권한이 빠짐(screens SC03)
        return
    }
    // A10: 미설치·실패면 SC04, 인식 불가면 SC02. 받는 중이면 여기 머물고 상태 줄 아래 한 줄.
    LaunchedEffect(model) {
        when (model) {
            is ModelState.Failed -> onNeedModel()
            ModelState.Unavailable -> onModelUnavailable()
            else -> Unit
        }
    }
    if (rememberTouchExploration()) TalkBackNotice(onExit) else WritingCanvas()
}

/** 사라지는 획 묶음(§4.4): 보냄은 위로 뜨며, 지움·실패는 제자리에서. */
private class Ghost(val strokes: List<InkStroke>, val rise: Boolean) {
    val progress = Animatable(0f)
}

@Composable
private fun WritingCanvas() {
    val writing: WritingViewModel = viewModel()
    val session = writing.session
    val hid by writing.hid.collectAsStateWithLifecycle()
    val model by writing.model.collectAsStateWithLifecycle()
    val mapping by writing.mapping.collectAsStateWithLifecycle()
    val strokes by session.strokes.collectAsStateWithLifecycle()
    val lastSent by session.lastSent.collectAsStateWithLifecycle()
    val edgeBand by session.edgeBandDp.collectAsStateWithLifecycle()
    val preview by session.preview.collectAsStateWithLifecycle()

    var intro by remember { mutableStateOf(true) }
    LifecycleStartEffect(writing) {
        intro = true // 새 방문(E13 뒤 포함)마다 처음 안내
        writing.onStart()
        onStopOrDispose { writing.onStop() }
    }
    WritingWindow()

    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val live = remember { LiveInk(ViewConfiguration.get(context).scaledTouchSlop / density.density, scope) }
    val gestures = WindowInsets.systemGestures
    val direction = LocalLayoutDirection.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val insetPx = maxOf(gestures.getLeft(density, direction), gestures.getRight(density, direction))
    LaunchedEffect(size, insetPx) {
        if (size != IntSize.Zero) {
            session.setSurface(size.width / density.density, size.height / density.density, insetPx / density.density)
        }
    }
    live.bandDp = edgeBand
    live.widthDp = size.width / density.density
    live.heightDp = size.height / density.density

    // 끝난 획 흐려짐(0.8초 뒤 0.6초): 흐려지는 동안만 프레임을 돈다.
    val clock = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(strokes) {
        do {
            withFrameMillis { clock.longValue = SystemClock.uptimeMillis() }
        } while (strokes.any { clock.longValue - it.endMs < FADE_END_MS })
    }

    // ② 최근 줄과 사라짐 효과
    val p = MaterialTheme.pocket
    val ghosts = remember { mutableStateListOf<Ghost>() }
    val recentAlpha = remember { Animatable(1f) }
    val recentBright = remember { Animatable(0f) } // 0 = ink.faint, 1 = ink.dim. 진입 시 이전 글은 흐린 단계
    val flashAlpha = remember { Animatable(0f) }
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        var recentJob: Job? = null
        var flashJob: Job? = null
        session.writingEvents.collect { e ->
            when (e) {
                is WritingEvent.Sent -> {
                    vanish(ghosts, Ghost(e.strokes, rise = true), 200, FastOutLinearInEasing)
                    recentJob?.cancel()
                    recentJob = launch {
                        recentAlpha.snapTo(0f)
                        recentBright.snapTo(1f)
                        delay(100)
                        recentAlpha.animateTo(1f, tween(150, easing = LinearOutSlowInEasing))
                        delay(4_000)
                        recentBright.animateTo(0f, tween(400, easing = LinearEasing))
                    }
                }
                is WritingEvent.StrokeRemoved -> vanish(ghosts, Ghost(listOf(e.stroke), rise = false), 120, LinearEasing)
                is WritingEvent.StrokesDiscarded -> vanish(ghosts, Ghost(e.strokes, rise = false), 160, LinearEasing)
                else -> Unit
            }
            e.flashLabel()?.let { text ->
                flashJob?.cancel()
                flashJob = launch {
                    flash = text
                    flashAlpha.snapTo(1f)
                    delay(800)
                    flashAlpha.animateTo(0f, tween(200))
                    flash = null
                }
            }
        }
    }
    val line: String?
    val lineColor: Color
    val modelLine = model.progressLabel()
    when {
        flash != null -> { line = flash; lineColor = p.inkDim.copy(alpha = flashAlpha.value) }
        modelLine != null -> { line = modelLine; lineColor = p.inkDim }
        else -> { line = lastSent.lastOrNull(); lineColor = lerp(p.inkFaint, p.inkDim, recentBright.value).copy(alpha = recentAlpha.value) }
    }
    val introAlpha by animateFloatAsState(if (intro) 1f else 0f, tween(150), label = "intro")

    Box(Modifier.fillMaxSize().background(p.inkBlack)) {
        WritingDisplay(
            status = hid.writingStatus(),
            line = line,
            lineColor = lineColor,
            introAlpha = introAlpha,
            sendHint = mapping.sendHint(),
            edgeBandDp = edgeBand,
            preview = preview,
        )
        InkLayer(strokes, ghosts, live.points, now = { clock.longValue })
        // 입력은 맨 위의 이 뷰 하나만 받는다(§5.2 방법 A). 투명하고 그리지 않으므로 아래 표시가 그대로 보인다.
        AndroidView(
            factory = { ctx ->
                TouchSurfaceView(ctx).apply {
                    listener = TouchSurfaceView.Listener { samples ->
                        if (intro && samples.any { it.action == TouchAction.DOWN }) intro = false
                        live.onTouch(samples)
                        session.onTouch(samples)
                    }
                }
            },
            modifier = Modifier.fillMaxSize().onSizeChanged { size = it },
        )
    }
}

private fun CoroutineScope.vanish(ghosts: MutableList<Ghost>, ghost: Ghost, ms: Int, easing: Easing) {
    if (ghost.strokes.isEmpty()) return
    ghosts += ghost
    launch {
        try {
            ghost.progress.animateTo(1f, tween(ms, easing = easing))
        } finally {
            ghosts -= ghost
        }
    }
}

private fun GestureMapping.sendHint(): String? =
    gesturesFor(Action.SEND).firstOrNull()?.let { "${it.label}하면 PC로 보내져요" }

/**
 * 진행 중 획(§4.3): 세션을 거치지 않고 터치에서 바로. 첫 손가락 하나만, 터치 슬롭을 넘은 뒤부터(탭은 안 그림),
 * 둘째 손가락이 닿으면 제스처라 즉시 지운다. 손을 떼면 [HANDOFF_MS] 동안 남겨 세션의 끝난 획으로 넘긴다.
 */
private class LiveInk(private val slopDp: Float, private val scope: CoroutineScope) {
    /** 그릴 점(dp). 슬롭을 넘기 전에는 비어 있다. */
    val points = mutableStateListOf<Offset>()
    var bandDp = 0f
    var widthDp = 0f
    var heightDp = 0f
    private val pending = ArrayList<Offset>()
    private var pointer = NONE
    private var down = 0
    private var serial = 0

    fun onTouch(samples: List<TouchSample>) {
        for (s in samples) {
            val at = Offset(s.xDp, s.yDp)
            when (s.action) {
                TouchAction.DOWN -> {
                    down++
                    serial++
                    points.clear()
                    pending.clear()
                    pointer = if (down == 1 && !inEdgeBand(at)) s.pointerId else NONE
                    if (pointer != NONE) pending += at
                }
                TouchAction.MOVE -> if (s.pointerId == pointer) {
                    if (points.isNotEmpty()) points += at
                    else {
                        pending += at
                        if ((at - pending[0]).getDistance() > slopDp) { points.addAll(pending); pending.clear() }
                    }
                }
                TouchAction.UP -> {
                    down = max(0, down - 1)
                    if (s.pointerId == pointer) {
                        pointer = NONE
                        if (points.isNotEmpty()) points += at
                        val n = serial
                        scope.launch { delay(HANDOFF_MS); if (serial == n) points.clear() }
                    }
                }
                TouchAction.CANCEL -> { down = 0; pointer = NONE; points.clear(); pending.clear() }
            }
        }
    }

    // 판정기 EdgeStartRule 과 같은 경계: 띠에서 시작한 획은 글씨가 안 되므로 그리지 않는다(잡힌 것처럼 보이지 않게).
    private fun inEdgeBand(p: Offset) = bandDp > 0f &&
        (p.x < bandDp || p.y < bandDp || (widthDp > 0f && p.x > widthDp - bandDp) || (heightDp > 0f && p.y > heightDp - bandDp))

    companion object {
        private const val NONE = -1
        const val HANDOFF_MS = 300L
    }
}

private const val FADE_START_MS = 800L
private const val FADE_END_MS = 1_400L
private val InkStroke.endMs get() = points.lastOrNull()?.t ?: 0L

/** 끝난 획 알파: 끝난 지 0.8초 뒤부터 0.6초 동안 1.0 → 0.4(§4.3). */
private fun settledAlpha(ageMs: Long): Float = when {
    ageMs <= FADE_START_MS -> 1f
    ageMs >= FADE_END_MS -> 0.4f
    else -> 1f - 0.6f * (ageMs - FADE_START_MS) / (FADE_END_MS - FADE_START_MS)
}

/** ⑤ 잉크: 순수 그리기(입력·시맨틱스 없음). 상태는 그리기 단계에서만 읽어 쓰는 중에는 다시 그리기만 한다. */
@Composable
private fun InkLayer(strokes: List<InkStroke>, ghosts: List<Ghost>, live: List<Offset>, now: () -> Long) {
    val color = MaterialTheme.pocket.inkTrail
    Canvas(Modifier.fillMaxSize()) {
        val t = now()
        for (s in strokes) ink(s.points.map { Offset(it.x, it.y) }, color, settledAlpha(t - s.endMs))
        for (g in ghosts) {
            val k = g.progress.value
            for (s in g.strokes) ink(s.points.map { Offset(it.x, it.y) }, color, settledAlpha(t - s.endMs) * (1f - k), if (g.rise) -16f * k else 0f)
        }
        ink(live, color, 1f)
    }
}

/** 3dp 둥근 선, 중점 2차 베지어(§4.3). 1점 획은 지름 4dp 원. 좌표는 dp. */
private fun DrawScope.ink(dp: List<Offset>, color: Color, alpha: Float, dyDp: Float = 0f) {
    if (dp.isEmpty() || alpha <= 0f) return
    val d = density
    fun px(o: Offset) = Offset(o.x * d, (o.y + dyDp) * d)
    if (dp.size == 1) { drawCircle(color, 2.dp.toPx(), px(dp[0]), alpha); return }
    val path = Path()
    val first = px(dp[0])
    path.moveTo(first.x, first.y)
    for (i in 1 until dp.size) {
        val a = px(dp[i - 1])
        val b = px(dp[i])
        path.quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
    }
    val last = px(dp.last())
    path.lineTo(last.x, last.y)
    drawPath(path, color, alpha, Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * ①~④, ⑥: 상태 줄, 최근 줄(보낸 글·동작 표시·모델 받는 중), 처음 안내와 쓰기 영역 테두리, F25 미리보기.
 * 순수 표시: 누를 수 있는 것도 시맨틱스도 없다(P5, §5.2).
 */
@Composable
private fun WritingDisplay(
    status: Badge,
    line: String?,
    lineColor: Color,
    introAlpha: Float,
    sendHint: String?,
    edgeBandDp: Float,
    preview: String?,
) {
    val p = MaterialTheme.pocket
    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        if (introAlpha > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                val band = edgeBandDp.dp.toPx()
                val top = 96.dp.toPx()
                drawRoundRect(
                    p.inkFaint, Offset(band, top), Size(size.width - 2 * band, size.height - top - 24.dp.toPx()),
                    CornerRadius(28.dp.toPx()), Stroke(1.dp.toPx()), alpha = introAlpha,
                )
            }
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("여기에 쓰세요", style = MaterialTheme.typography.titleMedium, color = p.inkDim.copy(alpha = introAlpha))
                val small = MaterialTheme.typography.bodyMedium
                sendHint?.let { Text(it, style = small, color = p.inkFaint.copy(alpha = introAlpha), textAlign = TextAlign.Center) }
                Text("나가기: 뒤로 가기 두 번", style = small, color = p.inkFaint.copy(alpha = introAlpha))
            }
        }
        StatusLine(status, p)
        if (!line.isNullOrEmpty()) {
            Text(
                line, color = lineColor, maxLines = 1, overflow = TextOverflow.StartEllipsis, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 68.dp, start = 24.dp, end = 24.dp),
            )
        }
        PreviewLine(preview)
    }
}

/** ① 점 + 글자, 상단 40dp. 점만 상태 색 × 0.45, 글자는 ink.dim. 바뀔 때 애니 없음. */
@Composable
private fun BoxScope.StatusLine(status: Badge, p: PocketColors) {
    val dot = when (status.kind) {
        BadgeKind.Connected -> p.connected
        BadgeKind.Connecting, BadgeKind.Retrying -> p.connecting
        BadgeKind.Problem -> p.problem
        BadgeKind.Idle, BadgeKind.NotSetUp -> p.idle
    }.copy(alpha = 0.45f)
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = 40.dp, start = 24.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(status.kind, dot, 6.dp, problemSize = 10.dp)
        Text(status.label, style = MaterialTheme.typography.labelLarge, color = p.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** ⑥ F25 미리보기(현행): 아래쪽 1/4 중앙, headlineSmall, ink.dimStrong, 1줄 앞쪽 생략. null 이면 없음. */
@Composable
private fun BoxScope.PreviewLine(text: String?) {
    if (text.isNullOrEmpty()) return
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.25f), contentAlignment = Alignment.Center) {
        Text(
            text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.pocket.inkDimStrong,
            maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

// ---------- 프리뷰 (sc09-writing-v2 §5.3) ----------

private val previewHost = HostDevice("00:11", "내 노트북", null)

@Composable
private fun PreviewScreen(
    hid: HidState = HidState.Connected(previewHost),
    line: String? = null,
    bright: Boolean = false,
    intro: Boolean = false,
    strokes: List<InkStroke> = emptyList(),
    live: List<Offset> = emptyList(),
    preview: String? = null,
    now: Long = 0L,
) = PocketTheme {
    val p = MaterialTheme.pocket
    Box(Modifier.fillMaxSize().background(p.inkBlack)) {
        WritingDisplay(hid.writingStatus(), line, if (bright) p.inkDim else p.inkFaint, if (intro) 1f else 0f, GestureMapping.DEFAULT.sendHint(), 24f, preview)
        InkLayer(strokes, emptyList(), live) { now }
    }
}

/** 미리보기용 획: (x, y) dp 쌍, 끝난 시각 [end]. */
private fun stroke(end: Long, vararg xy: Float) = InkStroke(xy.toList().chunked(2).map { (x, y) -> InkPoint(x, y, end) })

private val sampleStrokes = listOf(
    stroke(0, 120f, 330f, 122f, 370f, 124f, 400f, 150f, 402f, 190f, 400f), // ㄴ (흐려짐 끝)
    stroke(1_000, 230f, 320f, 231f, 360f, 230f, 410f), // ㅣ (흐려지는 중)
)

@Preview(widthDp = 411, heightDp = 823)
@Composable
private fun WritingIdlePreview() = PreviewScreen(intro = true, line = "확인했어요 내일 배포 OK")

@Preview(widthDp = 411, heightDp = 823)
@Composable
private fun WritingInkPreview() = PreviewScreen(
    line = "확인했어요 내일 배포 OK",
    strokes = sampleStrokes,
    live = listOf(Offset(270f, 330f), Offset(285f, 345f), Offset(300f, 370f), Offset(310f, 400f)),
    preview = "안녕 sl",
    now = 1_500L,
)

@Preview(widthDp = 411, heightDp = 823)
@Composable
private fun WritingSentPreview() = PreviewScreen(line = "확인했어요 내일 배포 OK", bright = true)

@Preview(widthDp = 411, heightDp = 823)
@Composable
private fun WritingReconnectingPreview() = PreviewScreen(
    hid = HidState.Reconnecting(previewHost, 2, repairLikely = false),
    line = "확인했어요 내일 배포 OK",
    strokes = sampleStrokes,
    now = 5_000L,
)

@Preview(widthDp = 411, heightDp = 823)
@Composable
private fun WritingModelPreview() = PreviewScreen(intro = true, line = ModelState.Downloading(1, 2).progressLabel(), bright = true)

/** 몰입 모드, 화면 켜짐(A11), 창 밝기 10%(sc09-writing-v2 P3). 나가면 모두 되돌린다. 세로 고정은 매니페스트. */
@Composable
private fun WritingWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = WRITING_BRIGHTNESS }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = previous }
        }
    }
}

/** TalkBack(터치 탐색)이 켜져 있는지. 켜고 끄면 바로 따라간다. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val am = remember { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var on by remember { mutableStateOf(am.isTouchExplorationEnabled) }
    DisposableEffect(am) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        am.addTouchExplorationStateChangeListener(listener)
        onDispose { am.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/** A9: TalkBack 이 켜져 있으면 입력을 받지 않고 안내만(일반 대비). */
@Composable
fun TalkBackNotice(onHome: () -> Unit) {
    BlockedScaffold(
        title = "TalkBack이 켜져 있으면 손글씨를 쓸 수 없어요",
        body = "TalkBack을 끈 뒤 다시 열어 주세요",
        icon = Icons.Rounded.Accessibility,
        bottom = { PrimaryButton("홈으로", onHome) },
    )
}

@Preview
@Composable
private fun TalkBackPreview() = PocketTheme { TalkBackNotice {} }
