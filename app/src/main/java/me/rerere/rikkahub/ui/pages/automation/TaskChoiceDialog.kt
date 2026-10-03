package me.rerere.rikkahub.ui.pages.automation

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun taskModeText(mode: String) = when (mode) { "FOLLOW_UP" -> "继续会话"; "REGENERATE" -> "重新生成"; else -> "新建会话" }
@Composable
internal fun TaskChoiceDialog(title: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            if (choices.isEmpty()) item { Text("暂无可选项") }
            items(choices, key = { it.first }) { (id, label) -> TextButton(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth()) { Text(label) } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
