package com.tonoyama.filemanager.pcloud

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.SecureRandom

data class PCloudSession(val token: String, val host: String)

/**
 * pCloud の OAuth 2.0 認証。
 *
 * 主方式は「poll_token」方式（pCloud 公式 JavaScript SDK と同じ）。ブラウザで許可してもらう間、
 * 米国・欧州の両方のサーバーにトークンを問い合わせ続け、返ってきた側をそのアカウントの地域とする。
 * リダイレクトURIの登録が要らず、地域の指定も不要。
 *
 * 予備方式として「認証コード」方式（公式ドキュメント記載）にも対応する。
 */
object PCloudAuth {

    private const val AUTHORIZE = "https://my.pcloud.com/oauth2/authorize"

    fun newRequestId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val rnd = SecureRandom()
        return (1..40).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    fun pollAuthorizeUrl(clientId: String, requestId: String): Uri =
        Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "poll_token")
            .appendQueryParameter("request_id", requestId)
            .build()

    /** redirect_uri を付けない code 方式。許可するとページ上にコードが表示される */
    fun codeAuthorizeUrl(clientId: String): Uri =
        Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .build()

    /**
     * ブラウザでの許可を待つ。両地域のサーバーを並行して問い合わせ、先に成功した方を採用する。
     */
    suspend fun waitForPollToken(clientId: String, requestId: String, timeoutMs: Long = 10 * 60_000L): PCloudSession {
        var lastError: Throwable? = null
        val result = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val found = Channel<PCloudSession>(Channel.UNLIMITED)
                val jobs = PCloudHosts.all.map { host ->
                    launch {
                        while (true) {
                            try {
                                val json = PCloudHttp.call(
                                    host,
                                    "oauth2_token",
                                    mapOf("client_id" to clientId, "request_id" to requestId),
                                    readTimeoutMs = 120_000,
                                )
                                val token = json.optString("access_token")
                                if (token.isNotEmpty()) {
                                    val resolved = PCloudHosts.forLocation(json.optInt("locationid", 0)) ?: host
                                    found.send(PCloudSession(token, resolved))
                                    return@launch
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                lastError = e
                            }
                            delay(2_000)
                        }
                    }
                }
                val session = found.receive()
                jobs.forEach { it.cancel() }
                session
            }
        }
        return result ?: throw IOException(
            "時間内に許可が確認できませんでした" + (lastError?.message?.let { "（$it）" } ?: "")
        )
    }

    /** 認証コードをトークンに交換する（どちらの地域のコードか分からないので両方試す） */
    suspend fun exchangeCode(clientId: String, clientSecret: String, code: String): PCloudSession {
        var lastError: Throwable? = null
        for (host in PCloudHosts.all) {
            try {
                val json = PCloudHttp.call(
                    host,
                    "oauth2_token",
                    mapOf("client_id" to clientId, "client_secret" to clientSecret, "code" to code.trim()),
                )
                val token = json.optString("access_token")
                if (token.isNotEmpty()) {
                    val resolved = PCloudHosts.forLocation(json.optInt("locationid", 0)) ?: host
                    return PCloudSession(token, resolved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("トークンを取得できませんでした")
    }

    /**
     * 保存済みトークンの地域が不明・誤りのとき、どちらのサーバーで使えるか確かめる
     */
    suspend fun detectHost(token: String): String {
        var lastError: Throwable? = null
        for (host in PCloudHosts.all) {
            try {
                PCloudApi(host, token).userEmail()
                return host
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("pCloud に接続できません")
    }
}
