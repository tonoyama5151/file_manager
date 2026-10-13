package com.tonoyama.filemanager.media

import android.app.usage.StorageStatsManager
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.util.PriorityQueue

/** ジャンル（ホーム画面のタイル） */
enum class Category(val label: String) {
    IMAGES("画像"),
    AUDIO("オーディオ"),
    VIDEO("動画"),
    DOCUMENTS("ドキュメント"),
    APK("アプリ(APK)"),
    ARCHIVES("圧縮ファイル"),
    RECENT("新着ファイル"),
    ;

    /** 一覧をグリッドで表示するのが自然なジャンル */
    val gridByDefault: Boolean get() = this == IMAGES || this == VIDEO
}

data class IndexedFile(
    val file: File,
    val size: Long,
    val modified: Long,
)

data class CatStat(val count: Int = 0, val bytes: Long = 0)

data class MediaSummary(
    val stats: Map<Category, CatStat>,
    /** 本体ストレージ上にあるものだけの容量（ストレージ分析用） */
    val primaryBytes: Map<Category, Long>,
    val largest: List<IndexedFile>,
)

data class VolumeInfo(
    val label: String,
    val dir: File,
    val primary: Boolean,
    val total: Long,
    val free: Long,
) {
    val used: Long get() = (total - free).coerceAtLeast(0)
}

/**
 * Android のメディアデータベース（MediaStore）を使ったジャンル分け。
 * 端末全体を毎回たどるより大幅に速い。
 */
