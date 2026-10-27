package com.ouor.pocketboard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.HapticStrength
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.data.AppSettings
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.ConnectionTest
import com.ouor.pocketboard.ui.DevOptions
import com.ouor.pocketboard.ui.Hosts
import com.ouor.pocketboard.ui.Licenses
import com.ouor.pocketboard.ui.LocalSnackbar
import com.ouor.pocketboard.ui.Mapping
import com.ouor.pocketboard.ui.Model
import com.ouor.pocketboard.ui.Pairing
import com.ouor.pocketboard.ui.Practice
import com.ouor.pocketboard.ui.Privacy
import com.ouor.pocketboard.ui.Troubleshoot
import com.ouor.pocketboard.ui.Tutorial
import com.ouor.pocketboard.ui.components.ChoiceSheet
import com.ouor.pocketboard.ui.components.PageScaffold
import com.ouor.pocketboard.ui.components.RowKind
import com.ouor.pocketboard.ui.components.SettingGroup
import com.ouor.pocketboard.ui.components.SettingRow
import com.ouor.pocketboard.ui.components.gestureNames
import com.ouor.pocketboard.ui.components.progressLabel
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space
import kotlinx.coroutines.launch

// ---------- SC12 설정 ----------

private val IDLE_OPTIONS = listOf(10 to "10분", 30 to "30분", 60 to "1시간", 0 to "끊지 않음")
private val STRENGTH_OPTIONS = listOf(HapticStrength.WEAK to "약", HapticStrength.MEDIUM to "중", HapticStrength.STRONG to "강")
private const val DEV_TAPS = 7

