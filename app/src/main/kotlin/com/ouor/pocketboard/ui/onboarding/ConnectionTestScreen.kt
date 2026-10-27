package com.ouor.pocketboard.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.pocketboard.core.hid.SendResult
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.di.AppContainer
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.LocalSnackbar
import com.ouor.pocketboard.ui.components.OnboardingScaffold
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.SecondaryButton
import com.ouor.pocketboard.ui.components.StatusBadge
import com.ouor.pocketboard.ui.components.StepCard
import com.ouor.pocketboard.ui.components.WarningCard
import com.ouor.pocketboard.ui.components.badge
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space
import kotlinx.coroutines.launch

// ---------- SC06 연결 테스트 ----------

/** SC14 증상(순서 = Troubleshoot.symptom). */
val SYMPTOMS = listOf("영어 글자만 나와요 (예: dkssud)", "한글이 깨져서 나와요", "아무것도 안 나와요")

enum class TestStep { Ready, Sending, Asked, NotConnected, Aborted }

@Composable
fun ConnectionTestRoute(
    vm: AppViewModel,
    onboarding: Boolean,
    onYes: () -> Unit,
    onSymptom: (Int) -> Unit,
    onRepair: () -> Unit,
    onModel: () -> Unit,
    onBack: () -> Unit,
) {
    val hid by vm.hid.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(TestStep.Ready) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.ensureStarted() } // 설정에서 들어오면 끊겨 있을 수 있다

    ConnectionTestScreen(
        step = step,
        status = { StatusBadge(hid.badge(hosts.isNotEmpty())) },
        onboarding = onboarding,
        model = if (onboarding) model else null,
        onModel = onModel,
        onSend = {
            step = TestStep.Sending
            scope.launch {
                step = when (val r = vm.sendConnectionTest()) {
                    is SendResult.Done -> {
                        if (onboarding) vm.update { it.copy(onboardingDone = true) } // integration §5 SC06
                        TestStep.Asked
                    }
                    is SendResult.Aborted -> TestStep.Aborted
                    SendResult.NotConnected -> TestStep.NotConnected
                }
            }
        },
        onYes = {
            if (!onboarding) scope.launch { snackbar.showSnackbar("잘 연결돼 있어요") }
            onYes()
        },
        onSymptom = onSymptom,
        onRepair = onRepair,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionTestScreen(
    step: TestStep,
    status: @Composable () -> Unit,
    onboarding: Boolean,
    model: ModelState?,
    onModel: () -> Unit,
    onSend: () -> Unit,
    onYes: () -> Unit,
    onSymptom: (Int) -> Unit,
    onRepair: () -> Unit,
    onBack: () -> Unit,
) {
    var sheet by rememberSaveable { mutableStateOf(false) }
    val asked = step == TestStep.Asked
    OnboardingScaffold(
        title = if (asked) "PC 화면에 똑같이\n들어갔나요?" else "글자가 잘 들어가는지\n확인할게요",
        step = if (onboarding) 5 else null,
        onBack = onBack,
        model = model,
        onModelClick = onModel,
        bottom = {
            if (asked) {
                PrimaryButton("네", onYes)
                OutlinedButton({ sheet = true }, Modifier.fillMaxWidth().heightIn(min = Space.button)) { Text("아니요") }
                SecondaryButton("다시 보내기", onSend)
            } else {
                PrimaryButton(if (step == TestStep.Ready) "테스트 보내기" else "다시 보내기", onSend, loading = step == TestStep.Sending)
            }
        },
    ) {
        when (step) {
            TestStep.NotConnected -> {
                WarningCard("PC 블루투스가 켜져 있는지 확인해 주세요. 연결되면 다시 보내 주세요.", title = "아직 연결되지 않았어요") {
                    TextButton(onRepair, contentPadding = PaddingValues(0.dp)) { Text("페어링 다시 하기") }
                }
                status()
            }
            TestStep.Aborted -> WarningCard("다시 보내 주세요. 계속 끊기면 PC와 폰을 가까이 두세요.", title = "전송 중 연결이 끊겼어요")
            else -> Unit
        }
        if (!asked) {
            WarningCard("PC에 한국어 입력기가 있어야 한글이 들어가요")
            StepCard(1, "PC에서 메모장을 열어요", art = Icons.Rounded.EditNote, artLabel = "메모장")
            StepCard(2, "빈 곳을 눌러 커서를 둬요", art = Icons.Rounded.Mouse, artLabel = "|")
            Text("보낼 문구", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Text(AppContainer.CONNECTION_TEST_TEXT, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(Space.xl))
        }
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("어떻게 나왔나요?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
                SYMPTOMS.forEachIndexed { i, s ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(false, role = Role.RadioButton) { sheet = false; onSymptom(i) },
                        leadingContent = { RadioButton(selected = false, onClick = null) },
                        headlineContent = { Text(s, style = MaterialTheme.typography.bodyLarge) },
                    )
                }
            }
        }
    }
}

@Preview(heightDp = 900)
@Composable
private fun TestReadyPreview() = PocketTheme {
    ConnectionTestScreen(TestStep.Ready, {}, true, null, {}, {}, {}, {}, {}, {})
}

@Preview(heightDp = 720)
@Composable
private fun TestAskedPreview() = PocketTheme {
    ConnectionTestScreen(TestStep.Asked, {}, true, null, {}, {}, {}, {}, {}, {})
}
