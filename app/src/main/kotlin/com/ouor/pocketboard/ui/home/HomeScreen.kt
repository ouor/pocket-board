package com.ouor.pocketboard.ui.home

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.Hosts
import com.ouor.pocketboard.ui.Mapping
import com.ouor.pocketboard.ui.Model
import com.ouor.pocketboard.ui.Pairing
import com.ouor.pocketboard.ui.Permission
import com.ouor.pocketboard.ui.Settings
import com.ouor.pocketboard.ui.Troubleshoot
import com.ouor.pocketboard.ui.Writing
import com.ouor.pocketboard.ui.components.GestureSummary
import com.ouor.pocketboard.ui.components.PageScaffold
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.RowKind
import com.ouor.pocketboard.ui.components.SettingGroup
import com.ouor.pocketboard.ui.components.SettingRow
import com.ouor.pocketboard.ui.components.StatusBadge
import com.ouor.pocketboard.ui.components.WarningCard
import com.ouor.pocketboard.ui.components.NOT_WINDOWS_NOTE
import com.ouor.pocketboard.ui.components.badge
import com.ouor.pocketboard.ui.components.mayNotBeWindows
import com.ouor.pocketboard.ui.components.progressLabel
import com.ouor.pocketboard.ui.components.reason
import com.ouor.pocketboard.ui.components.whenLabel
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space

// ---------- SC10 홈(허브) ----------

@Composable
fun HomeRoute(vm: AppViewModel, go: (Any) -> Unit) {
    val hid by vm.hid.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val mapping by vm.mapping.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    HomeScreen(
        hid = hid, hosts = hosts, model = model, mapping = mapping, hapticsOn = settings.haptics.enabled,
        onPrimary = { action ->
            when (action) {
                HomeAction.Write -> go(Writing)
                HomeAction.AddHost -> go(Pairing(onboarding = false))
                HomeAction.Repair -> go(Pairing(onboarding = false, repair = true))
                HomeAction.EnableBluetooth -> enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                HomeAction.Retry -> vm.ensureStarted()
                HomeAction.Permission -> go(Permission(onboarding = false))
                HomeAction.None -> Unit
            }
        },
        onDisconnect = vm::disconnect,
        onTroubleshoot = { go(Troubleshoot()) },
        onModel = { go(Model(onboarding = false)) },
        onHosts = { go(Hosts) },
        onMapping = { go(Mapping) },
        onSettings = { go(Settings) },
        onHaptics = { on -> vm.update { it.copy(haptics = it.haptics.copy(enabled = on)) } },
    )
}

enum class HomeAction { Write, AddHost, Repair, EnableBluetooth, Retry, Permission, None }

/** HostHeroCard 주 버튼(screens SC10 상태 표). */
private fun primaryFor(hid: HidState, hasHosts: Boolean): Pair<HomeAction, String> = when (hid) {
    is HidState.Connected, is HidState.Connecting, HidState.Registering -> HomeAction.Write to "쓰기 시작"
    is HidState.Reconnecting -> if (hid.repairLikely) HomeAction.Repair to "다시 페어링하기" else HomeAction.Write to "쓰기 시작"
    HidState.BluetoothOff -> HomeAction.EnableBluetooth to "블루투스 켜기"
    is HidState.Unavailable -> when (hid.why) {
        HidState.Why.NO_PERMISSION -> HomeAction.Permission to "권한 허용하기"
        HidState.Why.NO_HID_SUPPORT -> HomeAction.None to ""
        else -> HomeAction.Retry to "다시 시도"
    }
    HidState.Ready, HidState.Stopped -> if (hasHosts) HomeAction.Write to "연결하고 쓰기" else HomeAction.AddHost to "새 PC 추가하기"
}

