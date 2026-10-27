package com.ouor.pocketboard.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.ConnectionTest
import com.ouor.pocketboard.ui.Pairing
import com.ouor.pocketboard.ui.components.PageScaffold
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.SettingGroup
import com.ouor.pocketboard.ui.components.StepCard
import com.ouor.pocketboard.ui.components.TipCard
import com.ouor.pocketboard.ui.components.gestureNames
import com.ouor.pocketboard.ui.onboarding.SYMPTOMS
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space

// ---------- SC14 문제 해결 ----------

@Composable
fun TroubleshootRoute(vm: AppViewModel, symptom: Int, go: (Any) -> Unit, onBack: () -> Unit) {
    val mapping by vm.mapping.collectAsStateWithLifecycle()
    TroubleshootScreen(
        mapping, symptom,
        onRetest = { go(ConnectionTest(onboarding = false)) },
        onRepair = { go(Pairing(onboarding = false, repair = true)) },
        onBack = onBack,
    )
}

@Composable
fun TroubleshootScreen(mapping: GestureMapping, symptom: Int, onRetest: () -> Unit, onRepair: () -> Unit, onBack: () -> Unit) {
    var open by rememberSaveable { mutableIntStateOf(symptom) }
    val resync = "${mapping.gestureNames(Action.RESYNC)}(한/영 맞추기)"
    PageScaffold("연결 문제 해결", onBack, bottom = { PrimaryButton("다시 테스트하기", onRetest) }) {
        Text("어떤 증상인가요?", style = MaterialTheme.typography.headlineSmall)
        SettingGroup {
            SYMPTOMS.forEachIndexed { i, title ->
                val expanded = open == i
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(role = Role.Button) { open = if (expanded) -1 else i }
                        .semantics { stateDescription = if (expanded) "펼쳐짐" else "접힘" },
                    headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
                    trailingContent = { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) },
                )
                AnimatedVisibility(expanded) {
                    Column(Modifier.padding(horizontal = Space.l).padding(bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        when (i) {
                            0 -> {
                                StepCard(1, "PC에 한국어 입력기가 있는지 확인해요", "설정 → 시간 및 언어 → 언어 및 지역에 한국어가 있어야 해요")
                                StepCard(2, "작업 표시줄의 한/영 표시를 확인해요", "'A'로 보이면 PC가 영어 상태예요")
                                StepCard(3, "그래도 안 되면 $resync", "폰이 아는 한/영 상태를 PC와 맞춰요")
                            }
                            1 -> {
                                StepCard(1, "$resync 을 해요", "한/영 상태가 어긋나면 글자가 깨져요")
                                StepCard(2, "PC 입력기가 Microsoft 한국어 입력기인지 확인해요", "다른 입력기는 결과가 다를 수 있어요")
                            }
                            else -> {
                                StepCard(1, "PC에서 글자를 넣을 곳을 눌러 커서를 둬요")
                                StepCard(2, "PC 블루투스가 켜져 있는지 확인해요", "PC 블루투스 목록에서 폰이 '연결됨'인지 봐요")
                                StepCard(3, "그래도 안 되면 다시 페어링해요")
                                FilledTonalButton(onRepair) { Text("페어링 다시 하기") }
                            }
                        }
                    }
                }
            }
        }
        TipCard("PC가 꺼져 있거나 블루투스가 꺼져 있으면 연결이 안 돼요")
        Text("Windows PC 기준 안내예요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(heightDp = 1000)
@Composable
private fun TroubleshootPreview() = PocketTheme { TroubleshootScreen(GestureMapping.DEFAULT, 0, {}, {}, {}) }
