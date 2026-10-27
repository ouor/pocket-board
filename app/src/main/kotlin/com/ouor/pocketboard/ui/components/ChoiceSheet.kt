package com.ouor.pocketboard.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.ouor.pocketboard.ui.theme.Space

/** 값 선택 바텀시트(RadioButton). 고르면 바로 닫힌다(design-system §6.1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceSheet(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Space.l)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
            options.forEach { (value, label) ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(value == selected, role = Role.RadioButton) { onSelect(value); onDismiss() },
                    leadingContent = { RadioButton(selected = value == selected, onClick = null) },
                    headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                )
            }
        }
    }
}
