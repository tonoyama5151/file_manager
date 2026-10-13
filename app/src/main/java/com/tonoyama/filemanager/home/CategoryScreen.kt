package com.tonoyama.filemanager.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tonoyama.filemanager.local.ArchiveOps
import com.tonoyama.filemanager.local.LocalViewModel
import com.tonoyama.filemanager.local.SortMode
import com.tonoyama.filemanager.media.Category
import com.tonoyama.filemanager.media.IndexedFile
import com.tonoyama.filemanager.thumb.LocalThumb
import com.tonoyama.filemanager.ui.ConfirmDialog
import com.tonoyama.filemanager.ui.NameInputDialog
import com.tonoyama.filemanager.ui.ProgressDialog
import com.tonoyama.filemanager.util.FileOpener
import com.tonoyama.filemanager.util.Format
import java.io.File

private sealed interface CatDialog {
    data object Delete : CatDialog
    data class Rename(val file: File) : CatDialog
    data class ArchiveChoice(val file: File) : CatDialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CategoryScreen(
    category: Category,
    vm: CategoryViewModel,
    localVm: LocalViewModel,
    onBack: () -> Unit,
    onOpenFolder: (File) -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val local by localVm.state.collectAsState()
    val opVersion by localVm.opVersion.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<CatDialog?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }

    LaunchedEffect(category) { vm.load(category) }
    // 削除・名前変更などの後に一覧を読み直す（メディアDBへの反映を少し待つ）
    val enteredAtOp = remember { opVersion }
    LaunchedEffect(opVersion) {
        if (opVersion != enteredAtOp) {
            kotlinx.coroutines.delay(600)
            vm.reload()
        }
    }
    LaunchedEffect(local.message) {
        local.message?.let {
            snackbar.showSnackbar(it)
            localVm.consumeMessage()
        }
    }

    BackHandler(enabled = state.selectionMode) { vm.clearSelection() }