@Composable
fun SettingsRoute(vm: AppViewModel, go: (Any) -> Unit, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val mapping by vm.mapping.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "-" }
    var taps by remember { mutableIntStateOf(0) }

    SettingsScreen(
        settings = settings, hostCount = hosts.size, model = model, mapping = mapping, version = version,
        update = vm::update,
        go = go,
        onVersionTap = {
            // B6: Android 빌드 번호 관례. 3번째 탭부터 남은 횟수 안내.
            if (settings.devOptionsVisible) return@SettingsScreen
            taps++
            val left = DEV_TAPS - taps
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                when {
                    left <= 0 -> { vm.update { it.copy(devOptionsVisible = true) }; taps = 0; snackbar.showSnackbar("개발자 옵션이 켜졌어요") }
                    taps >= 3 -> snackbar.showSnackbar("${left}번 더 누르면 개발자 옵션이 열려요")
                }
            }
        },
        onBack = onBack,
    )
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    hostCount: Int,
    model: ModelState,
    mapping: GestureMapping,
    version: String,
    update: ((AppSettings) -> AppSettings) -> Unit,
    go: (Any) -> Unit,
    onVersionTap: () -> Unit,
    onBack: () -> Unit,
) {
    var sheet by remember { mutableStateOf<String?>(null) }
    PageScaffold("설정", onBack) {
        SettingGroup("연결") {
            SettingRow("PC 목록", value = "${hostCount}대") { go(Hosts) }
            SettingRow("새 PC 추가") { go(Pairing(onboarding = false)) }
            SettingRow("연결 테스트", supporting = "\"안녕 Hello 123\"을 보내 확인해요") { go(ConnectionTest(onboarding = false)) }
            SettingRow(
                "안 쓰면 연결 끊기", RowKind.Value, supporting = "배터리를 아끼고 PC 연결을 정리해요",
                value = IDLE_OPTIONS.firstOrNull { it.first == settings.idleTimeoutMin }?.second ?: "${settings.idleTimeoutMin}분",
            ) { sheet = "idle" }
            SettingRow("PC 종류", RowKind.Info, value = "Windows", supporting = "다른 PC도 연결은 되지만 동작을 보장하지 않아요")
        }
        SettingGroup("입력") {
            SettingRow("제스처", supporting = "동작마다 제스처를 바꿔요") { go(Mapping) }
            SettingRow(
                "쓴 글씨 없을 때 ${mapping.gestureNames(Action.SEND)} = 엔터", RowKind.Toggle, checked = settings.emptySendEnter,
            ) { update { it.copy(emptySendEnter = !it.emptySendEnter) } }
        }
        SettingGroup("피드백·화면") {
            SettingRow("진동", RowKind.Toggle, checked = settings.haptics.enabled, supporting = "쓰는 동안 결과를 진동으로 알려요") {
                update { it.copy(haptics = it.haptics.copy(enabled = !it.haptics.enabled)) }
            }
            SettingRow(
                "진동 세기", RowKind.Value, value = STRENGTH_OPTIONS.first { it.first == settings.haptics.strength }.second,
                enabled = settings.haptics.enabled, supporting = if (settings.haptics.enabled) null else "진동이 꺼져 있어요",
            ) { sheet = "strength" }
            // F25: 세션에 미리보기 상태가 아직 없다(코어 요청). 그때까지 비활성.
            SettingRow("어두운 미리보기", RowKind.Toggle, checked = false, enabled = false, supporting = "쓰는 동안 결과를 아주 어둡게 보여 줘요 · 준비 중이에요")
        }
        SettingGroup("인식") {
            SettingRow(
                "인식 모델",
                value = if (model == ModelState.Ready) "한글·영어 준비됨" else null,
                supporting = when (model) {
                    is ModelState.Failed -> "받지 못했어요 · 다시 시도"
                    ModelState.Unavailable -> "Google Play 서비스가 없어 쓸 수 없어요"
                    else -> model.progressLabel()
                },
                warning = model is ModelState.Failed || model == ModelState.Unavailable,
            ) { go(Model(onboarding = false)) }
        }
        SettingGroup("도움말") {
            SettingRow("튜토리얼 다시 보기") { go(Tutorial(onboarding = false)) }
            SettingRow("연습하기", supporting = "PC로 보내지 않고 인식을 시험해요") { go(Practice(onboarding = false)) }
            SettingRow("연결 문제 해결") { go(Troubleshoot()) }
        }
        SettingGroup("정보") {
            SettingRow("개인정보") { go(Privacy) }
            SettingRow("오픈소스 라이선스") { go(Licenses) }
            SettingRow("버전", RowKind.Value, value = version, onClick = onVersionTap)
        }
        if (settings.devOptionsVisible) {
            SettingGroup("개발자") { SettingRow("개발자 옵션", icon = Icons.Rounded.Code) { go(DevOptions) } }
        }
    }
    when (sheet) {
        "idle" -> ChoiceSheet("안 쓰면 연결 끊기", IDLE_OPTIONS, settings.idleTimeoutMin, { v -> update { it.copy(idleTimeoutMin = v) } }) { sheet = null }
        "strength" -> ChoiceSheet("진동 세기", STRENGTH_OPTIONS, settings.haptics.strength, { v -> update { it.copy(haptics = it.haptics.copy(strength = v)) } }) { sheet = null }
    }
}

// ---------- 개인정보(B2, SC01 자세히와 같은 페이지) ----------

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    PageScaffold("개인정보", onBack) {
        PrivacyBlock("보내지 않는 것", "쓴 글씨, 입력한 글자. 글씨 인식은 폰 안에서 하고, 결과는 블루투스로 PC에만 들어가요.")
        PrivacyBlock("보내는 것", "인식 도구(Google ML Kit)의 사용 통계가 Google로 가요. 앱에서 끌 수 없어요. 쓴 내용은 들어가지 않아요.")
        PrivacyBlock("인터넷을 쓰는 곳", "인식 모델 받기(처음 한 번, 약 40MB)와 위 사용 통계뿐이에요.")
        PrivacyBlock("폰에만 남는 것", "설정, 제스처, 연결한 PC 목록. 개발자 옵션의 접촉 기록은 켰을 때만 남고, 직접 내보내기 전에는 밖으로 나가지 않아요.")
        PrivacyBlock("없는 것", "광고, 가입, 구독, 계정")
    }
}

@Composable
private fun PrivacyBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(heightDp = 1400)
@Composable
private fun SettingsPreview() = PocketTheme {
    SettingsScreen(AppSettings(devOptionsVisible = true), 3, ModelState.Ready, GestureMapping.DEFAULT, "1.0", {}, {}, {}, {})
}

@Preview(heightDp = 720)
@Composable
private fun PrivacyPreview() = PocketTheme { PrivacyScreen {} }