@Composable
fun HomeScreen(
    hid: HidState,
    hosts: List<HostDevice>,
    model: ModelState,
    mapping: GestureMapping,
    hapticsOn: Boolean,
    onPrimary: (HomeAction) -> Unit,
    onDisconnect: () -> Unit,
    onTroubleshoot: () -> Unit,
    onModel: () -> Unit,
    onHosts: () -> Unit,
    onMapping: () -> Unit,
    onSettings: () -> Unit,
    onHaptics: (Boolean) -> Unit,
) {
    PageScaffold(
        title = "pocketboard",
        onBack = null,
        actions = { IconButton(onSettings) { Icon(Icons.Rounded.Settings, "설정") } },
    ) {
        HostHeroCard(hid, hosts, onPrimary, onDisconnect, onTroubleshoot)
        when (model) {
            is ModelState.Failed -> WarningCard("글씨를 인식하려면 모델을 한 번 받아야 해요", title = "인식 모델이 필요해요") {
                TextButton(onModel, contentPadding = PaddingValues(0.dp)) { Text("받기") }
            }
            ModelState.Unavailable -> WarningCard("Google Play 서비스가 없어 글씨 인식을 쓸 수 없어요")
            is ModelState.Downloading -> SettingGroup { SettingRow(model.progressLabel()!!, RowKind.Info, progress = true) }
            else -> Unit
        }
        SettingGroup {
            SettingRow("PC 목록", icon = Icons.Rounded.Laptop, value = if (hosts.isEmpty()) "없음" else "${hosts.size}대", onClick = onHosts)
            SettingRow("제스처", icon = Icons.Rounded.Gesture, supporting = "동작마다 제스처를 바꿔요", onClick = onMapping)
            SettingRow("진동", RowKind.Toggle, icon = Icons.Rounded.Vibration, checked = hapticsOn) { onHaptics(!hapticsOn) }
        }
        SettingGroup("제스처 한눈에") { GestureSummary(mapping, hapticsOn) }
    }
}

@Composable
private fun HostHeroCard(hid: HidState, hosts: List<HostDevice>, onPrimary: (HomeAction) -> Unit, onDisconnect: () -> Unit, onTroubleshoot: () -> Unit) {
    val context = LocalContext.current
    val current = when (hid) {
        is HidState.Connected -> hid.host
        is HidState.Connecting -> hid.host
        is HidState.Reconnecting -> hid.host
        else -> null
    }
    val host = current ?: hosts.firstOrNull()
    var menu by remember { mutableStateOf(false) }
    val (action, label) = primaryFor(hid, hosts.isNotEmpty())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (hid is HidState.Connected) "지금 연결된 PC" else "마지막 PC",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (current != null) {
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "더 보기") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("연결 끊기") }, onClick = { menu = false; onDisconnect() })
                        }
                    }
                }
            }
            Text(host?.name ?: "아직 연결한 PC가 없어요", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            StatusBadge(hid.badge(hosts.isNotEmpty()))
            val detail = hid.reason() ?: host?.lastConnectedAt?.takeIf { current == null }?.let { "마지막 연결 ${context.whenLabel(it)}" }
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (host?.mayNotBeWindows == true) Text(NOT_WINDOWS_NOTE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != HomeAction.None) PrimaryButton(label, { onPrimary(action) }, Modifier.padding(top = Space.s))
            if (hid is HidState.Reconnecting && !hid.repairLikely) {
                TextButton(onTroubleshoot, Modifier.fillMaxWidth()) { Text("연결 문제 해결") }
            }
        }
    }
}

private val previewHost = HostDevice("00:11", "내 노트북", System.currentTimeMillis())

@Preview(heightDp = 900)
@Composable
private fun HomeConnectedPreview() = PocketTheme {
    HomeScreen(HidState.Connected(previewHost), listOf(previewHost), ModelState.Ready, GestureMapping.DEFAULT, true, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(heightDp = 900)
@Composable
private fun HomeRetryingPreview() = PocketTheme {
    HomeScreen(HidState.Reconnecting(previewHost, 3, false), listOf(previewHost), ModelState.Failed(ModelState.Reason.NETWORK), GestureMapping.DEFAULT, false, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(heightDp = 900)
@Composable
private fun HomeEmptyPreview() = PocketTheme {
    HomeScreen(HidState.Stopped, emptyList(), ModelState.Downloading(1, 2), GestureMapping.DEFAULT, true, {}, {}, {}, {}, {}, {}, {}, {})
}
