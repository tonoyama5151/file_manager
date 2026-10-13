package com.tonoyama.filemanager.local

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val childCount: Int,
)

data class StorageRoot(val label: String, val dir: File)

enum class SortMode(val label: String) { NAME("名前"), DATE("更新日時"), SIZE("サイズ"), TYPE("種類") }

data class Clipboard(val files: List<File>, val isCut: Boolean)

data class Operation(val title: String, val label: String = "", val done: Int = 0, val total: Int = 0)

data class LocalUiState(
    val roots: List<StorageRoot> = emptyList(),
    val currentDir: File? = null,
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val selection: Set<File> = emptySet(),
    val showHidden: Boolean = false,
    val sortMode: SortMode = SortMode.NAME,
    val ascending: Boolean = true,
    val clipboard: Clipboard? = null,
    val operation: Operation? = null,
    val message: String? = null,
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val currentRoot: StorageRoot?
        get() = currentDir?.let { dir ->
            roots.filter { dir.path == it.dir.path || dir.path.startsWith(it.dir.path + "/") }
                .maxByOrNull { it.dir.path.length }
        }
}

class LocalViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("local", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        LocalUiState(
            showHidden = prefs.getBoolean("showHidden", false),
            sortMode = runCatching { SortMode.valueOf(prefs.getString("sort", "NAME")!!) }.getOrDefault(SortMode.NAME),
            ascending = prefs.getBoolean("ascending", true),
        )
    )
    val state: StateFlow<LocalUiState> = _state.asStateFlow()

    private var operationJob: Job? = null

    /** 権限が得られた後に呼ぶ */
    fun start() {
        val roots = findRoots()
        _state.update { it.copy(roots = roots) }
        if (_state.value.currentDir == null) {
            roots.firstOrNull()?.let { open(it.dir) } ?: _state.update { it.copy(message = "ストレージが見つかりません") }
        } else {
            refresh()
        }
    }

    private fun findRoots(): List<StorageRoot> {
        val list = mutableListOf(StorageRoot("内部ストレージ", Environment.getExternalStorageDirectory()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = getApplication<Application>().getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.forEach { vol ->
                val dir = vol.directory
                if (!vol.isPrimary && dir != null && dir.canRead()) {
                    val label = vol.getDescription(getApplication<Application>())
                    list += StorageRoot(if (label.isNullOrBlank()) dir.name else label, dir)
                }
            }
        }
        return list
    }

    fun open(dir: File) {
        _state.update { it.copy(currentDir = dir, selection = emptySet()) }
        refresh()
    }

    /** 1つ上の階層へ。ストレージの最上位なら false */
    fun goUp(): Boolean {
        val s = _state.value
        val dir = s.currentDir ?: return false
        val root = s.currentRoot
        if (root != null && dir.path == root.dir.path) return false
        val parent = dir.parentFile ?: return false
        open(parent)
        return true
    }

    fun refresh() {
        val dir = _state.value.currentDir ?: return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val s = _state.value
            val items = withContext(Dispatchers.IO) { listItems(dir, s.showHidden, s.sortMode, s.ascending) }
            _state.update { st ->
                if (st.currentDir?.path != dir.path) st
                else st.copy(
                    items = items,
                    loading = false,
                    selection = st.selection.filter { sel -> items.any { it.file == sel } }.toSet(),
                )
            }
        }
    }

    private fun listItems(dir: File, showHidden: Boolean, sort: SortMode, asc: Boolean): List<FileItem> {
        val files = dir.listFiles() ?: return emptyList()
        val items = files
            .filter { showHidden || !it.name.startsWith(".") }
            .map { f ->
                val isDir = f.isDirectory
                FileItem(
                    file = f,
                    name = f.name,
                    isDirectory = isDir,
                    size = if (isDir) 0 else f.length(),
                    modified = f.lastModified(),
                    childCount = if (isDir) (f.list()?.count { showHidden || !it.startsWith(".") } ?: 0) else 0,
                )
            }
        val comparator: Comparator<FileItem> = when (sort) {
            SortMode.NAME -> compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.DATE -> compareBy<FileItem> { it.modified }
            SortMode.SIZE -> compareBy<FileItem> { it.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.TYPE -> compareBy<FileItem> { it.name.substringAfterLast('.', "").lowercase() }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val ordered = if (asc) comparator else comparator.reversed()
        // フォルダは常に先頭
        return items.sortedWith(compareByDescending<FileItem> { it.isDirectory }.then(ordered))
    }

    // ---------- 表示設定 ----------

    fun setSort(mode: SortMode) {
        _state.update {
            val asc = if (it.sortMode == mode) !it.ascending else mode == SortMode.NAME || mode == SortMode.TYPE
            it.copy(sortMode = mode, ascending = asc)
        }
        prefs.edit().putString("sort", _state.value.sortMode.name).putBoolean("ascending", _state.value.ascending).apply()
        refresh()
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        prefs.edit().putBoolean("showHidden", _state.value.showHidden).apply()
        refresh()
    }

    // ---------- 選択 ----------

    fun toggleSelect(file: File) {
        _state.update {
            val sel = if (file in it.selection) it.selection - file else it.selection + file
            it.copy(selection = sel)
        }
    }

    fun selectAll() = _state.update { it.copy(selection = it.items.map { item -> item.file }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    // ---------- 操作 ----------

    fun copySelection(cut: Boolean) {
        val files = _state.value.selection.toList()
        if (files.isEmpty()) return
        _state.update {
            it.copy(
                clipboard = Clipboard(files, cut),
                selection = emptySet(),
                message = "${files.size}件を${if (cut) "切り取り" else "コピー"}しました。貼り付け先を開いてください",
            )
        }
    }

    fun clearClipboard() = _state.update { it.copy(clipboard = null) }

    fun paste() {
        val clip = _state.value.clipboard ?: return
        val dest = _state.value.currentDir ?: return
        runOperation(if (clip.isCut) "移動中" else "コピー中", done = { if (clip.isCut) "移動しました" else "コピーしました" }) { p ->
            if (clip.isCut) LocalFileOps.move(clip.files, dest, p) else LocalFileOps.copy(clip.files, dest, p)
            if (clip.isCut) _state.update { it.copy(clipboard = null) }
        }
    }

    fun deleteSelection() {
        val files = _state.value.selection.toList()
        if (files.isEmpty()) return
        runOperation("削除中", done = { "${files.size}件を削除しました" }) { p -> LocalFileOps.delete(files, p) }
    }

    fun rename(file: File, newName: String) {
        runOperation("名前を変更中", done = { "名前を変更しました" }) { LocalFileOps.rename(file, newName.trim()) }
    }

    fun createFolder(name: String) {
        val dir = _state.value.currentDir ?: return
        runOperation("フォルダを作成中", done = { "フォルダを作成しました" }) { LocalFileOps.createFolder(dir, name.trim()) }
    }

    fun compressSelection(zipName: String) {
        val files = _state.value.selection.toList()
        val dir = _state.value.currentDir ?: return
        if (files.isEmpty()) return
        var created: File? = null
        runOperation("圧縮中", done = { "「${created?.name}」を作成しました" }) { p ->
            created = ArchiveOps.compressToZip(files, dir, zipName.trim(), p)
        }
    }

    fun extract(archive: File) {
        var out: File? = null
        runOperation("解凍中", done = { "「${out?.name}」に展開しました" }) { p ->
            out = ArchiveOps.extract(archive, p)
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    private fun runOperation(
        title: String,
        done: () -> String,
        block: suspend (ProgressListener) -> Unit,
    ) {
        if (operationJob?.isActive == true) return
        _state.update { it.copy(operation = Operation(title), selection = emptySet()) }
        operationJob = viewModelScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    block { label, d, t ->
                        _state.update { it.copy(operation = Operation(title, label, d, t)) }
                    }
                }
                done()
            } catch (e: CancellationException) {
                "中止しました"
            } catch (e: Throwable) {
                "エラー: ${e.message ?: e.javaClass.simpleName}"
            }
            _state.update { it.copy(operation = null, message = msg) }
            refresh()
        }
    }
}
