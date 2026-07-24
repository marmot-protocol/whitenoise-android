package dev.ipf.whitenoise.android.ui.conversation.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One recent gallery entry surfaced in the attachment-sheet strip. */
internal data class RecentMediaItem(
    val uri: Uri,
    val isVideo: Boolean,
)

/**
 * Runtime permissions to request for the recent-media strip. On Android 14+
 * (our minSdk) that's the granular media reads plus the partial-access grant,
 * so "Select photos" still populates the strip. No legacy READ_EXTERNAL_STORAGE.
 */
internal fun recentMediaReadPermissions(): Array<String> =
    arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )

/** Any of full-images/full-video/partial-visual grant is enough to read some recents. */
internal fun recentMediaGrantAllowsRead(grants: Map<String, Boolean>): Boolean =
    grants[Manifest.permission.READ_MEDIA_IMAGES] == true ||
        grants[Manifest.permission.READ_MEDIA_VIDEO] == true ||
        grants[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true

internal fun hasRecentMediaAccess(context: Context): Boolean =
    recentMediaReadPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

internal fun hasPartialRecentMediaAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    ) == PackageManager.PERMISSION_GRANTED &&
        !(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES,
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VIDEO,
                ) == PackageManager.PERMISSION_GRANTED
        )

/**
 * Most-recent images and videos from the shared store, newest first. Reads only
 * the id/type/date columns and builds per-item content URIs — no bulk copy.
 */
internal suspend fun queryRecentVisualMedia(
    context: Context,
    limit: Int,
): List<RecentMediaItem> =
    withContext(Dispatchers.IO) {
        runCatching {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection =
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                )
            val selection =
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
            val args =
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                        .toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        .toString(),
                )
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            val out = ArrayList<RecentMediaItem>(limit)
            context.contentResolver
                .query(collection, projection, selection, args, sortOrder)
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    while (cursor.moveToNext() && out.size < limit) {
                        val id = cursor.getLong(idCol)
                        val isVideo = cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        out += RecentMediaItem(ContentUris.withAppendedId(collection, id), isVideo)
                    }
                }
            out.toList()
        }.getOrDefault(emptyList())
    }

private const val RECENT_MEDIA_LIMIT = 24
private val ThumbPx = Size(256, 256)

@Composable
private fun rememberRecentThumbnail(uri: Uri): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap =
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(uri, ThumbPx, null) }.getOrNull()
            }
    }
    DisposableEffect(bitmap) {
        val decoded = bitmap
        onDispose { decoded?.recycle() }
    }
    return remember(bitmap) { bitmap?.asImageBitmap() }
}

/**
 * Horizontal strip of recent gallery items at the top of the attachment sheet.
 * Self-contained: it owns its permission request and off-main thumbnail loads.
 * A tap stages that item and opens the preview (where multi-select, removal,
 * caption, and send already live) — no extra send control on the sheet. If
 * access is denied it shows a one-tap opt-in rather than nagging on open; the
 * permission-free Gallery tile below always remains available.
 */
@Composable
internal fun RecentMediaStrip(
    onPick: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasRecentMediaAccess(context)) }
    var partialAccess by remember { mutableStateOf(hasPartialRecentMediaAccess(context)) }
    var items by remember { mutableStateOf<List<RecentMediaItem>>(emptyList()) }
    var refreshToken by remember { mutableIntStateOf(0) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            granted = recentMediaGrantAllowsRead(grants) || hasRecentMediaAccess(context)
            partialAccess =
                grants[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true ||
                hasPartialRecentMediaAccess(context)
            refreshToken++
        }

    DisposableEffect(lifecycleOwner, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = hasRecentMediaAccess(context)
                    partialAccess = hasPartialRecentMediaAccess(context)
                    refreshToken++
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(granted, refreshToken) {
        items = if (granted) queryRecentVisualMedia(context, RECENT_MEDIA_LIMIT) else emptyList()
    }

    when {
        granted && items.isNotEmpty() ->
            LazyRow(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.uri }) { item ->
                    RecentThumb(item = item, onClick = { onPick(item.uri) })
                }
                if (partialAccess) {
                    item(key = "recent_media_manage") {
                        TextButton(onClick = { permissionLauncher.launch(recentMediaReadPermissions()) }) {
                            Text(stringResource(R.string.recent_media_manage))
                        }
                    }
                }
            }
        !granted || partialAccess ->
            Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { permissionLauncher.launch(recentMediaReadPermissions()) }) {
                    Text(
                        stringResource(
                            if (partialAccess) R.string.recent_media_manage else R.string.recent_media_enable,
                        ),
                    )
                }
            }
        else -> Unit // granted but empty gallery — render nothing
    }
}

@Composable
private fun RecentThumb(
    item: RecentMediaItem,
    onClick: () -> Unit,
) {
    val description =
        stringResource(if (item.isVideo) R.string.reply_media_video else R.string.reply_media_photo)
    Box(
        modifier =
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
    ) {
        val bitmap = rememberRecentThumbnail(item.uri)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp),
            )
        }
        if (item.isVideo) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = ScrimAlpha.TILE)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
