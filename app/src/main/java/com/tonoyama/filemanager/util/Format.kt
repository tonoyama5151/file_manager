package com.tonoyama.filemanager.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return if (value >= 100) "%.0f %s".format(value, units[i])
        else "%.1f %s".format(value, units[i])
    }

    fun date(millis: Long): String = if (millis <= 0) "" else dateFormat.format(Date(millis))
}
