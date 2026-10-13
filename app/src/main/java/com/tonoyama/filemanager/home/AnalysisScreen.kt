package com.tonoyama.filemanager.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tonoyama.filemanager.local.LocalViewModel
import com.tonoyama.filemanager.media.Category
import com.tonoyama.filemanager.media.IndexedFile
import com.tonoyama.filemanager.ui.ConfirmDialog
import com.tonoyama.filemanager.ui.OtherColor
import com.tonoyama.filemanager.ui.ProgressDialog
import com.tonoyama.filemanager.ui.color
import com.tonoyama.filemanager.util.FileOpener
import com.tonoyama.filemanager.util.Format

private data class Segment(val label: String, val bytes: Long, val color: Color, val category: Category?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    vm: HomeViewModel,
    localVm: LocalViewModel,
    onBack: () -> Unit,
    onCategory: (Category) -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val local by localVm.state.collectAsState()
    val opVersion by localVm.opVersion.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<IndexedFile?>(null) }

    val enteredAtOp = remember { opVersion }
    LaunchedEffect(opVersion) { if (opVersion != enteredAtOp) vm.refresh() }
    LaunchedEffect(Unit) { if (state.summary == null) vm.refresh() }
    LaunchedEffect(local.message) {
        local.message?.let {
            snackbar.showSnackbar(it)
            localVm.consumeMessage()
        }
    }

    val volume = state.volumes.firstOrNull { it.primary }
    val summary = state.summary
    // 本体ストレージ上の内訳（SDカード上のファイルは除く）
    val segments = remember(summary, volume) {
        if (summary == null || volume == null) emptyList()
        else {
            val cats = listOf(Category.IMAGES, Category.VIDEO, Category.AUDIO, Category.DOCUMENTS, Category.APK, Category.ARCHIVES)
            val list = cats.map { Segment(it.label, summary.primaryBytes[it] ?: 0, it.color, it) }
            val other = (volume.used - list.sumOf { it.bytes }).coerceAtLeast(0)
            list + Segment("その他（アプリ・システム等）", other, OtherColor, null)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                title = { Text("ストレージ分析") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (volume != null) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Donut(segments, volume.total, Modifier.size(132.dp))
                            Spacer(Modifier.width(20.dp))
                            Column {
                                Text("メインストレージ", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${Format.size(volume.used)} 使用",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "全体 ${Format.size(volume.total)} ・ 空き ${Format.size(volume.free)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(segments) { seg ->
                        SegmentRow(seg, volume.total, onClick = seg.category?.let { c -> { onCategory(c) } })
                    }
                }
                if (summary != null && summary.largest.isNotEmpty()) {
                    item {
                        Text(
                            "大きいファイル",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                        )
                    }
                    items(summary.largest, key = { it.file.path }) { f ->
                        IndexedFileRow(
                            f = f,
                            selected = false,
                            modifier = Modifier.clickable { FileOpener.open(context, f.file) },
                            trailing = {
                                IconButton(onClick = { confirmDelete = f }) {
                                    Icon(Icons.Filled.DeleteOutline, "削除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    local.operation?.let { op ->
        ProgressDialog(op.title, op.label, op.done, op.total, onCancel = localVm::cancelOperation)
    }
    confirmDelete?.let { f ->
        ConfirmDialog(
            title = "削除",
            text = "「${f.file.name}」（${Format.size(f.size)}）を削除します。元に戻せません。",
            confirmLabel = "削除",
            onDismiss = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                localVm.deleteFiles(listOf(f.file))
            },
        )
    }
}

@Composable
private fun SegmentRow(seg: Segment, total: Long, onClick: (() -> Unit)?) {
    val ratio = if (total > 0) seg.bytes.toFloat() / total else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(seg.color)
            )
            Spacer(Modifier.width(10.dp))
            Text(seg.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${Format.size(seg.bytes)}  ${"%.1f".format(ratio * 100)}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        val track = MaterialTheme.colorScheme.surfaceVariant
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
        ) {
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            drawRoundRect(
                seg.color,
                size = androidx.compose.ui.geometry.Size(size.width * ratio.coerceIn(0f, 1f), size.height),
                cornerRadius = r,
            )
        }
    }
}

/** 内訳のドーナツグラフ（空き容量は薄い灰色） */
@Composable
private fun Donut(segments: List<Segment>, total: Long, modifier: Modifier) {
    val free = MaterialTheme.colorScheme.surfaceVariant
    val gap = MaterialTheme.colorScheme.surface
    val percent = if (total > 0) (segments.sumOf { it.bytes } * 100 / total).toInt() else 0
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.18f
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(free, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (total > 0) {
                var start = -90f
                segments.forEach { seg ->
                    val sweep = 360f * seg.bytes / total
                    if (sweep > 0.3f) {
                        drawArc(seg.color, start, sweep, false, topLeft, arcSize, style = Stroke(stroke))
                        // 区切り線
                        drawArc(gap, start + sweep - 0.6f, 0.6f, false, topLeft, arcSize, style = Stroke(stroke))
                    }
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("$percent%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("使用中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
