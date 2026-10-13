package com.tonoyama.filemanager.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tonoyama.filemanager.local.SortMode
import com.tonoyama.filemanager.media.Category
import com.tonoyama.filemanager.media.IndexedFile
import com.tonoyama.filemanager.media.MediaIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CategoryState(
    val category: Category? = null,
    val items: List<IndexedFile> = emptyList(),
    val loading: Boolean = false,
    val selection: Set<File> = emptySet(),
    val sortMode: SortMode = SortMode.DATE,
    val ascending: Boolean = false,
    val grid: Boolean = false,
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val totalBytes: Long get() = items.sumOf { it.size }
}

class CategoryViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("category", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(CategoryState())
    val state: StateFlow<CategoryState> = _state.asStateFlow()
    private var job: Job? = null

    fun load(category: Category) {
        val changed = _state.value.category != category
        if (changed) {
            _state.update {
                CategoryState(
                    category = category,
                    grid = prefs.getBoolean("grid_${category.name}", category.gridByDefault),
                    sortMode = it.sortMode,
                    ascending = it.ascending,
                )
            }
        }
        reload()
    }

    fun reload() {
        val cat = _state.value.category ?: return
        job?.cancel()
        _state.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { MediaIndex.list(getApplication<Application>(), cat) }.getOrDefault(emptyList())
            }
            val s = _state.value
            val sorted = sort(list, s.sortMode, s.ascending)
            _state.update { st ->
                st.copy(
                    items = sorted,
                    loading = false,
                    selection = st.selection.intersect(sorted.mapTo(HashSet()) { it.file }),
                )
            }
        }
    }

    private fun sort(list: List<IndexedFile>, mode: SortMode, asc: Boolean): List<IndexedFile> {
        val c: Comparator<IndexedFile> = when (mode) {
            SortMode.NAME -> compareBy<IndexedFile, String>(String.CASE_INSENSITIVE_ORDER) { it.file.name }
            SortMode.DATE -> compareBy<IndexedFile> { it.modified }
            SortMode.SIZE -> compareBy<IndexedFile> { it.size }
            SortMode.TYPE -> compareBy<IndexedFile> { it.file.extension.lowercase() }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
        }
        return list.sortedWith(if (asc) c else c.reversed())
    }

    fun setSort(mode: SortMode) {
        _state.update {
            val asc = if (it.sortMode == mode) !it.ascending else mode == SortMode.NAME || mode == SortMode.TYPE
            it.copy(sortMode = mode, ascending = asc, items = sort(it.items, mode, asc))
        }
    }

    fun toggleGrid() {
        val cat = _state.value.category ?: return
        _state.update { it.copy(grid = !it.grid) }
        prefs.edit().putBoolean("grid_${cat.name}", _state.value.grid).apply()
    }

    fun toggleSelect(file: File) = _state.update {
        it.copy(selection = if (file in it.selection) it.selection - file else it.selection + file)
    }

    fun selectAll() = _state.update { it.copy(selection = it.items.map { f -> f.file }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }
}
