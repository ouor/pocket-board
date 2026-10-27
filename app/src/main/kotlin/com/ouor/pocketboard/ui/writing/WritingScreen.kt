package com.ouor.pocketboard.ui.writing

import android.app.Application
import android.content.Context
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.ouor.pocketboard.core.gesture.android.TouchSurfaceView
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.di.container
import com.ouor.pocketboard.session.WritingNotice
import com.ouor.pocketboard.session.writingNotice
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.components.BlockedScaffold
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.progressLabel
import com.ouor.pocketboard.ui.hasRequiredPermissions
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.pocket
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

// ---------- SC09 쓰기 화면 ----------

/** integration §6 그대로. 세션이 획·제스처·인식·전송·진동을 모두 한다. 화면은 그리지 않는다. */
class WritingViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val session = c.openWritingSession(viewModelScope)
    val notice = combine(c.hid.state, c.models.state, ::writingNotice)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    // A10: 미설치·실패면 SC04, 인식 불가면 SC02. 받는 중이면 여기 머물고 상단 한 줄.
    LaunchedEffect(model) {
        when (model) {
            is ModelState.Failed -> onNeedModel()
            ModelState.Unavailable -> onModelUnavailable()
            else -> Unit
        }
    }
    if (rememberTouchExploration()) TalkBackNotice(onExit) else WritingCanvas(model)
}

@Composable
private fun WritingCanvas(model: ModelState) {
    val writing: WritingViewModel = viewModel()
    val notice by writing.notice.collectAsStateWithLifecycle()
    val preview by writing.session.preview.collectAsStateWithLifecycle()
    LifecycleStartEffect(writing) {
        writing.onStart()
        onStopOrDispose { writing.onStop() }
    }
    WritingWindow()

    val density = LocalDensity.current
    val gestures = WindowInsets.systemGestures
    val direction = LocalLayoutDirection.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val insetPx = maxOf(gestures.getLeft(density, direction), gestures.getRight(density, direction))
    LaunchedEffect(size, insetPx) {
        if (size != IntSize.Zero) {
            writing.session.setSurface(size.width / density.density, size.height / density.density, insetPx / density.density)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.pocket.inkBlack)) {
        AndroidView(
            factory = { ctx -> TouchSurfaceView(ctx).apply { listener = TouchSurfaceView.Listener(writing.session::onTouch) } },
            modifier = Modifier.fillMaxSize().onSizeChanged { size = it },
        )
        NoticeLine(notice, model)
        PreviewLine(preview)
    }
}

/** 최저 밝기 한 줄(design-system §2.3): labelLarge, ink.dim, 상단 56dp, 애니메이션 없음. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.NoticeLine(notice: WritingNotice?, model: ModelState) {
    val text = when (notice) {
        null -> return
        WritingNotice.RECONNECTING -> "호스트 블루투스가 켜져 있는지 확인"
        WritingNotice.REPAIR_NEEDED -> "다시 페어링이 필요해요 · 뒤로 가서 확인"
        WritingNotice.BLUETOOTH_OFF -> "폰 블루투스가 꺼져 있어요"
        WritingNotice.MODEL_DOWNLOADING -> model.progressLabel() ?: "인식 모델 받는 중"
    }
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.pocket.inkDim, maxLines = 1,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
    )
}

/** F25 미리보기(design-system §2.3): 아래쪽 1/4 중앙, headlineSmall, ink.dimStrong, 1줄 앞쪽 생략. null 이면 없음. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PreviewLine(text: String?) {
    if (text.isNullOrEmpty()) return
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.25f), contentAlignment = Alignment.Center) {
        Text(
            text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.pocket.inkDimStrong,
            maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** 몰입 모드, 화면 켜짐(A11), 창 밝기 최저(A7). 나가면 모두 되돌린다. 세로 고정은 매니페스트. */
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
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF }
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
private fun WritingNoticePreview() = PocketTheme {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        NoticeLine(WritingNotice.MODEL_DOWNLOADING, ModelState.Downloading(1, 2))
        PreviewLine("안녕하세요 오늘 회의는 slack에")
    }
}

@Preview
@Composable
private fun TalkBackPreview() = PocketTheme { TalkBackNotice {} }
