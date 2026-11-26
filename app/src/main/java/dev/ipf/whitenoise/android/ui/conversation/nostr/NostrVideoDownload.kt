package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.sandbox.MAX_MEDIA_SOURCE_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_DISK_RESERVE_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.usableSpaceFor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// Players live only in this process, so every other process's directory is an orphan left by a kill.
private val processDownloadName = "process-" + UUID.randomUUID()

/** This process's private directory for referenced videos open in the player; no FileProvider path exposes it. */
internal fun nostrVideoDirectory(cacheDir: File): File = File(nostrVideoRoot(cacheDir), processDownloadName)

private fun nostrVideoRoot(cacheDir: File) = File(cacheDir, MediaCacheDirs.NOSTR_VIDEO)

/** Deletes downloads left by earlier processes at any age; safe while this process has a player open. */
internal fun sweepOrphanedNostrVideoFiles(cacheDir: File) {
    nostrVideoRoot(cacheDir)
        .listFiles()
        ?.filterNot { it.name == processDownloadName }
        ?.forEach { orphan -> runCatching { orphan.deleteRecursively() } }
}

/**
 * Downloads a referenced video to a private file so the isolated attachment decoder, not an app-process extractor,
 * parses the sender-chosen bytes. Only one public HTTPS resource is fetched: playlists are never expanded, and the
 * declared and streamed sizes stay within the decoder's input limit. Cancellation aborts the request. [directory] must
 * already exist and belong to one player: the player deletes it on disposal, so a download that finishes after the
 * player left cannot create a file there. The copy leaves the app's disk reserve free: a declared size that does
 * not fit is refused before writing, and an undeclared one stops when free space reaches the reserve. Returns null
 * when the source is refused or unavailable.
 */
internal suspend fun downloadNostrVideo(
    directory: File,
    url: String,
    client: OkHttpClient = nostrMediaHttpClient,
    maxBytes: Long = MAX_MEDIA_SOURCE_BYTES,
    usableBytes: () -> Long = { usableSpaceFor(directory) },
): File? =
    withContext(Dispatchers.IO) {
        val target = url.toHttpUrlOrNull()?.takeIf { it.isSafeMediaDestination() } ?: return@withContext null
        // Identity encoding keeps the declared length and the streamed bytes in the same representation.
        val request =
            Request
                .Builder()
                .url(target)
                .header("Accept-Encoding", "identity")
                .build()
        val call = client.newCall(request)
        // A blocking connect or read does not observe coroutine cancellation; this child aborts the call instead,
        // on the cancelling thread rather than waiting for a slot in the I/O pool the blocked call occupies.
        val canceller =
            launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        var file: File? = null
        var keep = false
        try {
            call.execute().use { response ->
                val body = response.body
                val encoded =
                    response
                        .headers("Content-Encoding")
                        .flatMap { it.split(',') }
                        .any { coding -> coding.isNotBlank() && !coding.trim().equals("identity", ignoreCase = true) }
                val declared = body.contentLength()
                if (!response.isSuccessful || encoded || declared > maxBytes) return@use
                if (declared > 0 && declared + FILE_BACKED_DISK_RESERVE_BYTES > usableBytes()) return@use
                val download = File.createTempFile("video-", ".bin", directory).also { file = it }
                body.byteStream().use { input ->
                    download.outputStream().use { output -> copyBounded(input, output, maxBytes, usableBytes) }
                }
                ensureActive()
                keep = download.length() > 0
            }
            file.takeIf { keep }
        } catch (_: IOException) {
            null
        } finally {
            canceller.cancel()
            if (!keep) file?.delete()
        }
    }

/**
 * Copies at most [maxBytes]; a longer stream is refused rather than truncated into a different file. Free space is
 * rechecked every few MiB, so an undeclared length cannot fill the volume past the app's reserve.
 */
private suspend fun copyBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
    usableBytes: () -> Long,
) {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var written = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) return
        written += read
        if (written > maxBytes) throw IOException("Referenced video exceeds the decoder input limit")
        if (written / SPACE_CHECK_BYTES != (written - read) / SPACE_CHECK_BYTES &&
            usableBytes() < FILE_BACKED_DISK_RESERVE_BYTES + read
        ) {
            throw IOException("Referenced video would fill the app's disk reserve")
        }
        output.write(buffer, 0, read)
    }
}

private const val COPY_BUFFER_BYTES = 64 * 1024
private const val SPACE_CHECK_BYTES = 4L * 1024 * 1024
