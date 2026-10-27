package com.ouor.pocketboard.ui.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.components.OnboardingScaffold
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.RowKind
import com.ouor.pocketboard.ui.components.SecondaryButton
import com.ouor.pocketboard.ui.components.SettingGroup
import com.ouor.pocketboard.ui.components.SettingRow
import com.ouor.pocketboard.ui.components.TipCard
import com.ouor.pocketboard.ui.components.WarningCard
import com.ouor.pocketboard.ui.theme.pocket
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space

// ---------- SC04 모델 다운로드 ----------

/**
 * 세 가지 진입: 온보딩(진행 막대, 받는 중에도 다음 단계로), 실행 시 모델 없음([toWriting], 받으면 쓰기 화면, A10),
 * 설정 > 인식 모델(완료 상태 + 다시 받기).
 */
@Composable
fun ModelRoute(vm: AppViewModel, onboarding: Boolean, toWriting: Boolean, onNext: () -> Unit, onUnavailable: () -> Unit, onBack: () -> Unit) {
    val state by vm.model.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.ensureDownloaded() }
    val context = LocalContext.current
    var cellular by remember { mutableStateOf(false) }
    LifecycleResumeEffect(state) { // 돌아올 때마다(와이파이 켜고 오는 경우) 다시 본다
        cellular = context.onMobileData()
        onPauseOrDispose {}
    }
    LaunchedEffect(state) {
        if (state == ModelState.Unavailable) onUnavailable()
        if (toWriting && state == ModelState.Ready) onNext()
    }
    ModelScreen(state, onboarding, cellular = cellular, onRetry = vm::ensureDownloaded, onNext = onNext, onRedownload = vm::redownload, onBack = onBack)
}

@Composable
fun ModelScreen(state: ModelState, onboarding: Boolean, onRetry: () -> Unit, onNext: () -> Unit, onRedownload: () -> Unit, onBack: () -> Unit, cellular: Boolean = false) {
    var confirm by remember { mutableStateOf(false) }
    val title = when (state) {
        ModelState.Checking -> "글씨 인식 모델을 받을게요"
        is ModelState.Downloading -> "받는 중이에요"
        is ModelState.Failed -> "받지 못했어요"
        ModelState.Ready -> "준비됐어요"
        ModelState.Unavailable -> "글씨 인식을 쓸 수 없어요"
    }
    val loading = state == ModelState.Checking || state is ModelState.Downloading
    OnboardingScaffold(
        title = title,
        description = when {
            state == ModelState.Ready -> "이제 인터넷 없이도 동작해요"
            loading && onboarding -> "기다리지 않아도 돼요. 다음 단계와 함께 진행해요"
            loading -> "한글 · 영어, 약 40MB"
            else -> null
        },
        step = if (onboarding) 3 else null,
        onBack = onBack,
        bottom = {
            when {
                state is ModelState.Failed -> PrimaryButton("다시 시도", onRetry)
                onboarding && state == ModelState.Ready -> PrimaryButton("다음", onNext)
                onboarding && loading -> PrimaryButton("다음 단계로", onNext)
                !onboarding && state == ModelState.Ready -> SecondaryButton("다시 받기", { confirm = true })
            }
        },
    ) {
        when (state) {
            ModelState.Checking, is ModelState.Downloading -> {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth()) // 진행률 API 없음(A17)
                    if (state is ModelState.Downloading) {
                        Text("모델 ${state.completed}/${state.total} 완료", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (cellular) WarningCard("와이파이에 연결하면 데이터 요금 없이 받을 수 있어요.", title = "모바일 데이터로 받아요 · 약 40MB")
                else TipCard("한 번 받으면 인터넷 없이 동작해요.", Icons.Rounded.Wifi, title = "한글 · 영어, 약 40MB")
            }
            is ModelState.Failed -> WarningCard(
                if (state.reason == ModelState.Reason.NETWORK) "인터넷에 연결한 뒤 다시 시도해 주세요. 한 번만 받으면 돼요."
                else "잠시 뒤 다시 시도해 주세요. 계속 안 되면 폰을 다시 켜 보세요.",
                title = if (state.reason == ModelState.Reason.NETWORK) "인터넷 연결이 필요해요" else null,
            )
            ModelState.Ready -> SettingGroup {
                val ok = MaterialTheme.pocket.connected
                SettingRow("한글", RowKind.Info, trailing = { Icon(Icons.Rounded.CheckCircle, "준비됨", tint = ok) })
                SettingRow("영어", RowKind.Info, trailing = { Icon(Icons.Rounded.CheckCircle, "준비됨", tint = ok) })
            }
            ModelState.Unavailable -> Text("Google Play 서비스가 필요해요", style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("모델을 다시 받을까요?") },
            text = { Text("지우고 다시 받는 동안(약 40MB, 인터넷 필요) 글씨 인식을 쓸 수 없어요.") },
            confirmButton = { TextButton({ confirm = false; onRedownload() }) { Text("다시 받기") } },
            dismissButton = { TextButton({ confirm = false }) { Text("취소") } },
        )
    }
}

@Preview(heightDp = 720)
@Composable
private fun ModelDownloadingPreview() = PocketTheme { ModelScreen(ModelState.Downloading(1, 2), true, {}, {}, {}, {}) }

/** 지금 쓰는 네트워크가 모바일 데이터인지(SC04 경고). */
private fun Context.onMobileData(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return false
    return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
}

@Preview(heightDp = 720)
@Composable
private fun ModelCellularPreview() = PocketTheme { ModelScreen(ModelState.Checking, true, {}, {}, {}, {}, cellular = true) }

@Preview(heightDp = 720)
@Composable
private fun ModelFailedPreview() = PocketTheme { ModelScreen(ModelState.Failed(ModelState.Reason.NETWORK), true, {}, {}, {}, {}) }

@Preview(heightDp = 720)
@Composable
private fun ModelReadySettingsPreview() = PocketTheme { ModelScreen(ModelState.Ready, false, {}, {}, {}, {}) }
