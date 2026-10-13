package com.tonoyama.filemanager.local

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** 進捗通知: 処理中の名前と、完了数／総数（総数が不明なら 0） */
typealias ProgressListener = (label: String, done: Int, total: Int) -> Unit

object LocalFileOps {

    /** 同名がある場合は「名前 (1).拡張子」のように重複しない名前を返す */
    fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var n = 1
        while (target.exists()) {
            target = File(dir, "$base ($n)$ext")
            n++
        }
        return target
    }

    fun countEntries(files: List<File>): Int =
        files.sumOf { f -> if (f.isDirectory) f.walkTopDown().count() else 1 }

    private fun isInside(child: File, parent: File): Boolean {
        val c = child.canonicalPath
        val p = parent.canonicalPath
        return c == p || c.startsWith(p + File.separator)
    }

    suspend fun copy(sources: List<File>, destDir: File, progress: ProgressListener) {
        val total = countEntries(sources)
        var done = 0
        for (src in sources) {
            if (src.isDirectory && isInside(destDir, src)) {
                throw IOException("「${src.name}」を自分自身の中にはコピーできません")
            }
            val target = uniqueTarget(destDir, src.name)
            copyRecursive(src, target) { name ->
                done++
                progress(name, done, total)
            }
        }
    }

    private suspend fun copyRecursive(src: File, target: File, onItem: (String) -> Unit) {
        coroutineContext.ensureActive()
        if (src.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) throw IOException("フォルダを作れません: ${target.path}")
            onItem(src.name)
            src.listFiles()?.forEach { child -> copyRecursive(child, File(target, child.name), onItem) }
        } else {
            onItem(src.name)
            src.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            target.setLastModified(src.lastModified())
        }
    }

    suspend fun move(sources: List<File>, destDir: File, progress: ProgressListener) {
        val total = sources.size
        sources.forEachIndexed { i, src ->
            coroutineContext.ensureActive()
            if (src.parentFile?.canonicalPath == destDir.canonicalPath) {
                progress(src.name, i + 1, total)
                return@forEachIndexed
            }
            if (src.isDirectory && isInside(destDir, src)) {
                throw IOException("「${src.name}」を自分自身の中には移動できません")
            }
            val target = uniqueTarget(destDir, src.name)
            progress(src.name, i + 1, total)
            // 同じストレージ内なら名前変更だけで済む。だめならコピーして削除
            if (!src.renameTo(target)) {
                copyRecursive(src, target) { }
                if (!src.deleteRecursively()) throw IOException("移動元を削除できません: ${src.name}")
            }
        }
    }

    suspend fun delete(targets: List<File>, progress: ProgressListener) {
        val total = targets.size
        targets.forEachIndexed { i, f ->
            coroutineContext.ensureActive()
            progress(f.name, i + 1, total)
            if (!f.deleteRecursively()) throw IOException("削除できません: ${f.name}")
        }
    }

    fun rename(file: File, newName: String): File {
        validateName(newName)
        val target = File(file.parentFile, newName)
        if (target.exists()) throw IOException("同じ名前がすでにあります")
        if (!file.renameTo(target)) throw IOException("名前を変更できません")
        return target
    }

    fun createFolder(dir: File, name: String): File {
        validateName(name)
        val target = File(dir, name)
        if (target.exists()) throw IOException("同じ名前がすでにあります")
        if (!target.mkdir()) throw IOException("フォルダを作れません")
        return target
    }

    private fun validateName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/')) {
            throw IOException("使えない名前です")
        }
    }
}
