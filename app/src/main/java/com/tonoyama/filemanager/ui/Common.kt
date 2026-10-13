package com.tonoyama.filemanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 拡張子から種類を判定してアイコンと色を返す */
fun fileVisual(name: String, isDirectory: Boolean): Pair<ImageVector, Color> {
    if (isDirectory) return Icons.Filled.Folder to Color(0xFF3D7BD6)
    val lower = name.lowercase()
    val ext = lower.substringAfterLast('.', "")
    return when {
        ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "dng") ->
            Icons.Filled.Image to Color(0xFF2E9D6A)
        ext in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts") ->
            Icons.Filled.Movie to Color(0xFFD9534F)
        ext in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma") ->
            Icons.Filled.MusicNote to Color(0xFF8E5BD0)
        ext in setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "txz", "tbz2") ->
            Icons.Filled.FolderZip to Color(0xFFC98A1B)
        ext == "pdf" -> Icons.Filled.PictureAsPdf to Color(0xFFE0533D)
        ext == "apk" -> Icons.Filled.Android to Color(0xFF3DA35D)
        ext in setOf("txt", "md", "log", "csv", "json", "xml", "html", "doc", "docx", "xls", "xlsx", "ppt", "pptx") ->
            Icons.Filled.Description to Color(0xFF4A7FB5)
        else -> Icons.Filled.InsertDriveFile to Color(0xFF7A8594)
    }
}

@Composable
fun FileIcon(name: String, isDirectory: Boolean, selected: Boolean = false) {
    val (icon, tint) = fileVisual(name, isDirectory)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else tint.copy(alpha = 0.14f),
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (selected) Icons.Filled.Check else icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else tint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 名前入力ダイアログ（拡張子の手前までを選択した状態で開く） */
@Composable
fun NameInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    selectBaseName: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val dot = initial.lastIndexOf('.')
    val end = if (selectBaseName && dot > 0) dot else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.text.isNotBlank(),
                onClick = { onConfirm(value.text) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** 処理中ダイアログ（進捗バー・中止ボタン付き） */
@Composable
fun ProgressDialog(title: String, label: String, done: Int, total: Int, onCancel: (() -> Unit)?) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = label.ifEmpty { "準備中…" },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$done / $total",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (done > 0) {
                        Text("$done 件", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (onCancel != null) TextButton(onClick = onCancel) { Text("中止") }
        },
    )
}
