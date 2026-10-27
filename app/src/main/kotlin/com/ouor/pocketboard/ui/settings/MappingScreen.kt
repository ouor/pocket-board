package com.ouor.pocketboard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.AssignResult
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.ui.AppViewModel
import com.ouor.pocketboard.ui.LocalSnackbar
import com.ouor.pocketboard.ui.components.GestureActionRow
import com.ouor.pocketboard.ui.components.GestureGlyph
import com.ouor.pocketboard.ui.components.PageScaffold
import com.ouor.pocketboard.ui.components.RowKind
import com.ouor.pocketboard.ui.components.SettingGroup
import com.ouor.pocketboard.ui.components.SettingRow
import com.ouor.pocketboard.ui.components.TipCard
import com.ouor.pocketboard.ui.components.delaysSend
import com.ouor.pocketboard.ui.components.gestureNames
import com.ouor.pocketboard.ui.components.gesturesFor
import com.ouor.pocketboard.ui.components.label
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space
import kotlinx.coroutines.launch

// ---------- SC13 제스처 매핑(F11, A3) ----------

private const val SEND_REQUIRED = "보내기에 다른 제스처를 먼저 정해 주세요"

@Composable
fun MappingRoute(vm: AppViewModel, onBack: () -> Unit) {
    val mapping by vm.mapping.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var blocked by remember { mutableStateOf<String?>(null) }
    MappingScreen(
        mapping = mapping,
        emptySendEnter = settings.emptySendEnter,
        hapticsOn = settings.haptics.enabled,
        onAssign = { g, a -> scope.launch { if (vm.assign(g, a) == AssignResult.SendRequired) blocked = SEND_REQUIRED } },
        onEmptySendEnter = { on -> vm.update { it.copy(emptySendEnter = on) } },
        onReset = { scope.launch { vm.resetMapping(); snackbar.showSnackbar("기본값으로 되돌렸어요") } },
        onBack = onBack,
    )
    blocked?.let { InfoDialog(it) { blocked = null } }
}

@Composable
fun MappingScreen(
    mapping: GestureMapping,
    emptySendEnter: Boolean,
    hapticsOn: Boolean,
    onAssign: (Gesture, Action?) -> Unit,
    onEmptySendEnter: (Boolean) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<Action?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val delayed = mapping.actions.keys.any { it.delaysSend }
    val noEnter = !emptySendEnter && mapping.gesturesFor(Action.ENTER).isEmpty()

    PageScaffold("제스처", onBack, actions = {
        Box {
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "더 보기") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("기본값으로 되돌리기") }, onClick = { menu = false; confirmReset = true })
            }
        }
    }) {
        Text("동작마다 제스처를 바꿀 수 있어요. 한 동작에 여러 개도 돼요.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SettingGroup { Action.entries.forEach { a -> GestureActionRow(a, mapping, hapticsOn) { editing = a } } }
            if (delayed) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(horizontal = Space.xs)) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("세 번·네 번 톡을 쓰고 있어서 보내기가 조금 늦어져요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SettingGroup {
            SettingRow(
                "쓴 글씨 없을 때 ${mapping.gestureNames(Action.SEND)} = 엔터", RowKind.Toggle, checked = emptySendEnter,
                supporting = if (noEnter) "엔터를 넣을 방법이 없어요" else "끄면 엔터는 위에서 고른 제스처로만 들어가요",
                warning = noEnter, // A15: 막지 않고 경고만
            ) { onEmptySendEnter(!emptySendEnter) }
        }
        TipCard("엔터를 자주 실수하면 엔터에 네 번 톡을 넣고 위 스위치를 끄세요")
    }

    editing?.let { GestureSheet(it, mapping, onAssign) { editing = null } }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("기본값으로 되돌릴까요?") },
            text = { Text("바꾼 제스처가 모두 처음 상태로 돌아가요.") },
            confirmButton = { TextButton({ confirmReset = false; onReset() }) { Text("되돌리기") } },
            dismissButton = { TextButton({ confirmReset = false }) { Text("취소") } },
        )
    }
}

/** 제스처 선택 시트: C3 Option 변형, 체크박스(여러 개). 체크는 즉시 저장, [완료]는 닫기만. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureSheet(action: Action, mapping: GestureMapping, onAssign: (Gesture, Action?) -> Unit, onDismiss: () -> Unit) {
    var takeOver by remember { mutableStateOf<Pair<Gesture, Action>?>(null) }
    var blocked by remember { mutableStateOf<String?>(null) }
    val sendCount = mapping.gesturesFor(Action.SEND).size
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("${action.label}에 쓸 제스처", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = Space.gutter))
            Text("여러 개 고를 수 있어요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs))
            Gesture.MAPPABLE.forEach { g ->
                val owner = mapping.actions[g]
                val checked = owner == action
                val locked = checked && action == Action.SEND && sendCount == 1
                val note = when {
                    locked -> "보내기는 꼭 제스처가 있어야 해요"
                    owner != null && owner != action -> "${owner.label}에 사용 중"
                    g.delaysSend -> "넣으면 보내기가 조금 늦어져요"
                    else -> null
                }
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                    modifier = Modifier.alpha(if (locked) 0.38f else 1f).toggleable(checked, enabled = !locked, role = Role.Checkbox) {
                        when {
                            checked -> onAssign(g, null)
                            owner == null -> onAssign(g, action)
                            owner == Action.SEND && sendCount == 1 -> blocked = SEND_REQUIRED
                            else -> takeOver = g to owner
                        }
                    },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Checkbox(checked, onCheckedChange = null, enabled = !locked)
                            GestureGlyph(g, 28.dp, description = null)
                        }
                    },
                    headlineContent = { Text(g.label, style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = note?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
                )
            }
            Text(
                "한 손가락 한 번 톡·끌기는 글씨 쓰기 전용이에요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss) { Text("완료") }
            }
        }
    }
    takeOver?.let { (g, owner) ->
        AlertDialog(
            onDismissRequest = { takeOver = null },
            title = { Text("제스처를 옮길까요?") },
            text = { Text("${g.label}는 '${owner.label}'에서 해제돼요.") },
            confirmButton = { TextButton({ takeOver = null; onAssign(g, action) }) { Text("바꾸기") } },
            dismissButton = { TextButton({ takeOver = null }) { Text("취소") } },
        )
    }
    blocked?.let { InfoDialog(it) { blocked = null } }
}

@Composable
private fun InfoDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, text = { Text(text) }, confirmButton = { TextButton(onDismiss) { Text("확인") } })
}

@Preview(heightDp = 1000)
@Composable
private fun MappingPreview() = PocketTheme {
    val m = GestureMapping(GestureMapping.DEFAULT.actions + (Gesture.Tap(1, 4) to Action.ENTER))
    MappingScreen(m, false, true, { _, _ -> }, {}, {}, {})
}
