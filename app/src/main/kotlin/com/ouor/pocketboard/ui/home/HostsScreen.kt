package com.ouor.pocketboard.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.pocketboard.core.hid.HidState
import com.ouor.pocketboard.core.hid.HostDevice
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.LocalSnackbar
import com.ouor.pocketboard.ui.Pairing
import com.ouor.pocketboard.ui.components.Badge
import com.ouor.pocketboard.ui.components.BadgeKind
import com.ouor.pocketboard.ui.components.PageScaffold
import com.ouor.pocketboard.ui.components.PrimaryButton
import com.ouor.pocketboard.ui.components.SettingGroup
import com.ouor.pocketboard.ui.components.StatusBadge
import com.ouor.pocketboard.ui.components.whenLabel
import com.ouor.pocketboard.ui.components.NOT_WINDOWS_NOTE
import com.ouor.pocketboard.ui.components.mayNotBeWindows
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space
import kotlinx.coroutines.launch

// ---------- SC11 호스트 목록(F23) ----------

@Composable
fun HostsRoute(vm: AppViewModel, go: (Any) -> Unit, onBack: () -> Unit) {
    val hid by vm.hid.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    HostsScreen(
        hid, hosts,
        onConnect = vm::connect,
        onDisconnect = vm::disconnect,
        onRename = vm::rename,
        onForget = {
            vm.forget(it)
            scope.launch { snackbar.showSnackbar("목록에서 지웠어요. PC와 폰의 블루투스 설정에서도 지워 주세요") }
        },
        onAdd = { go(Pairing(onboarding = false)) },
        onBack = onBack,
    )
}

/** 이 호스트의 지금 상태 배지. 지금 다루는 호스트가 아니면 null. */
private fun HidState.badgeFor(host: HostDevice): Badge? = when {
    this is HidState.Connected && this.host.address == host.address -> Badge(BadgeKind.Connected, "연결됨")
    this is HidState.Connecting && this.host.address == host.address -> Badge(BadgeKind.Connecting, "연결 중…")
    this is HidState.Reconnecting && this.host.address == host.address ->
        if (repairLikely) Badge(BadgeKind.Problem, "다시 페어링 필요") else Badge(BadgeKind.Retrying, "다시 연결하는 중")
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    hid: HidState,
    hosts: List<HostDevice>,
    onConnect: (HostDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRename: (HostDevice, String) -> Unit,
    onForget: (HostDevice) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<HostDevice?>(null) }
    var forget by remember { mutableStateOf<HostDevice?>(null) }
    var renaming by remember { mutableStateOf<HostDevice?>(null) }
    PageScaffold("PC 목록", onBack, bottom = { PrimaryButton("새 PC 추가하기", onAdd) }) {
        if (hosts.isEmpty()) {
            Text("아직 연결한 PC가 없어요", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SettingGroup {
                hosts.forEachIndexed { i, host ->
                    val badge = hid.badgeFor(host)
                    val detail = buildList {
                        if (badge?.kind == BadgeKind.Connected) add("지금 연결됨") else host.lastConnectedAt?.let { add(context.whenLabel(it)) }
                        if (i == 0) add("자동 재연결")
                        if (host.mayNotBeWindows) add(NOT_WINDOWS_NOTE) // E16
                    }.joinToString(" · ")
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { selected = host },
                        leadingContent = { Icon(Icons.Rounded.Laptop, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        headlineContent = { Text(host.name, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = detail.takeIf { it.isNotEmpty() }?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
                        trailingContent = {
                            if (badge != null) StatusBadge(badge) else FilledTonalButton({ onConnect(host) }) { Text("연결") }
                        },
                    )
                }
            }
            Text("자동 재연결은 마지막 PC만 해요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    selected?.let { host ->
        val active = hid.badgeFor(host) != null
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = Space.l)) {
                Text(host.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
                if (active) SheetAction(Icons.Rounded.LinkOff, "이 PC에서 연결 끊기") { selected = null; onDisconnect() }
                else SheetAction(Icons.Rounded.Link, "연결") { selected = null; onConnect(host) }
                SheetAction(Icons.Rounded.Edit, "이름 바꾸기") { selected = null; renaming = host }
                SheetAction(Icons.Rounded.Delete, "목록에서 삭제", destructive = true) { selected = null; forget = host }
            }
        }
    }
    renaming?.let { host -> RenameDialog(host, onDismiss = { renaming = null }) { onRename(host, it) } }
    forget?.let { host ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("${host.name}을 지울까요?") },
            text = { Text("앱 목록에서만 지워요. PC와 폰의 블루투스 목록에서도 지워 주세요.") },
            confirmButton = { TextButton({ forget = null; onForget(host) }) { Text("삭제", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ forget = null }) { Text("취소") } },
        )
    }
}

/** F31: 앱 목록의 표시 이름만 바꾼다. 비우면 기기 이름으로 돌아간다(hid.rename). */
@Composable
private fun RenameDialog(host: HostDevice, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(host.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름 바꾸기") },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, label = { Text("PC 이름") },
                supportingText = { Text("비우면 PC가 알려 준 이름으로 돌아가요") },
            )
        },
        confirmButton = { TextButton({ onDismiss(); onSave(text.trim()) }) { Text("저장") } },
        dismissButton = { TextButton(onDismiss) { Text("취소") } },
    )
}

@Composable
fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null, tint = color) },
        headlineContent = { Text(text, color = color, style = MaterialTheme.typography.bodyLarge) },
    )
}

@Preview(heightDp = 640)
@Composable
private fun HostsPreview() = PocketTheme {
    val a = HostDevice("1", "내 노트북", System.currentTimeMillis())
    val b = HostDevice("2", "거실 TV", System.currentTimeMillis() - 86_400_000, kind = com.ouor.pocketboard.core.hid.HostKind.AUDIO_VIDEO)
    HostsScreen(HidState.Connected(a), listOf(a, b), {}, {}, { _, _ -> }, {}, {}, {})
}
