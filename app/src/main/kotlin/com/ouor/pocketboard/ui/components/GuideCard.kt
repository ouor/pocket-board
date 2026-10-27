package com.ouor.pocketboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ouor.pocketboard.ui.theme.PocketTheme
import com.ouor.pocketboard.ui.theme.Space

/** C5 안내 카드. Tip/Warning 은 아이콘 + 글, Step 은 그림 자리 + 번호 + 제목 + 설명. */
@Composable
fun TipCard(text: String, icon: ImageVector = Icons.Rounded.Lightbulb, title: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) =
    NoteCard(text, icon, title, warning = false, modifier, action)

@Composable
fun WarningCard(text: String, title: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) =
    NoteCard(text, Icons.Rounded.PriorityHigh, title, warning = true, modifier, action)

@Composable
private fun NoteCard(text: String, icon: ImageVector, title: String?, warning: Boolean, modifier: Modifier, action: (@Composable () -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val fg = if (warning) scheme.onErrorContainer else scheme.onSurface
    Surface(
        color = if (warning) scheme.errorContainer else scheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(icon, null, tint = if (warning) scheme.error else scheme.secondary, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.weight(1f)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = fg) }
                Text(text, style = MaterialTheme.typography.bodyLarge, color = if (title == null) fg else fg.copy(alpha = 0.85f))
                action?.invoke()
            }
        }
    }
}

/**
 * Step: 그림 + 번호 원 + 제목 + 설명. 그림은 단순화한 일러스트 자리(16:10)이며
 * 실제 일러스트가 오기 전까지 아이콘과 [art] 라벨로 그린다.
 */
@Composable
fun StepCard(number: Int, title: String, text: String? = null, art: ImageVector? = null, artLabel: String? = null, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (art != null) {
                Surface(
                    color = scheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 7f),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(art, null, tint = scheme.primary, modifier = Modifier.size(40.dp))
                        artLabel?.let {
                            Surface(
                                shape = MaterialTheme.shapes.small, color = scheme.surfaceContainer,
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, scheme.primary),
                                modifier = Modifier.padding(top = Space.s),
                            ) { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Space.m, vertical = Space.xs)) }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                NumberDot(number)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
fun NumberDot(n: Int) {
    Box(contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, modifier = Modifier.size(28.dp)) {}
        Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Preview
@Composable
private fun GuideCardPreview() = PocketTheme {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WarningCard("PC에 한국어 입력기가 있어야 한글이 들어가요")
        TipCard("PC가 꺼져 있거나 블루투스가 꺼져 있으면 연결이 안 돼요")
        StepCard(1, "Windows 설정을 열어요", "시작 → 설정", Icons.Rounded.Settings, "설정")
    }
}
