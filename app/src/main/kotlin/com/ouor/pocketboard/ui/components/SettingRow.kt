package com.ouor.pocketboard.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space

/** C4 SettingRow 변형(design-system §6.2). */
enum class RowKind { Navigate, Toggle, Value, Action, Info, Destructive }

/**
 * M3 ListItem 에 슬롯 규칙만 고정한다. Toggle 은 행 전체가 스위치(접근성 Role.Switch).
 * [warning]이면 보조문을 error 색으로, [progress]면 오른쪽 슬롯이 진행 표시.
 */
@Composable
fun SettingRow(
    title: String,
    kind: RowKind = RowKind.Navigate,
    supporting: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    checked: Boolean = false,
    enabled: Boolean = true,
    progress: Boolean = false,
    warning: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val interactive = enabled && kind != RowKind.Info
    val mod = when {
        !interactive -> Modifier
        kind == RowKind.Toggle -> Modifier.toggleable(checked, role = Role.Switch, onValueChange = { onClick() })
        else -> Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    ListItem(
        modifier = mod.alpha(if (enabled) 1f else 0.38f),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon?.let { { Icon(it, null, tint = scheme.onSurfaceVariant) } },
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = when (kind) {
                    RowKind.Action -> scheme.primary
                    RowKind.Destructive -> scheme.error
                    else -> scheme.onSurface
                },
            )
        },
        supportingContent = supporting?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (warning) scheme.error else scheme.onSurfaceVariant, maxLines = 3) }
        },
        trailingContent = {
            when {
                trailing != null -> trailing()
                progress -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                kind == RowKind.Toggle -> Switch(checked = checked, onCheckedChange = null, enabled = enabled)
                kind == RowKind.Navigate || kind == RowKind.Value || kind == RowKind.Info -> Row(verticalAlignment = Alignment.CenterVertically) {
                    value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
                    if (kind == RowKind.Navigate) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
                }
                else -> Unit
            }
        },
    )
}

/** 수치 조정 행(SC17 임계값). 범위 밖으로 못 나가게 버튼을 막는다. */
@Composable
fun StepperRow(title: String, value: String, supporting: String? = null, canDec: Boolean, canInc: Boolean, onDec: () -> Unit, onInc: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDec, enabled = canDec) { Icon(Icons.Rounded.Remove, "$title 줄이기") }
                Text(value, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onInc, enabled = canInc) { Icon(Icons.Rounded.Add, "$title 늘리기") }
            }
        },
    )
}

/** 토스식 그룹 카드: 헤더(onSurfaceVariant) + surfaceContainerLow 카드. */
@Composable
fun SettingGroup(header: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        header?.let {
            Text(
                it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.xs),
            )
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(vertical = Space.xs), content = content)
        }
    }
}

@Preview
@Composable
private fun SettingRowPreview() = PocketTheme {
    SettingGroup("피드백") {
        SettingRow("진동", RowKind.Toggle, icon = Icons.Rounded.Vibration, checked = true)
        SettingRow("진동 세기", RowKind.Value, value = "중")
        SettingRow("PC 목록", value = "3대")
        SettingRow("인식 모델", supporting = "받지 못했어요 · 다시 시도", warning = true)
        SettingRow("기록 지우기", RowKind.Destructive)
        StepperRow("탭 최대 시간", "250ms", canDec = true, canInc = true, onDec = {}, onInc = {})
    }
}
