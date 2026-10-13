package com.tonoyama.filemanager.local

import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.coroutineContext

object ArchiveOps {

    private enum class Kind { ZIP, SEVEN_Z, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ }

    private fun kindOf(name: String): Kind? {
        val n = name.lowercase()
        return when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Kind.TAR_GZ
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> Kind.TAR_BZ2
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> Kind.TAR_XZ
            n.endsWith(".tar") -> Kind.TAR
            n.endsWith(".zip") -> Kind.ZIP
            n.endsWith(".7z") -> Kind.SEVEN_Z
            n.endsWith(".gz") -> Kind.GZ
            n.endsWith(".bz2") -> Kind.BZ2
            n.endsWith(".xz") -> Kind.XZ
            else -> null
        }
    }

    fun isArchive(file: File): Boolean = file.isFile && kindOf(file.name) != null

    /** 拡張子を取り除いた名前（展開先フォルダ名に使う） */
    private fun baseName(name: String): String {
        val lower = name.lowercase()
        val multi = listOf(".tar.gz", ".tar.bz2", ".tar.xz")
        multi.firstOrNull { lower.endsWith(it) }?.let { return name.dropLast(it.length) }
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // ---------------- 圧縮 ----------------

    /** 選択したファイル・フォルダを zip にまとめる。作成したファイルを返す */
    suspend fun compressToZip(
        sources: List<File>,
        destDir: File,
        zipName: String,
        progress: ProgressListener,
    ): File {
        val name = if (zipName.lowercase().endsWith(".zip")) zipName else "$zipName.zip"
        val target = LocalFileOps.uniqueTarget(destDir, name)
        val total = LocalFileOps.countEntries(sources)
        var done = 0
        try {
            ZipArchiveOutputStream(target).use { zos ->
                zos.setEncoding("UTF-8")
                zos.setUseLanguageEncodingFlag(true)
                for (src in sources) {
                    addToZip(zos, src, src.name) { label ->
                        done++
                        progress(label, done, total)
                    }
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        return target
    }

    private suspend fun addToZip(
        zos: ZipArchiveOutputStream,
        file: File,
        entryName: String,
        onItem: (String) -> Unit,
    ) {
        coroutineContext.ensureActive()
        onItem(file.name)
        if (file.isDirectory) {
            val entry = ZipArchiveEntry("$entryName/")
            entry.time = file.lastModified()
            zos.putArchiveEntry(entry)
            zos.closeArchiveEntry()
            file.listFiles()?.forEach { child -> addToZip(zos, child, "$entryName/${child.name}", onItem) }
        } else {
            val entry = ZipArchiveEntry(file, entryName)
            zos.putArchiveEntry(entry)
            file.inputStream().use { copyStream(it, zos) }
            zos.closeArchiveEntry()
        }
    }

    // ---------------- 解凍 ----------------

    /** アーカイブを同じ場所の新しいフォルダへ展開する。展開先を返す */
    suspend fun extract(archive: File, progress: ProgressListener): File {
        val kind = kindOf(archive.name)
            ?: throw IOException("この形式には対応していません（zip / 7z / tar / gz / bz2 / xz に対応）")
        val parent = archive.parentFile ?: throw IOException("展開先がありません")

        // 単体圧縮（.gz など）は1ファイルを取り出すだけ
        when (kind) {
            Kind.GZ, Kind.BZ2, Kind.XZ -> {
                val out = LocalFileOps.uniqueTarget(parent, baseName(archive.name))
                progress(out.name, 0, 0)
                try {
                    decompressorFor(kind, BufferedInputStream(archive.inputStream())).use { input ->
                        out.outputStream().use { copyStream(input, it) }
                    }
                } catch (e: Throwable) {
                    out.delete()
                    throw e
                }
                return out
            }
            else -> Unit
        }

        val destDir = LocalFileOps.uniqueTarget(parent, baseName(archive.name))
        if (!destDir.mkdirs()) throw IOException("展開先フォルダを作れません")
        try {
            when (kind) {
                Kind.ZIP -> extractZip(archive, destDir, progress)
                Kind.SEVEN_Z -> extract7z(archive, destDir, progress)
                else -> {
                    val raw = BufferedInputStream(archive.inputStream())
                    val stream: InputStream = if (kind == Kind.TAR) raw else decompressorFor(kind, raw)
                    TarArchiveInputStream(stream, "UTF-8").use { tar -> extractStream(tar, destDir, progress) }
                }
            }
        } catch (e: Throwable) {
            destDir.deleteRecursively()
            throw e
        }
        return destDir
    }

    private fun decompressorFor(kind: Kind, input: InputStream): InputStream = when (kind) {
        Kind.GZ, Kind.TAR_GZ -> GzipCompressorInputStream(input, true)
        Kind.BZ2, Kind.TAR_BZ2 -> BZip2CompressorInputStream(input, true)
        Kind.XZ, Kind.TAR_XZ -> XZCompressorInputStream(input, true)
        else -> input
    }

    /**
     * 展開先の外に書き出す不正なパス（../ など）を防ぎつつ、出力先ファイルを決める
     */
    private fun safeTarget(destDir: File, entryName: String): File {
        val cleaned = entryName.replace('\\', '/').trimStart('/')
        val target = File(destDir, cleaned)
        val destPath = destDir.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(destPath) && target.canonicalPath != destDir.canonicalPath) {
            throw IOException("不正なパスを含むアーカイブです: $entryName")
        }
        return target
    }

    private val sjis: Charset by lazy { Charset.forName("MS932") }

    /** Windowsで作られた zip の日本語ファイル名（Shift_JIS）を化けずに読む */
    private fun zipEntryName(entry: ZipArchiveEntry): String {
        val name = entry.name
        val raw = entry.rawName ?: return name
        if (isValidUtf8(raw)) return name
        // UTF-8として読めない名前は '?' に置き換えられている。Shift_JIS として読み直す
        return if (name.contains('?')) String(raw, sjis) else name
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    private suspend fun extractZip(archive: File, destDir: File, progress: ProgressListener) {
        ZipFile.builder().setFile(archive).get().use { zip ->
            val entries = zip.entries.toList()
            entries.forEachIndexed { i, entry ->
                coroutineContext.ensureActive()
                val name = zipEntryName(entry)
                progress(name.substringAfterLast('/'), i + 1, entries.size)
                val target = safeTarget(destDir, name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { copyStream(input, it) }
                    }
                    if (entry.time > 0) target.setLastModified(entry.time)
                }
            }
        }
    }

    private suspend fun extract7z(archive: File, destDir: File, progress: ProgressListener) {
        SevenZFile.builder().setFile(archive).get().use { sz ->
            val total = sz.entries.count()
            var done = 0
            var entry = sz.nextEntry
            val buf = ByteArray(256 * 1024)
            while (entry != null) {
                coroutineContext.ensureActive()
                done++
                progress(entry.name.substringAfterLast('/'), done, total)
                val target = safeTarget(destDir, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        while (true) {
                            val n = sz.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    if (entry.hasLastModifiedDate) {
                        target.setLastModified(entry.lastModifiedDate.time)
                    }
                }
                entry = sz.nextEntry
            }
        }
    }

    private suspend fun <E : ArchiveEntry> extractStream(
        stream: ArchiveInputStream<E>,
        destDir: File,
        progress: ProgressListener,
    ) {
        var done = 0
        var entry: E? = stream.nextEntry
        while (entry != null) {
            coroutineContext.ensureActive()
            done++
            progress(entry.name.substringAfterLast('/'), done, 0)
            val target = safeTarget(destDir, entry.name)
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.outputStream().use { copyStream(stream, it) }
                val modified = entry.lastModifiedDate?.time ?: 0L
                if (modified > 0) target.setLastModified(modified)
            }
            entry = stream.nextEntry
        }
    }

    private suspend fun copyStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
        }
    }
}
