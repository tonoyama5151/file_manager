package com.tonoyama.filemanager.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tonoyama.filemanager.local.LocalViewModel
import com.tonoyama.filemanager.media.CatStat
import com.tonoyama.filemanager.media.Category
import com.tonoyama.filemanager.media.VolumeInfo
import com.tonoyama.filemanager.ui.CloudColor
import com.tonoyama.filemanager.ui.DownloadColor
import com.tonoyama.filemanager.ui.color
import com.tonoyama.filemanager.ui.icon
import com.tonoyama.filemanager.util.Format
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    localVm: LocalViewModel,
    onOpenDir: (File) -> Unit,
    onCategory: (Category) -> Unit,
    onAnalysis: () -> Unit,
    onCloud: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val local by localVm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // ホームに戻るたび（アプリ復帰時も含む）に集計し直す
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(local.message) {
        local.message?.let {
            snackbar.showSnackbar(it)
            localVm.consumeMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("ファイルマネージャー", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "再集計") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // ---- ストレージ ----
                state.volumes.forEach { v ->
                    item(key = "vol:" + v.dir.path) {
                        Tile(
                            title = v.label,
                            subtitle = "${Format.size(v.used)} / ${Format.size(v.total)}",
                            onClick = { onOpenDir(v.dir) },
                        ) {
                            Icon(
                                if (v.primary) Icons.Filled.PhoneAndroid else Icons.Filled.SdCard,
                                null,
                                tint = Color(0xFF6B7785),
                                modifier = Modifier.size(38.dp),
                            )
                            UsageBar(v, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                        }
                    }
                }
                item(key = "downloads") {
                    Tile(
                        title = "ダウンロード",
                        subtitle = statText(state.downloads),
                        onClick = { onOpenDir(vm.downloadDir) },
                    ) {
                        Icon(Icons.Filled.FileDownload, null, tint = DownloadColor, modifier = Modifier.size(38.dp))
                    }
                }
                item(key = "analysis") {
                    val primary = state.volumes.firstOrNull { it.primary }
                    val ratio = primary?.let { if (it.total > 0) it.used.toFloat() / it.total else 0f } ?: 0f
                    Tile(
                        title = "ストレージ分析",
                        subtitle = if (primary != null) "${(ratio * 100).toInt()}% 使用中" else "—",
                        onClick = onAnalysis,
                    ) {
                        UsagePie(ratio, Modifier.size(48.dp))
                    }
                }

                // ---- ジャンル ----
                item(key = "spacer", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(4.dp)) }
                Category.entries.forEach { cat ->
                    item(key = "cat:" + cat.name) {
                        Tile(
                            title = cat.label,
                            subtitle = statText(state.summary?.stats?.get(cat)),
                            onClick = { onCategory(cat) },
                        ) {
                            Icon(cat.icon, null, tint = cat.color, modifier = Modifier.size(38.dp))
                        }
                    }
                }
                item(key = "cloud") {
                    Tile(title = "クラウド", subtitle = "pCloud", onClick = onCloud) {
                        Icon(Icons.Filled.Cloud, null, tint = CloudColor, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

private fun statText(stat: CatStat?): String =
    if (stat == null) "集計中…" else "${Format.size(stat.bytes)} (${stat.count})"

@Composable
private fun Tile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
            content = content,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UsageBar(v: VolumeInfo, modifier: Modifier) {
    val ratio = if (v.total > 0) (v.used.toFloat() / v.total).coerceIn(0f, 1f) else 0f
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (ratio > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Canvas(modifier.size(width = 48.dp, height = 5.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(fill, size = Size(size.width * ratio, size.height), cornerRadius = r)
    }
}

/** 使用率の円グラフ */
@Composable
fun UsagePie(ratio: Float, modifier: Modifier) {
    val used = MaterialTheme.colorScheme.primary
    val free = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        drawCircle(free)
        drawArc(
            color = used,
            startAngle = -90f,
            sweepAngle = 360f * ratio.coerceIn(0f, 1f),
            useCenter = true,
            topLeft = Offset.Zero,
            size = size,
        )
        drawCircle(used.copy(alpha = 0.35f), style = Stroke(width = 1.dp.toPx()))
    }
}
