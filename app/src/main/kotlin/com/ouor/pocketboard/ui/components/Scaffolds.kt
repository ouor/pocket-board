package com.ouor.pocketboard.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space

/** 온보딩 단계 수(환영·권한·모델·페어링·테스트·튜토리얼·연습). */
const val ONBOARDING_STEPS = 7

/** 화면 주 버튼: 폭 꽉, 높이 56dp(design-system §4). [loading]이면 버튼 안 진행 표시. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick, enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = Space.button),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}

@Composable
fun ScreenTitle(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * 온보딩 뼈대(design-system §6.3): 진행 막대(+건너뛰기) / 모델 진행 줄 / 제목·설명 / 본문(스크롤) / 하단 고정 버튼.
 * [step]이 null 이면 진행 막대 대신 [onBack] 있는 TopAppBar(설정에서 다시 볼 때).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScaffold(
    title: String,
    step: Int?,
    description: String? = null,
    onSkip: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    model: ModelState? = null,
    onModelClick: () -> Unit = {},
    bottom: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            if (step == null) {
                TopAppBar(
                    title = {},
                    navigationIcon = { onBack?.let { BackButton(it) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.xs), content = bottom,
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (step != null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Space.gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { step / ONBOARDING_STEPS.toFloat() },
                        modifier = Modifier.weight(1f),
                        drawStopIndicator = {},
                    )
                    if (onSkip != null) TextButton(onClick = onSkip) { Text("건너뛰기") }
                }
            }
            model?.let { ModelLine(it, onModelClick) }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.gutter).padding(top = Space.xl, bottom = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.xl),
            ) {
                ScreenTitle(title, description)
                content()
            }
        }
    }
}

/** 온보딩 공통 줄(screens 온보딩 공통): 받는 중이면 안내, 실패면 경고(탭 → SC04). Ready 면 없음. */
@Composable
private fun ModelLine(state: ModelState, onClick: () -> Unit) {
    val failed = state is ModelState.Failed
    val text = state.progressLabel() ?: if (failed) "모델 받기 실패 · 다시 시도" else return
    Surface(
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(horizontal = Space.gutter).fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.CloudDownload, null, Modifier.size(18.dp),
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 막다른/차단 화면(SC02, SC03 거부, TalkBack 안내): 큰 아이콘 + 제목 + 이유 + 버튼. */
@Composable
fun BlockedScaffold(
    title: String,
    body: String,
    icon: ImageVector = Icons.Rounded.ErrorOutline,
    detail: String? = null,
    bottom: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Scaffold(
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs), content = bottom)
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Spacer(Modifier.height(Space.xxl))
            Icon(icon, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
            ScreenTitle(title, body)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

/** SC10~SC14, SC17 의 일반 화면: TopAppBar(뒤로 + 제목) + 스크롤 본문. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { onBack?.let { BackButton(it) } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            bottom?.let { Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l), content = it) }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter).padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
            content = content,
        )
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로") }
}


@Preview(heightDp = 640)
@Composable
private fun OnboardingScaffoldPreview() = PocketTheme {
    OnboardingScaffold(
        "PC에서 키보드를 추가해 주세요", step = 4, model = ModelState.Downloading(1, 2),
        bottom = { PrimaryButton("다음", {}) },
    ) { WarningCard("PC에 한국어 입력기가 있어야 한글이 들어가요") }
}

@Preview(heightDp = 640)
@Composable
private fun BlockedScaffoldPreview() = PocketTheme {
    BlockedScaffold("이 폰은 블루투스 키보드로 쓸 수 없어요", "폰 제조사가 이 기능을 막아 두었어요.", detail = "기기: Galaxy · Android 14") {}
}
