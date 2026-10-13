package com.tonoyama.filemanager.home

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tonoyama.filemanager.media.CatStat
import com.tonoyama.filemanager.media.MediaIndex
import com.tonoyama.filemanager.media.MediaSummary
import com.tonoyama.filemanager.media.VolumeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class HomeState(
    val volumes: List<VolumeInfo> = emptyList(),
    val downloads: CatStat? = null,
    val summary: MediaSummary? = null,
    val loading: Boolean = false,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var job: Job? = null

    val downloadDir: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun refresh() {
        job?.cancel()
        _state.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val volumes = withContext(Dispatchers.IO) { runCatching { MediaIndex.volumes(ctx) }.getOrDefault(emptyList()) }
            _state.update { it.copy(volumes = volumes) }

            val downloads = withContext(Dispatchers.IO) { folderStat(downloadDir) }
            _state.update { it.copy(downloads = downloads) }

            val summary = withContext(Dispatchers.IO) { runCatching { MediaIndex.summarize(ctx) }.getOrNull() }
            _state.update { it.copy(summary = summary ?: it.summary, loading = false) }
        }
    }

    /** フォルダ内（隠しフォルダを除く）のファイル数と合計サイズ */
    private fun folderStat(dir: File): CatStat {
        var count = 0
        var bytes = 0L
        dir.walkTopDown()
            .onEnter { it == dir || !it.name.startsWith(".") }
            .forEach { f ->
                if (f.isFile && !f.name.startsWith(".")) {
                    count++
                    bytes += f.length()
                }
            }
        return CatStat(count, bytes)
    }
}
