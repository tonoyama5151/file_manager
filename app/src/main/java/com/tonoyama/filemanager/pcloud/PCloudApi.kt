package com.tonoyama.filemanager.pcloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** pCloud のデータセンター。アカウントによって米国か欧州のどちらかに置かれている */
object PCloudHosts {
    const val US = "api.pcloud.com"
    const val EU = "eapi.pcloud.com"
    val all = listOf(US, EU)

    fun forLocation(locationId: Int): String? = when (locationId) {
        1 -> US
        2 -> EU
        else -> null
    }
}

class PCloudException(val code: Int, message: String) : IOException(message) {
    /** トークンが無効になったときのエラー */
    val isAuthError: Boolean get() = code in setOf(1000, 2000, 2094, 2095)
}

data class PEntry(
    val name: String,
    val isFolder: Boolean,
    val folderId: Long,
    val fileId: Long,
    val size: Long,
    val modified: Long,
    /** pCloud 側でサムネイルを作れるファイルか（画像・動画など） */
    val hasThumb: Boolean = false,
)

data class PFolder(val folderId: Long, val name: String, val entries: List<PEntry>)

object PCloudHttp {

    /** pCloud API を呼び、result=0 の JSON を返す（それ以外は例外） */
    suspend fun call(
        host: String,
        method: String,
        params: Map<String, String>,
        readTimeoutMs: Int = 30_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val url = URL("https://$host/$method" + if (query.isEmpty()) "" else "?$query")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: throw IOException("HTTP $code")
            val json = JSONObject(body)
            val result = json.optInt("result", -1)
            if (result != 0) {
                throw PCloudException(result, json.optString("error", "エラー $result"))
            }
            json
        } finally {
            conn.disconnect()
        }
    }

    /** URL のファイルをダウンロードする。onProgress(受信バイト, 総バイト[不明なら-1]) */
    suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("ダウンロードに失敗しました（HTTP $code）")
            val total = conn.contentLengthLong
            var received = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        if (received - lastReport > 512 * 1024) {
                            lastReport = received
                            onProgress(received, total)
                        }
                    }
                }
            }
            onProgress(received, total)
        } catch (e: Throwable) {
            target.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}

class PCloudApi(private val host: String, private val token: String) {

    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)

    private suspend fun call(method: String, params: Map<String, String> = emptyMap()) =
        PCloudHttp.call(host, method, params + ("access_token" to token))

    suspend fun userEmail(): String = call("userinfo").optString("email", "")

    suspend fun listFolder(folderId: Long): PFolder {
        val meta = call("listfolder", mapOf("folderid" to folderId.toString())).getJSONObject("metadata")
        val contents = meta.optJSONArray("contents")
        val entries = buildList {
            if (contents != null) {
                for (i in 0 until contents.length()) {
                    val o = contents.getJSONObject(i)
                    add(
                        PEntry(
                            name = o.optString("name"),
                            isFolder = o.optBoolean("isfolder"),
                            folderId = o.optLong("folderid"),
                            fileId = o.optLong("fileid"),
                            size = o.optLong("size"),
                            modified = parseDate(o.optString("modified")),
                            hasThumb = o.optBoolean("thumb"),
                        )
                    )
                }
            }
        }
        val name = if (folderId == 0L) "pCloud" else meta.optString("name")
        return PFolder(folderId, name, entries)
    }

    /** ファイルの一時ダウンロードURLを取得する */
    suspend fun fileLink(fileId: Long): String {
        val json = call("getfilelink", mapOf("fileid" to fileId.toString(), "forcedownload" to "1"))
        val hosts = json.getJSONArray("hosts")
        if (hosts.length() == 0) throw IOException("ダウンロード先が見つかりません")
        return "https://" + hosts.getString(0) + json.getString("path")
    }

    /** サムネイル画像（正方形に切り抜き）を取得する。作れない場合は null */
    suspend fun thumbnail(fileId: Long, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        // 幅・高さは 4 か 5 の倍数で 16〜1024 の範囲という制約がある
        val size = (sizePx / 4 * 4).coerceIn(16, 1024)
        val query = "fileid=$fileId&size=${size}x$size&crop=1&access_token=" + URLEncoder.encode(token, "UTF-8")
        val conn = URL("https://$host/getthumb?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) return@withContext null
            // エラー時は画像ではなく JSON が返る
            if (conn.contentType?.contains("json") == true) return@withContext null
            val bytes = conn.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseDate(text: String): Long = try {
        synchronized(dateFormat) { dateFormat.parse(text)?.time ?: 0L }
    } catch (e: Exception) {
        0L
    }
}