object MediaIndex {

    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "avif", "svg")
    private val videos = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts", "wmv", "flv")
    private val audios = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "mid", "midi")
    private val docs = setOf(
        "pdf", "txt", "md", "rtf", "csv", "epub",
        "doc", "docx", "xls", "xlsx", "xlsm", "ppt", "pptx", "odt", "ods", "odp",
    )
    private val apks = setOf("apk", "xapk", "apks")
    private val archives = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz")

    const val RECENT_DAYS = 7

    fun classify(name: String, mime: String?): Category? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in images -> Category.IMAGES
            ext in videos -> Category.VIDEO
            ext in audios -> Category.AUDIO
            ext in docs -> Category.DOCUMENTS
            ext in apks -> Category.APK
            ext in archives -> Category.ARCHIVES
            mime == null -> null
            mime.startsWith("image/") -> Category.IMAGES
            mime.startsWith("video/") -> Category.VIDEO
            mime.startsWith("audio/") -> Category.AUDIO
            else -> null
        }
    }

    private fun isHidden(path: String) = path.contains("/.")

    /** MediaStore の全ファイルを1行ずつ渡す */
    @Suppress("DEPRECATION")
    private inline fun forEachFile(context: Context, block: (path: String, size: Long, modifiedMs: Long, mime: String?) -> Unit) {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        // フォルダの行は MIME_TYPE が空なので除外される
        context.contentResolver.query(
            uri, projection, "${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL", null, null,
        )?.use { c ->
            val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (c.moveToNext()) {
                val path = c.getString(iPath) ?: continue
                if (isHidden(path)) continue
                block(path, c.getLong(iSize), c.getLong(iMod) * 1000, c.getString(iMime))
            }
        }
    }

    /** ジャンルごとの件数・容量と、大きいファイル上位を集計する */
    fun summarize(context: Context, largestCount: Int = 50): MediaSummary {
        val counts = HashMap<Category, IntArray>()
        val bytes = HashMap<Category, LongArray>()
        val recentLimit = System.currentTimeMillis() - RECENT_DAYS * 24 * 3600_000L
        val heap = PriorityQueue<IndexedFile>(compareBy { it.size })
        val primaryRoot = Environment.getExternalStorageDirectory().path + "/"
        val primary = HashMap<Category, LongArray>()

        fun add(cat: Category, size: Long) {
            counts.getOrPut(cat) { IntArray(1) }[0]++
            bytes.getOrPut(cat) { LongArray(1) }[0] += size
        }

        forEachFile(context) { path, size, modified, mime ->
            val name = path.substringAfterLast('/')
            classify(name, mime)?.let { cat ->
                add(cat, size)
                if (path.startsWith(primaryRoot)) primary.getOrPut(cat) { LongArray(1) }[0] += size
            }
            if (modified >= recentLimit) add(Category.RECENT, size)
            if (size > 0 && (heap.size < largestCount || size > heap.peek()!!.size)) {
                heap.add(IndexedFile(File(path), size, modified))
                if (heap.size > largestCount) heap.poll()
            }
        }
        val stats = Category.entries.associateWith { CatStat(counts[it]?.get(0) ?: 0, bytes[it]?.get(0) ?: 0) }
        val primaryBytes = Category.entries.associateWith { primary[it]?.get(0) ?: 0L }
        return MediaSummary(stats, primaryBytes, heap.toList().sortedByDescending { it.size })
    }

    /** ジャンルに属するファイル一覧 */
    fun list(context: Context, category: Category): List<IndexedFile> {
        val out = ArrayList<IndexedFile>()
        val recentLimit = System.currentTimeMillis() - RECENT_DAYS * 24 * 3600_000L
        forEachFile(context) { path, size, modified, mime ->
            val match = if (category == Category.RECENT) modified >= recentLimit
            else classify(path.substringAfterLast('/'), mime) == category
            if (match) out += IndexedFile(File(path), size, modified)
        }
        return out
    }

    // ---------------- ストレージ容量 ----------------

    fun volumes(context: Context): List<VolumeInfo> {
        val list = ArrayList<VolumeInfo>()
        val primaryDir = Environment.getExternalStorageDirectory()
        val (total, free) = primarySpace(context, primaryDir)
        list += VolumeInfo("メインストレージ", primaryDir, true, total, free)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.forEach { vol ->
                val dir = vol.directory
                if (!vol.isPrimary && dir != null && dir.canRead()) {
                    val st = StatFs(dir.path)
                    val label = vol.getDescription(context)
                    list += VolumeInfo(if (label.isNullOrBlank()) "SDカード" else label, dir, false, st.totalBytes, st.availableBytes)
                }
            }
        }
        return list
    }

    /** 本体ストレージは StorageStatsManager の方が実際の容量（256GB など）に近い値を返す */
    private fun primarySpace(context: Context, dir: File): Pair<Long, Long> = try {
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        ssm.getTotalBytes(StorageManager.UUID_DEFAULT) to ssm.getFreeBytes(StorageManager.UUID_DEFAULT)
    } catch (e: Exception) {
        val st = StatFs(dir.path)
        st.totalBytes to st.availableBytes
    }

    // ---------------- データベースの更新 ----------------

    /** 新しくできたファイルをメディアデータベースに登録する（フォルダなら中身も） */
    fun notifyAdded(context: Context, targets: List<File>) {
        val paths = ArrayList<String>()
        for (t in targets) {
            if (t.isDirectory) {
                t.walkTopDown().filter { it.isFile }.take(20_000).forEach { paths += it.path }
            } else {
                paths += t.path
            }
        }
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    /** 削除・移動元のファイルをメディアデータベースから消す */
    @Suppress("DEPRECATION")
    fun notifyRemoved(context: Context, targets: List<File>) {
        val uri = MediaStore.Files.getContentUri("external")
        for (t in targets) {
            try {
                val escaped = t.path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                context.contentResolver.delete(
                    uri,
                    "${MediaStore.MediaColumns.DATA} = ? OR ${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'",
                    arrayOf(t.path, "$escaped/%"),
                )
            } catch (e: Exception) {
                // 権限の都合で消せない場合は、下のスキャンに任せる
            }
        }
        if (targets.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, targets.map { it.path }.toTypedArray(), null, null)
        }
    }
}
