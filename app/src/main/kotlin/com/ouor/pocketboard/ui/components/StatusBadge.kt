package com.ouor.pocketboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space
import com.ouor.pocketboard.ui.theme.pocket

/** C1 연결 상태 배지. 색만으로 구분하지 않고 점/아이콘 + 글자(design-system §2.2). */
@Composable
fun StatusBadge(badge: Badge, modifier: Modifier = Modifier, spokenPrefix: String = "연결 상태: ") {
    val p = MaterialTheme.pocket
    val color = when (badge.kind) {
        BadgeKind.Connected -> p.connected
        BadgeKind.Connecting, BadgeKind.Retrying -> p.connecting
        BadgeKind.Problem -> p.problem
        BadgeKind.Idle, BadgeKind.NotSetUp -> p.idle
    }
    Surface(
        color = color.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spokenPrefix + badge.label
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            modifier = Modifier.heightIn(min = 28.dp).padding(horizontal = 10.dp),
        ) {
            if (badge.kind == BadgeKind.Problem) {
                Icon(Icons.Rounded.PriorityHigh, null, tint = color, modifier = Modifier.size(14.dp))
            } else {
                Dot(badge.kind, color)
            }
            Text(badge.label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
private fun Dot(kind: BadgeKind, color: Color) {
    Canvas(Modifier.size(10.dp)) {
        val r = size.minDimension / 2
        when (kind) {
            BadgeKind.Connected -> drawCircle(color, r)
            // 반 점: 정적(회전 애니 없음)
            BadgeKind.Connecting, BadgeKind.Retrying -> {
                drawCircle(color, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                drawArc(color, 90f, 180f, true, Offset.Zero, Size(size.width, size.height))
            }
            else -> drawCircle(color, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Preview
@Composable
private fun StatusBadgePreview() = PocketTheme {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusBadge(Badge(BadgeKind.Connected, "연결됨"))
        StatusBadge(Badge(BadgeKind.Connecting, "연결 중…"))
        StatusBadge(Badge(BadgeKind.Idle, "연결 안 됨"))
        StatusBadge(Badge(BadgeKind.Problem, "다시 페어링 필요"))
    }
}
