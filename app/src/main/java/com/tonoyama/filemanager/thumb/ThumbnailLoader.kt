package com.tonoyama.filemanager.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import kotlin.math.max
import kotlin.math.roundToInt

enum class ThumbKind { IMAGE, VIDEO, AUDIO, PDF, APK }

/**
 * サムネイルの作成とメモリキャッシュ。
 * 同時に作るのは3件までに抑え、スクロール中に端末が重くならないようにする。
 */
object ThumbnailLoader {

    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "avif")
    private val videoExt = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts", "wmv")
    private val audioExt = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wma")

    fun kindOf(name: String): ThumbKind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in imageExt -> ThumbKind.IMAGE
            ext in videoExt -> ThumbKind.VIDEO
            ext in audioExt -> ThumbKind.AUDIO
            ext == "pdf" -> ThumbKind.PDF
            ext == "apk" -> ThumbKind.APK
            else -> null
        }
    }

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(8 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** サムネイルが作れなかったもの（何度も試さない） */
    private val failed = Collections.synchronizedSet(HashSet<String>())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(3)

    fun keyFor(file: File, modified: Long) = "${file.path}|$modified"

    fun cached(key: String): Bitmap? = cache.get(key)

    fun hasFailed(key: String): Boolean = key in failed

    /** 任意の読み込み処理をキャッシュ付きで実行する（pCloud でも使う） */
    suspend fun load(key: String, producer: suspend () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        if (key in failed) return null
        return withContext(dispatcher) {
            cache.get(key)?.let { return@withContext it }
            val bmp = try {
                producer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 壊れたファイル・パスワード付きPDF・メモリ不足などは諦めてアイコン表示にする
                null
            }
            if (bmp != null) cache.put(key, bmp) else failed += key
            bmp
        }
    }

    suspend fun loadLocal(context: Context, file: File, modified: Long, sizePx: Int): Bitmap? {
        val kind = kindOf(file.name) ?: return null
        return load(keyFor(file, modified)) { create(context, file, kind, sizePx) }
    }

    fun forget(file: File) {
        val prefix = file.path + "|"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    private fun create(context: Context, file: File, kind: ThumbKind, sizePx: Int): Bitmap? {
        val size = Size(sizePx, sizePx)
        return when (kind) {
            ThumbKind.IMAGE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createImageThumbnail(file, size, null)
                else decodeImageLegacy(file, sizePx)
            ThumbKind.VIDEO ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createVideoThumbnail(file, size, null)
                else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Video.Thumbnails.MINI_KIND)
                }
            ThumbKind.AUDIO -> embeddedArt(file, sizePx)
            ThumbKind.PDF -> pdfFirstPage(file, sizePx)
            ThumbKind.APK -> apkIcon(context, file, sizePx)
        }
    }

    /** 縮小して読み込み、EXIFの向きに合わせて回転する（Android 9 以下用） */
    private fun decodeImageLegacy(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** 音楽ファイルに埋め込まれたジャケット画像 */
    private fun embeddedArt(file: File, sizePx: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.path)
            val bytes = mmr.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            mmr.release()
        }
    }

    /** PDF の1ページ目を描画する */
    private fun pdfFirstPage(file: File, sizePx: Int): Bitmap? {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: Exception) {
            pfd.close()
            throw e
        }
        try {
            if (renderer.pageCount == 0) return null
            val page = renderer.openPage(0)
            try {
                val scale = sizePx.toFloat() / max(page.width, page.height)
                val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            } finally {
                page.close()
            }
        } finally {
            renderer.close()
        }
    }

    /** APK ファイルに含まれるアプリのアイコン */
    private fun apkIcon(context: Context, file: File, sizePx: Int): Bitmap? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.path, 0) ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = file.path
        app.publicSourceDir = file.path
        return app.loadIcon(pm).toBitmap(sizePx, sizePx)
    }
}
