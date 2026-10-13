package com.tonoyama.filemanager.local

import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tonoyama.filemanager.ui.ConfirmDialog
import com.tonoyama.filemanager.thumb.LocalThumb
import com.tonoyama.filemanager.thumb.LocalThumbSmall
import com.tonoyama.filemanager.ui.NameInputDialog
import com.tonoyama.filemanager.ui.ProgressDialog
import com.tonoyama.filemanager.util.FileOpener
import com.tonoyama.filemanager.util.Format
import java.io.File

/** 画面上で開いているダイアログ */
private sealed interface LocalDialog {
    data object NewFolder : LocalDialog
    data class Rename(val file: File) : LocalDialog
    data object Delete : LocalDialog
    data object Compress : LocalDialog
    data class ArchiveChoice(val file: File) : LocalDialog
    data class Details(val file: File) : LocalDialog
}

/** 「全ファイルへのアクセス」が許可されるまで案内画面を出し、許可後に content を表示する */
@Composable
fun StoragePermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(StoragePermission.granted(context)) }

    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = StoragePermission.granted(context)
    }

    // 設定画面から戻ってきたときに権限を確認し直す
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = StoragePermission.granted(context)
    }

    if (!hasPermission) {
        PermissionRequest(onRequest = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    context.startActivity(StoragePermission.settingsIntent(context))
                } catch (e: ActivityNotFoundException) {
                    context.startActivity(StoragePermission.fallbackSettingsIntent())
                }
            } else {
                legacyLauncher.launch(StoragePermission.legacyPermissions)
            }
        })
    } else {
        content()
    }
}

