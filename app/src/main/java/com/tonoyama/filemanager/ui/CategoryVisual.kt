package com.tonoyama.filemanager.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.tonoyama.filemanager.media.Category

val Category.icon: ImageVector
    get() = when (this) {
        Category.IMAGES -> Icons.Filled.Image
        Category.AUDIO -> Icons.Filled.MusicNote
        Category.VIDEO -> Icons.Filled.Movie
        Category.DOCUMENTS -> Icons.Filled.Description
        Category.APK -> Icons.Filled.Android
        Category.ARCHIVES -> Icons.Filled.FolderZip
        Category.RECENT -> Icons.Filled.Schedule
    }

val Category.color: Color
    get() = when (this) {
        Category.IMAGES -> Color(0xFF7B4BC4)
        Category.AUDIO -> Color(0xFF00897B)
        Category.VIDEO -> Color(0xFFD32F2F)
        Category.DOCUMENTS -> Color(0xFF1E88E5)
        Category.APK -> Color(0xFF7CB342)
        Category.ARCHIVES -> Color(0xFFF29900)
        Category.RECENT -> Color(0xFF546E7A)
    }

val DownloadColor = Color(0xFF2D9CDB)
val CloudColor = Color(0xFF42A5F5)
val OtherColor = Color(0xFF9AA4B1)
