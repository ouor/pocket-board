package com.ouor.pocketboard.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ouor.pocketboard.core.gesture.Action
import com.ouor.pocketboard.core.gesture.Gesture
import com.ouor.pocketboard.core.gesture.GestureMapping
import com.ouor.pocketboard.core.gesture.SwipeDirection
import com.ouor.pocketboard.ui.theme.PocketTheme

/**
 * C2 제스처 글리프: 손가락 수 = 점 개수, 탭 횟수 = 동심원 수, 스와이프 = 화살표.
 * [animate]는 튜토리얼(SC07) 전용 1.2초 루프.
 */
@Composable
fun GestureGlyph(gesture: Gesture, size: Dp = 24.dp, animate: Boolean = false, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.secondaryContainer
    val fg = MaterialTheme.colorScheme.onSecondaryContainer
    val progress = if (animate) {
        val t by rememberInfiniteTransition(label = "glyph").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "p",
        )
        t
    } else 1f
    Canvas(modifier.size(size).semantics { contentDescription = gesture.label }) {
        val d = this.size.minDimension
        drawCircle(bg)
        val stroke = Stroke(width = d * 0.04f, cap = StrokeCap.Round)
        when (gesture) {
            is Gesture.Tap -> {
                dots(gesture.fingers, Offset(center.x, center.y), horizontal = true, d = d, color = fg)
                for (i in 0 until gesture.count) {
                    if (progress >= (i + 1f) / (gesture.count + 1f) || !animate) {
                        drawCircle(fg, radius = d * (0.27f + 0.055f * i), style = stroke)
                    }
                }
            }
            is Gesture.Swipe -> {
                val (ux, uy) = when (gesture.direction) {
                    SwipeDirection.LEFT -> -1f to 0f
                    SwipeDirection.RIGHT -> 1f to 0f
                    SwipeDirection.UP -> 0f to -1f
                    SwipeDirection.DOWN -> 0f to 1f
                }
                val tail = Offset(center.x - ux * d * 0.22f, center.y - uy * d * 0.22f)
                dots(gesture.fingers, tail, horizontal = ux == 0f, d = d, color = fg)
                val len = d * 0.5f * progress
                val start = Offset(center.x - ux * d * 0.08f, center.y - uy * d * 0.08f)
                val end = Offset(start.x + ux * len, start.y + uy * len)
                drawLine(fg, start, end, stroke.width, StrokeCap.Round)
                if (progress > 0.6f) {
                    val h = d * 0.12f
                    drawLine(fg, end, Offset(end.x - ux * h - uy * h, end.y - uy * h - ux * h), stroke.width, StrokeCap.Round)
                    drawLine(fg, end, Offset(end.x - ux * h + uy * h, end.y - uy * h + ux * h), stroke.width, StrokeCap.Round)
                }
            }
        }
    }
}

private fun DrawScope.dots(n: Int, at: Offset, horizontal: Boolean, d: Float, color: androidx.compose.ui.graphics.Color) {
    val gap = d * 0.15f
    for (i in 0 until n) {
        val off = (i - (n - 1) / 2f) * gap
        drawCircle(color, d * 0.055f, if (horizontal) Offset(at.x + off, at.y) else Offset(at.x, at.y + off))
    }
}

/** 햅틱 표기(`·` `˙` `—`)는 같은 폭으로(design-system §3). 진동이 꺼져 있으면 흐리게. */
@Composable
fun HapticNotation(action: Action, hapticsOn: Boolean = true) {
    Text(
        action.haptic.notation,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.alpha(if (hapticsOn) 1f else 0.38f).semantics { contentDescription = "진동 ${action.haptic.notation}" },
    )
}

/**
 * C3 제스처 카드 Compact / Mapping 변형. 한 동작에 제스처 여럿이면 한 행에 이어 쓴다(A3).
 * [onClick]이 있으면 Mapping(오른쪽 ›, 글리프 최대 3개 + "외 n").
 */
@Composable
fun GestureActionRow(action: Action, mapping: GestureMapping, hapticsOn: Boolean, onClick: (() -> Unit)? = null) {
    val gestures = mapping.gesturesFor(action)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                gestures.take(if (onClick != null) 3 else 1).forEach { GestureGlyph(it, 28.dp) }
                if (gestures.isEmpty()) GestureGlyphEmpty()
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(action.label, style = MaterialTheme.typography.titleMedium)
                HapticNotation(action, hapticsOn)
            }
        },
        supportingContent = {
            val more = gestures.size - 3
            Text(
                mapping.gestureNames(action) + if (onClick != null && more > 0) " (외 $more)" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = onClick?.let { { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) } },
    )
}

@Composable
private fun GestureGlyphEmpty() {
    val c = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(28.dp)) { drawCircle(c, style = Stroke(1.dp.toPx())) }
}

/** 홈·연습의 "제스처 한눈에": 동작 없는 것은 숨긴다. */
@Composable
fun GestureSummary(mapping: GestureMapping, hapticsOn: Boolean) {
    Column {
        Action.entries.filter { mapping.gesturesFor(it).isNotEmpty() }
            .forEach { GestureActionRow(it, mapping, hapticsOn) }
    }
}

@Preview
@Composable
private fun GlyphPreview() = PocketTheme {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Gesture.MAPPABLE.forEach { GestureGlyph(it, 40.dp) }
    }
}

@Preview
@Composable
private fun GestureSummaryPreview() = PocketTheme { GestureSummary(GestureMapping.DEFAULT, hapticsOn = true) }