/** フォルダ画面。ストレージの最上位で「戻る」と onBack（ホームへ） */
@Composable
fun LocalScreen(vm: LocalViewModel, onBack: () -> Unit) {
    FileBrowser(vm, onBack)
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("ファイルへのアクセスを許可してください", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "端末内のファイルを閲覧・整理するために「全ファイルへのアクセス」が必要です。" +
                "設定画面でこのアプリをオンにして戻ってください。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("設定を開く") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FileBrowser(vm: LocalViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<LocalDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var storageMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    BackHandler(enabled = state.selectionMode) { vm.clearSelection() }
    BackHandler(enabled = !state.selectionMode && !state.atRoot) {
        vm.goUp()
    }

    val selectedFiles = state.selection.toList()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "選択解除") }
                    },
                    title = { Text("${state.selection.size}件を選択") },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, "すべて選択") }
                        IconButton(onClick = { FileOpener.share(context, selectedFiles) }) {
                            Icon(Icons.Filled.Share, "共有")
                        }
                        Box {
                            IconButton(onClick = { moreOpen = true }) { Icon(Icons.Filled.MoreVert, "その他") }
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                if (selectedFiles.size == 1) {
                                    DropdownMenuItem(
                                        text = { Text("名前を変更") },
                                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                        onClick = { moreOpen = false; dialog = LocalDialog.Rename(selectedFiles[0]) },
                                    )
                                    if (ArchiveOps.isArchive(selectedFiles[0])) {
                                        DropdownMenuItem(
                                            text = { Text("ここに解凍") },
                                            leadingIcon = { Icon(Icons.Filled.Unarchive, null) },
                                            onClick = { moreOpen = false; vm.extract(selectedFiles[0]) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("詳細") },
                                        leadingIcon = { Icon(Icons.Filled.Info, null) },
                                        onClick = { moreOpen = false; dialog = LocalDialog.Details(selectedFiles[0]) },
                                    )
                                }
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { if (!vm.goUp()) onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
                        }
                    },
                    title = {
                        val dir = state.currentDir
                        val root = state.currentRoot
                        Text(
                            text = if (dir != null && root != null && dir.path == root.dir.path) root.label else dir?.name ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        IconButton(onClick = vm::toggleGrid) {
                            if (state.grid) Icon(Icons.AutoMirrored.Filled.ViewList, "リスト表示")
                            else Icon(Icons.Filled.GridView, "グリッド表示")
                        }
                        if (state.roots.size > 1) {
                            Box {
                                IconButton(onClick = { storageMenuOpen = true }) { Icon(Icons.Filled.SdCard, "ストレージ") }
                                DropdownMenu(expanded = storageMenuOpen, onDismissRequest = { storageMenuOpen = false }) {
                                    state.roots.forEach { root ->
                                        DropdownMenuItem(
                                            text = { Text(root.label) },
                                            leadingIcon = {
                                                Icon(if (root == state.roots.first()) Icons.Filled.PhoneAndroid else Icons.Filled.SdCard, null)
                                            },
                                            onClick = { storageMenuOpen = false; vm.open(root.dir) },
                                        )
                                    }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.SwapVert, "並べ替え") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            val arrow = if (state.sortMode == mode) (if (state.ascending) " ↑" else " ↓") else ""
                                            Text(mode.label + arrow)
                                        },
                                        leadingIcon = {
                                            if (state.sortMode == mode) Icon(Icons.Filled.Check, null)
                                            else Spacer(Modifier.width(24.dp))
                                        },
                                        onClick = { vm.setSort(mode) },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (state.showHidden) "隠しファイルを隠す" else "隠しファイルを表示") },
                                    leadingIcon = {
                                        Icon(if (state.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                                    },
                                    onClick = { menuOpen = false; vm.toggleHidden() },
                                )
                                DropdownMenuItem(
                                    text = { Text("再読み込み") },
                                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                    onClick = { menuOpen = false; vm.refresh() },
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            when {
                state.selectionMode -> SelectionBar(
                    onCopy = { vm.copySelection(cut = false) },
                    onCut = { vm.copySelection(cut = true) },
                    onCompress = { dialog = LocalDialog.Compress },
                    onDelete = { dialog = LocalDialog.Delete },
                )
                state.clipboard != null -> PasteBar(
                    count = state.clipboard!!.files.size,
                    isCut = state.clipboard!!.isCut,
                    onPaste = vm::paste,
                    onCancel = vm::clearClipboard,
                )
            }
        },
        floatingActionButton = {
            if (!state.selectionMode && state.clipboard == null) {
                FloatingActionButton(onClick = { dialog = LocalDialog.NewFolder }) {
                    Icon(Icons.Filled.CreateNewFolder, "新しいフォルダ")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Breadcrumbs(state, onOpen = vm::open)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            if (state.items.isEmpty() && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("空のフォルダです", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val onClickItem: (FileItem) -> Unit = { item ->
                    when {
                        state.selectionMode -> vm.toggleSelect(item.file)
                        item.isDirectory -> vm.open(item.file)
                        ArchiveOps.isArchive(item.file) -> dialog = LocalDialog.ArchiveChoice(item.file)
                        else -> FileOpener.open(context, item.file)
                    }
                }
                if (state.grid) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 96.dp),
                    ) {
                        gridItems(state.items, key = { it.file.path }) { item ->
                            FileGridCell(
                                item = item,
                                selected = item.file in state.selection,
                                modifier = Modifier.combinedClickable(
                                    onClick = { onClickItem(item) },
                                    onLongClick = { vm.toggleSelect(item.file) },
                                ),
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        items(state.items, key = { it.file.path }) { item ->
                            FileRow(
                                item = item,
                                selected = item.file in state.selection,
                                modifier = Modifier.combinedClickable(
                                    onClick = { onClickItem(item) },
                                    onLongClick = { vm.toggleSelect(item.file) },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- ダイアログ ----------
    state.operation?.let { op ->
        ProgressDialog(op.title, op.label, op.done, op.total, onCancel = vm::cancelOperation)
    }

    when (val d = dialog) {
        null -> Unit
        LocalDialog.NewFolder -> NameInputDialog(
            title = "新しいフォルダ",
            initial = "新しいフォルダ",
            confirmLabel = "作成",
            selectBaseName = false,
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.createFolder(it) },
        )
        is LocalDialog.Rename -> NameInputDialog(
            title = "名前を変更",
            initial = d.file.name,
            confirmLabel = "変更",
            selectBaseName = !d.file.isDirectory,
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.rename(d.file, it) },
        )
        LocalDialog.Delete -> ConfirmDialog(
            title = "削除",
            text = "${state.selection.size}件を削除します。フォルダは中身ごと削除され、元に戻せません。",
            confirmLabel = "削除",
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; vm.deleteSelection() },
        )
        LocalDialog.Compress -> {
            val first = selectedFiles.firstOrNull()
            val suggested = when {
                selectedFiles.size == 1 && first != null ->
                    if (first.isDirectory) first.name else first.name.substringBeforeLast('.', first.name)
                else -> state.currentDir?.name ?: "アーカイブ"
            }
            NameInputDialog(
                title = "zipに圧縮",
                initial = "$suggested.zip",
                confirmLabel = "圧縮",
                onDismiss = { dialog = null },
                onConfirm = { dialog = null; vm.compressSelection(it) },
            )
        }
        is LocalDialog.ArchiveChoice -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(d.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("このアーカイブをどうしますか？") },
            confirmButton = {
                TextButton(onClick = { dialog = null; vm.extract(d.file) }) { Text("ここに解凍") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null; FileOpener.open(context, d.file) }) { Text("他のアプリで開く") }
            },
        )
        is LocalDialog.Details -> DetailsDialog(d.file, onDismiss = { dialog = null })
    }
}

@Composable
private fun Breadcrumbs(state: LocalUiState, onOpen: (File) -> Unit) {
    val dir = state.currentDir ?: return
    val root = state.currentRoot ?: return
    val chain = mutableListOf<File>()
    var f: File? = dir
    while (f != null && f.path.length >= root.dir.path.length) {
        chain.add(0, f)
        if (f.path == root.dir.path) break
        f = f.parentFile
    }
    val scroll = rememberScrollState()
    LaunchedEffect(dir.path) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chain.forEachIndexed { i, item ->
            if (i > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val isLast = i == chain.lastIndex
            AssistChip(
                onClick = { if (!isLast) onOpen(item) },
                label = {
                    Text(
                        if (i == 0) root.label else item.name,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

@Composable
private fun FileRow(item: FileItem, selected: Boolean, modifier: Modifier = Modifier) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalThumbSmall(item.file, item.isDirectory, item.modified, selected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                val sub = if (item.isDirectory) "${item.childCount}項目" else Format.size(item.size)
                Text(
                    "$sub ・ ${Format.date(item.modified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileGridCell(item: FileItem, selected: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LocalThumb(
            file = item.file,
            isDirectory = item.isDirectory,
            modified = item.modified,
            selected = selected,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            iconSize = 44.dp,
            thumbSize = 128.dp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (item.isDirectory) "${item.childCount}項目" else Format.size(item.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectionBar(onCopy: () -> Unit, onCut: () -> Unit, onCompress: () -> Unit, onDelete: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarAction(Icons.Filled.ContentCopy, "コピー", onCopy)
            BarAction(Icons.Filled.ContentCut, "移動", onCut)
            BarAction(Icons.Filled.Archive, "圧縮", onCompress)
            BarAction(Icons.Filled.Delete, "削除", onDelete)
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

@Composable
private fun PasteBar(count: Int, isCut: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${count}件を${if (isCut) "移動" else "コピー"}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onCancel) { Text("キャンセル") }
            Button(onClick = onPaste) {
                Icon(Icons.Filled.ContentPaste, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("ここに貼り付け")
            }
        }
    }
}

@Composable
private fun DetailsDialog(file: File, onDismiss: () -> Unit) {
    var size by remember { mutableStateOf<Long?>(null) }
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(file) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (file.isDirectory) {
                var total = 0L
                var n = 0
                file.walkTopDown().forEach { if (it.isFile) { total += it.length(); n++ } }
                count = n
                size = total
            } else {
                size = file.length()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("詳細") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("名前: ${file.name}")
                Text("場所: ${file.parent ?: ""}")
                Text("サイズ: " + (size?.let { Format.size(it) + if (file.isDirectory) "（${count}ファイル）" else "" } ?: "計算中…"))
                Text("更新日時: ${Format.date(file.lastModified())}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
