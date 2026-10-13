package com.tonoyama.filemanager.thumb

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tonoyama.filemanager.ui.fileVisual
import java.io.File

/**
 * サムネイル（なければ種類別アイコン）を表示する枠。
 * 大きさは modifier で決める（一覧なら 42dp 角、グリッドなら正方形いっぱい）。
 */
@Composable
fun ThumbBox(
    name: String,
    isDirectory: Boolean,
    selected: Boolean,
    thumbKey: String?,
    loader: (suspend () -> Bitmap?)?,
    modifier: Modifier,
    iconSize: Dp = 24.dp,
    corner: Dp = 12.dp,
) {
    val (icon, tint) = remember(name, isDirectory) { fileVisual(name, isDirectory) }
    val bitmap by produceState(
        initialValue = thumbKey?.let { ThumbnailLoader.cached(it) },
        key1 = thumbKey,
    ) {
        if (value == null && thumbKey != null && loader != null && !ThumbnailLoader.hasFailed(thumbKey)) {
            value = loader()
        }
    }
    val isVideo = remember(name) { ThumbnailLoader.kindOf(name) == ThumbKind.VIDEO }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(if (bitmap != null) Color.Black.copy(alpha = 0.05f) else tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isVideo) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(iconSize + 4.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(iconSize - 4.dp))
                }
            }
        } else {
            Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(iconSize))
            }
        }
    }
}

/** 端末内ファイルのサムネイル */
@Composable
fun LocalThumb(
    file: File,
    isDirectory: Boolean,
    modified: Long,
    selected: Boolean,
    modifier: Modifier,
    iconSize: Dp = 24.dp,
    thumbSize: Dp = 96.dp,
    corner: Dp = 12.dp,
) {
    val context = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { thumbSize.roundToPx() }.coerceIn(64, 512)
    val canThumb = !isDirectory && ThumbnailLoader.kindOf(file.name) != null
    val key = if (canThumb) ThumbnailLoader.keyFor(file, modified) else null
    val loader: (suspend () -> Bitmap?)? =
        if (canThumb) suspend { ThumbnailLoader.loadLocal(context, file, modified, px) } else null
    ThumbBox(
        name = file.name,
        isDirectory = isDirectory,
        selected = selected,
        thumbKey = key,
        loader = loader,
        modifier = modifier,
        iconSize = iconSize,
        corner = corner,
    )
}

/** 一覧の行に置く標準サイズ（42dp） */
@Composable
fun LocalThumbSmall(file: File, isDirectory: Boolean, modified: Long, selected: Boolean) {
    LocalThumb(file, isDirectory, modified, selected, Modifier.size(42.dp))
}
