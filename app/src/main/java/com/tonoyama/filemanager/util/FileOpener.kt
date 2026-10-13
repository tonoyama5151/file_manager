package com.tonoyama.filemanager.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object FileOpener {

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            "md", "log", "csv", "json", "kt", "py" -> "text/plain"
            else -> "*/*"
        }
    }

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** 他のアプリでファイルを開く */
    fun open(context: Context, file: File) {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "このファイルを開けるアプリがありません", Toast.LENGTH_SHORT).show()
        }
    }

    /** 共有シートで送る */
    fun share(context: Context, files: List<File>) {
        val targets = files.filter { it.isFile }
        if (targets.isEmpty()) {
            Toast.makeText(context, "共有できるのはファイルのみです", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList(targets.map { uriFor(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(targets[0].name)
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "共有").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