    val items = if (state.category == category) state.items else emptyList()
    val selected = state.selection.toList()
    val isMedia = category == Category.IMAGES || category == Category.VIDEO

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "選択解除") } },
                    title = { Text("${selected.size}件を選択") },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, "すべて選択") }
                        IconButton(onClick = { FileOpener.share(context, selected) }) { Icon(Icons.Filled.Share, "共有") }
                        if (selected.size == 1) {
                            Box {
                                IconButton(onClick = { moreOpen = true }) { Icon(Icons.Filled.MoreVert, "その他") }
                                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("保存先のフォルダを開く") },
                                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                                        onClick = {
                                            moreOpen = false
                                            val parent = selected[0].parentFile
                                            vm.clearSelection()
                                            if (parent != null) onOpenFolder(parent)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("名前を変更") },
                                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                        onClick = { moreOpen = false; dialog = CatDialog.Rename(selected[0]) },
                                    )
                                    if (ArchiveOps.isArchive(selected[0])) {
                                        DropdownMenuItem(
                                            text = { Text("同じ場所に解凍") },
                                            leadingIcon = { Icon(Icons.Filled.Unarchive, null) },
                                            onClick = { moreOpen = false; vm.clearSelection(); localVm.extract(selected[0]) },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                    title = {
                        Column {
                            Text(category.label, maxLines = 1)
                            if (!state.loading || items.isNotEmpty()) {
                                Text(
                                    "${items.size}件 ・ ${Format.size(state.totalBytes)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::toggleGrid) {
                            if (state.grid) Icon(Icons.AutoMirrored.Filled.ViewList, "リスト表示")
                            else Icon(Icons.Filled.GridView, "グリッド表示")
                        }
                        Box {
                            IconButton(onClick = { sortOpen = true }) { Icon(Icons.Filled.SwapVert, "並べ替え") }
                            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                                listOf(SortMode.DATE, SortMode.NAME, SortMode.SIZE).forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            val arrow = if (state.sortMode == mode) (if (state.ascending) " ↑" else " ↓") else ""
                                            Text(mode.label + arrow)
                                        },
                                        leadingIcon = {
                                            if (state.sortMode == mode) Icon(Icons.Filled.Check, null) else Spacer(Modifier.width(24.dp))
                                        },
                                        onClick = { vm.setSort(mode) },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (state.selectionMode) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        BarAction(Icons.Filled.ContentCopy, "コピー") { localVm.setClipboard(selected, cut = false); vm.clearSelection() }
                        BarAction(Icons.Filled.ContentCut, "移動") { localVm.setClipboard(selected, cut = true); vm.clearSelection() }
                        BarAction(Icons.Filled.Delete, "削除") { dialog = CatDialog.Delete }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            if (items.isEmpty() && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (category == Category.RECENT) "直近${com.tonoyama.filemanager.media.MediaIndex.RECENT_DAYS}日間に更新されたファイルはありません"
                        else "${category.label}は見つかりませんでした",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            val onClick: (IndexedFile) -> Unit = { f ->
                when {
                    state.selectionMode -> vm.toggleSelect(f.file)
                    ArchiveOps.isArchive(f.file) -> dialog = CatDialog.ArchiveChoice(f.file)
                    else -> FileOpener.open(context, f.file)
                }
            }
            if (state.grid) {
                // 画像・動画はギャラリーのように詰めて並べる
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (isMedia) 96.dp else 104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 24.dp),
                ) {
                    items(items.size, key = { items[it].file.path }) { i ->
                        val f = items[i]
                        val isSel = f.file in state.selection
                        val click = Modifier.combinedClickable(
                            onClick = { onClick(f) },
                            onLongClick = { vm.toggleSelect(f.file) },
                        )
                        if (isMedia) {
                            LocalThumb(
                                file = f.file,
                                isDirectory = false,
                                modified = f.modified,
                                selected = isSel,
                                modifier = click
                                    .padding(2.dp)
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                                iconSize = 32.dp,
                                thumbSize = 128.dp,
                                corner = 4.dp,
                            )
                        } else {
                            Column(click.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                LocalThumb(
                                    file = f.file,
                                    isDirectory = false,
                                    modified = f.modified,
                                    selected = isSel,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    iconSize = 44.dp,
                                    thumbSize = 128.dp,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    f.file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(items, key = { it.file.path }) { f ->
                        IndexedFileRow(
                            f = f,
                            selected = f.file in state.selection,
                            modifier = Modifier.combinedClickable(
                                onClick = { onClick(f) },
                                onLongClick = { vm.toggleSelect(f.file) },
                            ),
                        )
                    }
                }
            }
        }
    }

    local.operation?.let { op ->
        ProgressDialog(op.title, op.label, op.done, op.total, onCancel = localVm::cancelOperation)
    }

    when (val d = dialog) {
        null -> Unit
        CatDialog.Delete -> ConfirmDialog(
            title = "削除",
            text = "${selected.size}件のファイルを削除します。元に戻せません。",
            confirmLabel = "削除",
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                vm.clearSelection()
                localVm.deleteFiles(selected)
            },
        )
        is CatDialog.Rename -> NameInputDialog(
            title = "名前を変更",
            initial = d.file.name,
            confirmLabel = "変更",
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                vm.clearSelection()
                localVm.rename(d.file, it)
            },
        )
        is CatDialog.ArchiveChoice -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(d.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("このアーカイブをどうしますか？\n解凍すると同じフォルダに展開されます。") },
            confirmButton = { TextButton(onClick = { dialog = null; localVm.extract(d.file) }) { Text("解凍") } },
            dismissButton = {
                TextButton(onClick = { dialog = null; FileOpener.open(context, d.file) }) { Text("他のアプリで開く") }
            },
        )
    }
}

/** ジャンル一覧・大きいファイル一覧で使う行 */
@Composable
fun IndexedFileRow(
    f: IndexedFile,
    selected: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalThumb(f.file, false, f.modified, selected, Modifier.size(48.dp), thumbSize = 96.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${Format.size(f.size)} ・ ${Format.date(f.modified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    f.file.parentFile?.name ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
