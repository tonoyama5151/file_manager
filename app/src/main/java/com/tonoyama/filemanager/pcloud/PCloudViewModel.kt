package com.tonoyama.filemanager.pcloud

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tonoyama.filemanager.local.LocalFileOps
import com.tonoyama.filemanager.local.StoragePermission
import com.tonoyama.filemanager.thumb.ThumbnailLoader
import com.tonoyama.filemanager.util.Format
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
import java.io.IOException

data class FolderRef(val folderId: Long, val name: String)

data class Download(val name: String, val percent: Int, val detail: String)

data class PCloudUiState(
    val clientId: String = "",
    val clientSecret: String = "",
    val loggedIn: Boolean = false,
    val email: String = "",
    val region: String = "",
    val waitingForBrowser: Boolean = false,
    val stack: List<FolderRef> = listOf(FolderRef(0, "pCloud")),
    val entries: List<PEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val download: Download? = null,
    /** 他アプリで開くために用意できたファイル（画面側で開いたら consume する） */
    val readyToOpen: File? = null,
    val message: String? = null,
)

class PCloudViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("pcloud", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(PCloudUiState())
    val state: StateFlow<PCloudUiState> = _state.asStateFlow()

    private var authJob: Job? = null
    private var downloadJob: Job? = null
    private var api: PCloudApi? = null

    init {
        val token = prefs.getString("token", null)
        val host = prefs.getString("host", null)
        _state.update {
            it.copy(
                clientId = prefs.getString("clientId", "") ?: "",
                clientSecret = prefs.getString("clientSecret", "") ?: "",
                email = prefs.getString("email", "") ?: "",
            )
        }
        if (!token.isNullOrEmpty()) {
            if (host.isNullOrEmpty()) {
                viewModelScope.launch { resolveAndLogin(token) }
            } else {
                login(PCloudSession(token, host), save = false)
            }
        }
        cleanCache()
    }

    private suspend fun resolveAndLogin(token: String) {
        try {
            val host = PCloudAuth.detectHost(token)
            login(PCloudSession(token, host), save = true)
        } catch (e: Exception) {
            _state.update { it.copy(error = "接続できません: ${e.message}") }
        }
    }

    fun setClientId(value: String) {
        _state.update { it.copy(clientId = value.trim()) }
        prefs.edit().putString("clientId", value.trim()).apply()
    }

    fun setClientSecret(value: String) {
        _state.update { it.copy(clientSecret = value.trim()) }
        prefs.edit().putString("clientSecret", value.trim()).apply()
    }

    // ---------------- ログイン ----------------

    /** poll 方式：ブラウザで開くURLを返し、裏でトークンを待ち受ける */
    fun startBrowserLogin(): android.net.Uri? {
        val clientId = _state.value.clientId
        if (clientId.isEmpty()) {
            _state.update { it.copy(message = "Client ID を入力してください") }
            return null
        }
        val requestId = PCloudAuth.newRequestId()
        authJob?.cancel()
        _state.update { it.copy(waitingForBrowser = true, error = null) }
        authJob = viewModelScope.launch {
            try {
                val session = PCloudAuth.waitForPollToken(clientId, requestId)
                login(session, save = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "ログインできませんでした: ${e.message}") }
            } finally {
                // 「もう一度開く」で新しい待ち受けに切り替わった場合は表示を消さない
                if (authJob === coroutineContext[Job]) {
                    _state.update { it.copy(waitingForBrowser = false) }
                }
            }
        }
        return PCloudAuth.pollAuthorizeUrl(clientId, requestId)
    }

    fun cancelBrowserLogin() {
        authJob?.cancel()
        _state.update { it.copy(waitingForBrowser = false) }
    }

    fun codeLoginUrl(): android.net.Uri? {
        val clientId = _state.value.clientId
        if (clientId.isEmpty()) {
            _state.update { it.copy(message = "Client ID を入力してください") }
            return null
        }
        return PCloudAuth.codeAuthorizeUrl(clientId)
    }

    fun loginWithCode(code: String) {
        val s = _state.value
        if (s.clientId.isEmpty() || s.clientSecret.isEmpty() || code.isBlank()) {
            _state.update { it.copy(message = "Client ID・Client Secret・認証コードをすべて入力してください") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                login(PCloudAuth.exchangeCode(s.clientId, s.clientSecret, code), save = true)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "認証に失敗しました: ${e.message}") }
            }
        }
    }

    private fun login(session: PCloudSession, save: Boolean) {
        api = PCloudApi(session.host, session.token)
        if (save) {
            prefs.edit().putString("token", session.token).putString("host", session.host).apply()
        }
        _state.update {
            it.copy(
                loggedIn = true,
                region = if (session.host == PCloudHosts.EU) "欧州" else "米国",
                stack = listOf(FolderRef(0, "pCloud")),
                error = null,
            )
        }
        loadCurrent()
        viewModelScope.launch {
            try {
                val email = api?.userEmail().orEmpty()
                prefs.edit().putString("email", email).apply()
                _state.update { it.copy(email = email) }
            } catch (e: Exception) {
                // メールアドレスは表示用なので取れなくても続行
            }
        }
    }

    fun logout() {
        api = null
        prefs.edit().remove("token").remove("host").remove("email").apply()
        _state.update {
            it.copy(loggedIn = false, email = "", entries = emptyList(), stack = listOf(FolderRef(0, "pCloud")))
        }
    }

    // ---------------- 閲覧 ----------------

    fun openFolder(entry: PEntry) {
        _state.update { it.copy(stack = it.stack + FolderRef(entry.folderId, entry.name)) }
        loadCurrent()
    }

    fun openStackIndex(index: Int) {
        _state.update { it.copy(stack = it.stack.take(index + 1)) }
        loadCurrent()
    }

    fun goUp(): Boolean {
        if (_state.value.stack.size <= 1) return false
        _state.update { it.copy(stack = it.stack.dropLast(1)) }
        loadCurrent()
        return true
    }

    fun loadCurrent() {
        val a = api ?: return
        val folder = _state.value.stack.last()
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val result = a.listFolder(folder.folderId)
                val sorted = result.entries.sortedWith(
                    compareByDescending<PEntry> { it.isFolder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
                _state.update { st ->
                    if (st.stack.last().folderId != folder.folderId) st
                    else st.copy(entries = sorted, loading = false)
                }
            } catch (e: PCloudException) {
                if (e.isAuthError) {
                    logout()
                    _state.update { it.copy(loading = false, error = "ログインの有効期限が切れました。もう一度ログインしてください") }
                } else {
                    _state.update { it.copy(loading = false, error = e.message) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "読み込めません: ${e.message}") }
            }
        }
    }

    // ---------------- ダウンロード ----------------

    private val cacheDir: File get() = File(getApplication<Application>().cacheDir, "pcloud").apply { mkdirs() }

    /** 1日以上前の一時ファイルを消す */
    private fun cleanCache() {
        val limit = System.currentTimeMillis() - 24 * 3600_000L
        cacheDir.listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
    }

    /** 一時フォルダにダウンロードして、他アプリで開ける状態にする */
    fun openFile(entry: PEntry) {
        val dir = File(cacheDir, entry.fileId.toString()).apply { mkdirs() }
        val target = File(dir, entry.name)
        if (target.exists() && target.length() == entry.size) {
            _state.update { it.copy(readyToOpen = target) }
            return
        }
        download(entry, target) { _state.update { it.copy(readyToOpen = target) } }
    }

    /** 端末の Download/pCloud フォルダに保存する */
    fun saveToDevice(entry: PEntry) {
        if (!StoragePermission.granted(getApplication<Application>())) {
            _state.update { it.copy(message = "先に「端末」タブでファイルへのアクセスを許可してください") }
            return
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "pCloud")
        if (!dir.isDirectory && !dir.mkdirs()) {
            _state.update { it.copy(message = "保存先フォルダを作れません") }
            return
        }
        val target = LocalFileOps.uniqueTarget(dir, entry.name)
        download(entry, target) {
            _state.update { it.copy(message = "Download/pCloud/${target.name} に保存しました") }
        }
    }

    private fun download(entry: PEntry, target: File, onDone: () -> Unit) {
        val a = api ?: return
        if (downloadJob?.isActive == true) return
        _state.update { it.copy(download = Download(entry.name, 0, "接続中…")) }
        downloadJob = viewModelScope.launch {
            try {
                val url = a.fileLink(entry.fileId)
                val partial = File(target.parentFile, target.name + ".part")
                PCloudHttp.download(url, partial) { got, total ->
                    val size = if (total > 0) total else entry.size
                    val pct = if (size > 0) ((got * 100) / size).toInt() else 0
                    _state.update {
                        it.copy(download = Download(entry.name, pct, "${Format.size(got)} / ${Format.size(size)}"))
                    }
                }
                withContext(Dispatchers.IO) {
                    if (target.exists()) target.delete()
                    if (!partial.renameTo(target)) throw IOException("ファイルを保存できません")
                    if (entry.modified > 0) target.setLastModified(entry.modified)
                }
                _state.update { it.copy(download = null) }
                onDone()
            } catch (e: CancellationException) {
                _state.update { it.copy(download = null, message = "中止しました") }
            } catch (e: Exception) {
                _state.update { it.copy(download = null, message = "ダウンロードに失敗しました: ${e.message}") }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    // ---------------- サムネイル ----------------

    fun thumbKey(entry: PEntry) = "pcloud|${entry.fileId}|${entry.modified}"

    suspend fun thumbnail(entry: PEntry): android.graphics.Bitmap? {
        val a = api ?: return null
        return ThumbnailLoader.load(thumbKey(entry)) { a.thumbnail(entry.fileId, 192) }
    }

    fun consumeReadyToOpen() = _state.update { it.copy(readyToOpen = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
