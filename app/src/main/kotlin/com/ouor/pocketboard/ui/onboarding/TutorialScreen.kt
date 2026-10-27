package com.ouor.pocketboard.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.HapticPattern
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.components.GestureGlyph
import com.ouor.pocketboard.ui.components.HapticNotation
import com.ouor.pocketboard.ui.components.OnboardingScaffold
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.RowKind
import com.ouor.pocketboard.ui.components.SecondaryButton
import com.ouor.pocketboard.ui.components.SettingGroup
import com.ouor.pocketboard.ui.components.SettingRow
import com.ouor.pocketboard.ui.components.gestureNames
import com.ouor.pocketboard.ui.components.gesturesFor
import com.ouor.pocketboard.ui.components.haptic
import com.ouor.pocketboard.ui.components.label
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space
import kotlinx.coroutines.launch

// ---------- SC07 튜토리얼(30초) ----------

private const val PAGES = 5

@Composable
fun TutorialRoute(vm: AppViewModel, onboarding: Boolean, onPractice: () -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    val mapping by vm.mapping.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    TutorialScreen(
        mapping = mapping,
        emptySendEnter = settings.emptySendEnter,
        hapticsOn = settings.haptics.enabled,
        onboarding = onboarding,
        onFeel = vm::play,
        onHaptics = { on -> vm.update { it.copy(haptics = it.haptics.copy(enabled = on)) } },
        onPractice = onPractice,
        onDone = onDone,
        onBack = onBack,
    )
}

@Composable
fun TutorialScreen(
    mapping: GestureMapping,
    emptySendEnter: Boolean,
    hapticsOn: Boolean,
    onboarding: Boolean,
    onFeel: (HapticPattern) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onPractice: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1
    OnboardingScaffold(
        title = "화면은 안 봐도 돼요\n손과 진동만 기억하세요",
        step = if (onboarding) 6 else null,
        onSkip = if (onboarding) onDone else null,
        onBack = onBack,
        bottom = {
            if (last) {
                PrimaryButton("연습해 보기", onPractice)
                SecondaryButton(if (onboarding) "바로 쓰기" else "닫기", onDone)
            } else {
                PrimaryButton("다음", { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } })
            }
        },
    ) {
        HorizontalPager(pager, pageSpacing = Space.l) { page ->
            when (page) {
                0 -> TutorialCard(Action.SEND, "쓰고 → ${mapping.gestureNames(Action.SEND)}", "글씨가 PC로 가요", mapping, hapticsOn, onFeel)
                1 -> TutorialCard(Action.BACKSPACE, "한 글자 지우기 → ${mapping.gestureNames(Action.BACKSPACE)}", "보내기 전이면 마지막 획만 지워요", mapping, hapticsOn, onFeel)
                2 -> TutorialCard(Action.SPACE, "띄어쓰기 → ${mapping.gestureNames(Action.SPACE)}", "빈칸을 하나 넣어요", mapping, hapticsOn, onFeel)
                3 -> {
                    val enterBy = buildList {
                        if (emptySendEnter) add("빈 화면에서 ${mapping.gestureNames(Action.SEND)}")
                        if (mapping.gesturesFor(Action.ENTER).isNotEmpty()) add(mapping.gestureNames(Action.ENTER))
                    }.joinToString(" 또는 ").ifEmpty { "(없음)" }
                    TutorialCard(Action.ENTER, "엔터 → $enterBy", "엔터 실수가 잦으면 설정에서 다른 제스처로 바꿀 수 있어요", mapping, hapticsOn, onFeel, glyphFrom = if (emptySendEnter) Action.SEND else Action.ENTER)
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    Text(
                        if (hapticsOn) "진동은 켜져 있어요. 옆 사람이 깰까 걱정되면 끄세요." else "진동이 꺼져 있어요. 쓰는 동안 결과를 알려면 켜 두는 게 좋아요.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    SettingGroup { SettingRow("진동", RowKind.Toggle, icon = Icons.Rounded.Vibration, checked = hapticsOn) { onHaptics(!hapticsOn) } }
                    Text("설정 > 진동에서도 바꿀 수 있어요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        PageDots(pager.currentPage)
    }
}

/** C3 Tutorial 변형: 큰 글리프(애니) + 제목 + 설명 + 햅틱 표기 + [느껴보기]. */
@Composable
private fun TutorialCard(
    action: Action,
    title: String,
    text: String,
    mapping: GestureMapping,
    hapticsOn: Boolean,
    onFeel: (HapticPattern) -> Unit,
    glyphFrom: Action = action,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
            mapping.gesturesFor(glyphFrom).firstOrNull()?.let { GestureGlyph(it, 96.dp, animate = true) }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Text("진동", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HapticNotation(action, hapticsOn)
                if (hapticsOn) {
                    FilledTonalButton({ onFeel(action.haptic) }) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Text("느껴보기", Modifier.padding(start = Space.xs))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(current: Int) {
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "${current + 1} / $PAGES 페이지" },
        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
    ) {
        repeat(PAGES) { i ->
            Box(
                Modifier.size(8.dp).background(
                    if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape,
                ),
            )
        }
    }
}

@Preview(heightDp = 760)
@Composable
private fun TutorialPreview() = PocketTheme {
    TutorialScreen(GestureMapping.DEFAULT, true, true, true, {}, {}, {}, {}, {})
}
