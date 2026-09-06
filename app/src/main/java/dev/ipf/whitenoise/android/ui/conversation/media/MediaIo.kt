package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.type
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import dev.ipf.whitenoise.android.core.DiagnosticErrorMetadata
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Saves a nullable Uri across process death (camera capture round-trip). */
internal val NullableUriSaver: Saver<android.net.Uri?, String> =
    Saver(
        save = { it?.toString() ?: "" },
        restore = { s -> s.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse) },
    )

/**
 * Saves a nullable [java.io.File] across process death by its absolute path.
 * Used so the camera capture's temp-file handle survives the round-trip and a
 * capture cancelled after process death can still delete the empty temp
 * instead of leaking it (issue #531).
 */
internal val NullableFileSaver: Saver<java.io.File?, String> =
    Saver(
        save = { it?.absolutePath ?: "" },
        restore = { s -> s.takeIf { it.isNotEmpty() }?.let { path -> java.io.File(path) } },
    )

// Persist a multi-pick selection across rotation / process death. Empty list
// encodes "no preview shown" so the parent re-render skips the sheet on
// restore. Uses '\n' as the separator — content URIs don't contain newlines.
internal val UriListSaver: Saver<List<android.net.Uri>, String> =
    Saver(
        save = { encodeUriListTokens(it.map { uri -> uri.toString() }) },
        restore = { s -> decodeUriListTokens(s).map(android.net.Uri::parse) },
    )

/**
 * Pure string codec backing [UriListSaver], split out from the [android.net.Uri]
 * conversion so the separator and empty-list contract is unit-testable on the
 * JVM (the Android `Uri` stubs are non-functional in local unit tests). Joins
 * tokens with '\n'; an empty list encodes to "".
 */
internal fun encodeUriListTokens(tokens: List<String>): String = tokens.joinToString("\n")

/**
 * Inverse of [encodeUriListTokens]. An empty input decodes to an empty list
 * (the "no preview shown" sentinel); blank tokens are dropped so a trailing or
 * doubled separator can't yield empty URI strings.
 */
internal fun decodeUriListTokens(encoded: String): List<String> =
    if (encoded.isEmpty()) {
        emptyList()
    } else {
        encoded.split('\n').filter { it.isNotEmpty() }
    }

/** Returns and promotes a still-present plaintext cache entry, or null after eviction. */
internal fun validatedAttachmentCacheFile(file: java.io.File?): java.io.File? =
    file
        ?.takeIf { it.isFile && it.length() > 0L }
        ?.also(AttachmentPlaintextCache::touch)

private val documentMaterializations = SingleFlight<String, java.io.File>()
private const val MAX_DOCUMENT_EXTENSION_LENGTH = 12
internal const val ANDROID_PACKAGE_MIME = "application/vnd.android.package-archive"
internal const val GENERIC_BINARY_MIME = "application/octet-stream"
private val ANDROID_PACKAGE_CONTAINER_MIMES =
    setOf(
        "application/zip",
        "application/x-zip-compressed",
    )
private const val MEDIA_STORE_INSERT_ATTEMPTS = 2
private const val MEDIA_STORE_INSERT_RETRY_DELAY_MILLIS = 150L

internal enum class AttachmentSaveStage {
    MEDIASTORE_INSERT,
    MEDIASTORE_OPEN,
    MEDIASTORE_WRITE,
    MEDIASTORE_FINALIZE,
    DOCUMENT_DESTINATION_OPEN,
    DOCUMENT_DESTINATION_WRITE,
}

internal class AttachmentSaveException(
    val stage: AttachmentSaveStage,
    cause: Throwable? = null,
) : java.io.IOException("attachment save failed at ${stage.name}", cause),
    DiagnosticErrorMetadata {
    override val diagnosticErrorCode: String = "IO"
    override val diagnosticTechnicalDetail: String = "stage=${stage.name}"
}

/**
 * Materialize a general document once into the bounded shared-media cache.
 * Opening and saving both reuse this complete artifact, avoiding a second
 * multi-megabyte byte-array-to-file copy after an attachment was downloaded.
 */
@VisibleForTesting
internal suspend fun materializeDocumentAttachment(
    context: Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: dev.ipf.marmotkit.MediaAttachmentReferenceFfi,
    resolveBytes: suspend () -> ByteArray,
): java.io.File {
    val file =
        withContext(Dispatchers.IO) {
            documentAttachmentCacheFile(context, messageIdHex, attachmentIndex, reference)
        }
    val attachmentKey =
        AttachmentCachePublication.attachmentKey(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            sourceEpoch = reference.sourceEpoch,
        )
    return documentMaterializations.run(file.absolutePath) {
        withContext(Dispatchers.IO) {
            validatedAttachmentCacheFile(file)
        } ?: run {
            val published =
                AttachmentCachePublication.publishAfterLoad(
                    attachmentKey = attachmentKey,
                    finalFile = file,
                    loadBytes = resolveBytes,
                )
            if (!published) {
                throw java.io.IOException("attachment cache publication aborted for ${file.name}")
            }
            file
        }
    }
}

/** Publishes a closeable document source and reuses a complete stable viewer file. */
internal suspend fun materializeDocumentAttachmentSource(
    context: Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: dev.ipf.marmotkit.MediaAttachmentReferenceFfi,
    resolveSource: suspend () -> AttachmentPlaintext,
): java.io.File {
    val file =
        withContext(Dispatchers.IO) {
            documentAttachmentCacheFile(context, messageIdHex, attachmentIndex, reference)
        }
    val attachmentKey =
        AttachmentCachePublication.attachmentKey(messageIdHex, attachmentIndex, reference.sourceEpoch)
    return documentMaterializations.run(file.absolutePath) {
        withContext(Dispatchers.IO) { validatedAttachmentCacheFile(file) } ?: run {
            val published =
                AttachmentCachePublication.publishSourceAfterLoad(
                    attachmentKey = attachmentKey,
                    finalFile = file,
                    loadSource = resolveSource,
                )
            if (!published) throw java.io.IOException("attachment cache publication aborted for ${file.name}")
            file
        }
    }
}

private fun documentAttachmentCacheFile(
    context: Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: dev.ipf.marmotkit.MediaAttachmentReferenceFfi,
): java.io.File {
    val digestInput =
        (
            "$messageIdHex\u0000$attachmentIndex\u0000${reference.plaintextSha256}\u0000" +
                "${reference.ciphertextSha256}\u0000${reference.sourceEpoch}"
        ).toByteArray()
    val digest =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(digestInput)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    val extension = documentAttachmentExtension(reference.fileName, reference.mediaType)
    return java.io.File(
        java.io.File(context.cacheDir, MediaCacheDirs.SHARED),
        "document_$digest.$extension",
    )
}

private fun documentAttachmentExtension(
    fileName: String,
    mediaType: String,
): String {
    val fromName =
        MediaPipeline
            .safeDisplayName(fileName)
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { candidate ->
                candidate.length in 1..MAX_DOCUMENT_EXTENSION_LENGTH && candidate.all { it.isLetterOrDigit() }
            }
    if (fromName != null) return fromName
    return android.webkit.MimeTypeMap
        .getSingleton()
        .getExtensionFromMimeType(mediaType.lowercase())
        ?.takeIf { it.length in 1..MAX_DOCUMENT_EXTENSION_LENGTH && it.all(Char::isLetterOrDigit) }
        ?: "bin"
}

/**
 * Dispatch an already materialized [source] via the app's FileProvider.
 * Verified APKs use the dedicated package-install action; general files keep
 * the platform view action. The stable source is reused by Save and later taps.
 *
 * Distinguishes "no app claims this MIME" ([OpenAttachmentResult.NoHandler])
 * from "we couldn't even try" ([OpenAttachmentResult.Error]) so the caller
 * can surface the right toast.
 *
 * `resolveActivity`/`queryIntentActivities` are intentionally NOT used to
 * pre-flight the launch: under Android 11+ package visibility they return
 * null for any handler whose package isn't declared in `<queries>`, even
 * when the activity exists and `startActivity` would launch it. Catching
 * `ActivityNotFoundException` from `startActivity` is the authoritative
 * "nothing handles this MIME" signal.
 *
 * Shared plaintext is trimmed oldest-first to a byte cap after every
 * publication and by the age-based startup janitor. It is not deleted on
 * screen exit because the external reader may still hold the FileProvider URI.
 */
internal suspend fun openAttachmentExternally(
    context: android.content.Context,
    source: java.io.File,
    mediaType: String,
    fileName: String,
    selfUpdateEnabled: Boolean = BuildConfig.SELF_UPDATE_ENABLED,
    sdkInt: Int = Build.VERSION.SDK_INT,
    canRequestPackageInstalls: () -> Boolean = {
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    },
    dispatchGuard: AttachmentDispatchGuard? = null,
): OpenAttachmentResult =
    withContext(Dispatchers.IO) { validatedAttachmentCacheFile(source) }
        ?.let { completeSource ->
            val classification =
                withContext(Dispatchers.IO) {
                    classifyAttachmentOpen(
                        mediaType = mediaType,
                        fileName = fileName,
                        isValidAndroidPackage = { isValidAndroidPackageArchive(completeSource) },
                    )
                }
            when (classification) {
                is AttachmentOpenClassification.Ready ->
                    openReadyAttachment(
                        context = context,
                        source = completeSource,
                        mediaType = classification.mediaType,
                        selfUpdateEnabled = selfUpdateEnabled,
                        sdkInt = sdkInt,
                        canRequestPackageInstalls = canRequestPackageInstalls,
                        dispatchGuard = dispatchGuard,
                    )
                AttachmentOpenClassification.InvalidAndroidPackage -> OpenAttachmentResult.InvalidPackage
            }
        }
        ?: OpenAttachmentResult.MissingArtifact

private suspend fun openReadyAttachment(
    context: Context,
    source: java.io.File,
    mediaType: String,
    selfUpdateEnabled: Boolean,
    sdkInt: Int,
    canRequestPackageInstalls: () -> Boolean,
    dispatchGuard: AttachmentDispatchGuard?,
): OpenAttachmentResult =
    when {
        dispatchGuard?.canDispatch?.invoke() == false -> OpenAttachmentResult.DestinationNotVisible
        mediaType == ANDROID_PACKAGE_MIME && !selfUpdateEnabled -> OpenAttachmentResult.InstallUnsupported
        requiresAndroidPackageInstallPermission(
            mediaType = mediaType,
            selfUpdateEnabled = selfUpdateEnabled,
            sdkInt = sdkInt,
            canRequestPackageInstalls = canRequestPackageInstalls,
        ) -> OpenAttachmentResult.InstallPermissionRequired
        else ->
            try {
                val uri = withContext(Dispatchers.IO) { fileProviderUri(context.applicationContext, source) }
                launchAttachmentIntent(context, uri, mediaType, dispatchGuard)
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                OpenAttachmentResult.SecurityFailure
            } catch (_: IllegalArgumentException) {
                OpenAttachmentResult.Error
            } catch (_: RuntimeException) {
                OpenAttachmentResult.Error
            }
    }

private fun launchAttachmentIntent(
    context: Context,
    uri: Uri,
    mediaType: String,
    dispatchGuard: AttachmentDispatchGuard?,
): OpenAttachmentResult {
    if (dispatchGuard?.canDispatch?.invoke() == false) {
        return OpenAttachmentResult.DestinationNotVisible.also(dispatchGuard.onPlatformDispatchResult)
    }
    val intent = attachmentOpenIntent(uri, mediaType)
    dispatchGuard?.onPlatformDispatchStarted?.invoke()
    return (
        try {
            context.startActivity(intent)
            OpenAttachmentResult.Opened
        } catch (error: CancellationException) {
            throw error
        } catch (_: ActivityNotFoundException) {
            if (mediaType == ANDROID_PACKAGE_MIME) {
                OpenAttachmentResult.NoInstaller
            } else {
                OpenAttachmentResult.NoHandler
            }
        } catch (_: SecurityException) {
            // FileProvider grant rejected, or target activity has no permission
            // to access this URI for some reason. Surfacing this as a generic
            // error is more useful than crashing.
            OpenAttachmentResult.SecurityFailure
        } catch (_: IllegalArgumentException) {
            OpenAttachmentResult.Error
        } catch (_: RuntimeException) {
            OpenAttachmentResult.Error
        }
    ).also { dispatchGuard?.onPlatformDispatchResult?.invoke(it) }
}

internal fun attachmentOpenIntent(
    uri: Uri,
    mediaType: String,
): Intent =
    Intent(
        if (mediaType == ANDROID_PACKAGE_MIME) {
            Intent.ACTION_INSTALL_PACKAGE
        } else {
            Intent.ACTION_VIEW
        },
    ).apply {
        setDataAndType(uri, mediaType)
        clipData = ClipData.newRawUri("attachment", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

internal sealed interface AttachmentOpenClassification {
    data class Ready(
        val mediaType: String,
    ) : AttachmentOpenClassification

    data object InvalidAndroidPackage : AttachmentOpenClassification
}

/**
 * Identifies received attachments that may become APKs after verification.
 * Conflicting specific MIME metadata stays on the ordinary viewer path.
 */
internal fun isAndroidPackageOpenCandidate(
    mediaType: String,
    fileName: String,
): Boolean {
    val normalizedMime =
        mediaType
            .substringBefore(';')
            .trim()
            .lowercase(java.util.Locale.ROOT)
    return normalizedMime == ANDROID_PACKAGE_MIME ||
        (
            (
                normalizedMime.isEmpty() ||
                    normalizedMime == GENERIC_BINARY_MIME ||
                    normalizedMime in ANDROID_PACKAGE_CONTAINER_MIMES
            ) &&
                hasAndroidPackageExtension(fileName)
        )
}

/**
 * Resolve the MIME used for external dispatch only after the attachment has
 * been materialized and verified by the transfer pipeline. A remote filename
 * can refine blank/octet-stream metadata to APK only when its sanitized
 * basename ends in `.apk` and the artifact is an APK-shaped ZIP containing an
 * Android manifest. Known ZIP-container aliases are eligible because Android's
 * document picker reports APKs with those MIME values; other specific metadata
 * always wins.
 */
internal fun classifyAttachmentOpen(
    mediaType: String,
    fileName: String,
    isValidAndroidPackage: () -> Boolean,
): AttachmentOpenClassification {
    val normalizedMime =
        mediaType
            .substringBefore(';')
            .trim()
            .lowercase(java.util.Locale.ROOT)
    val openMime = mediaType.trim().ifBlank { GENERIC_BINARY_MIME }
    return when {
        normalizedMime == ANDROID_PACKAGE_MIME -> AttachmentOpenClassification.Ready(ANDROID_PACKAGE_MIME)
        (
            normalizedMime.isEmpty() ||
                normalizedMime == GENERIC_BINARY_MIME ||
                normalizedMime in ANDROID_PACKAGE_CONTAINER_MIMES
        ) &&
            hasAndroidPackageExtension(fileName) ->
            if (isValidAndroidPackage()) {
                AttachmentOpenClassification.Ready(ANDROID_PACKAGE_MIME)
            } else {
                AttachmentOpenClassification.InvalidAndroidPackage
            }
        else -> AttachmentOpenClassification.Ready(openMime)
    }
}

private fun hasAndroidPackageExtension(fileName: String): Boolean =
    MediaPipeline
        .safeDisplayName(fileName)
        .substringAfterLast('.', "")
        .equals("apk", ignoreCase = true)

internal fun isValidAndroidPackageArchive(source: java.io.File): Boolean =
    runCatching {
        java.util.zip.ZipFile(source).use { archive ->
            val manifest = archive.getEntry("AndroidManifest.xml")?.takeUnless { it.isDirectory } ?: return@use false
            val header = ByteArray(ANDROID_BINARY_XML_HEADER_BYTES)
            java.io.DataInputStream(archive.getInputStream(manifest)).use { input ->
                input.readFully(header)
            }
            val declaredSize =
                (header[4].toLong() and 0xffL) or
                    ((header[5].toLong() and 0xffL) shl 8) or
                    ((header[6].toLong() and 0xffL) shl 16) or
                    ((header[7].toLong() and 0xffL) shl 24)
            header[0] == 0x03.toByte() &&
                header[1] == 0x00.toByte() &&
                header[2] == 0x08.toByte() &&
                header[ANDROID_BINARY_XML_VERSION_HIGH_INDEX] == 0x00.toByte() &&
                declaredSize == manifest.size
        }
    }.getOrDefault(false)

private const val ANDROID_BINARY_XML_HEADER_BYTES = 8
private const val ANDROID_BINARY_XML_VERSION_HIGH_INDEX = 3

internal fun requiresAndroidPackageInstallPermission(
    mediaType: String,
    selfUpdateEnabled: Boolean,
    sdkInt: Int,
    canRequestPackageInstalls: () -> Boolean,
): Boolean =
    mediaType.equals(ANDROID_PACKAGE_MIME, ignoreCase = true) &&
        selfUpdateEnabled &&
        sdkInt >= Build.VERSION_CODES.O &&
        !canRequestPackageInstalls()

/**
 * Persist [bytes] to the device gallery (Pictures/White Noise). Returns success.
 * Uses the IS_PENDING dance so other apps never see a half-written entry, and
 * sanitizes the remote-supplied [fileName] to a basename.
 */
internal fun saveImageToGallery(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, MediaPipeline.safeDisplayName(fileName))
            // Preserve the attachment's real MIME (a peer may send PNG/WebP/HEIC),
            // so gallery indexing matches the actual bytes.
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mediaType.ifBlank { MediaPipeline.RECOMPRESSED_MIME })
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/White Noise")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
    return publishMediaStoreEntry(
        resolver = resolver,
        collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values = values,
    ) { output -> output.write(bytes) }
}

/** Persist a decrypted video to the public Movies/White Noise folder via the
 *  Video MediaStore so it shows up in the system gallery. Mirrors the image
 *  save flow's IS_PENDING dance. */
internal fun saveVideoToGallery(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean = saveVideoToGallery(context, bytes.inputStream(), fileName, mediaType)

internal fun saveVideoToGallery(
    context: android.content.Context,
    source: java.io.File,
    fileName: String,
    mediaType: String,
): Boolean = source.inputStream().use { saveVideoToGallery(context, it, fileName, mediaType) }

/** Save a decrypted attachment to the public collection appropriate for its MIME type. */
internal fun saveAttachmentToMediaStore(
    context: Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean =
    when {
        mediaType.startsWith("image/", ignoreCase = true) ->
            saveImageToGallery(context, bytes, fileName, mediaType)
        mediaType.startsWith("video/", ignoreCase = true) ->
            saveVideoToGallery(context, bytes, fileName, mediaType)
        else -> saveFileToDownloads(context, bytes, fileName, mediaType)
    }

/** Stream a reusable general-file artifact into public Downloads. */
internal fun saveDocumentToDownloads(
    context: Context,
    source: java.io.File,
    fileName: String,
    mediaType: String,
): Boolean = source.inputStream().use { saveFileToDownloads(context, it, fileName, mediaType) }

/** Persist an arbitrary attachment to Download/White Noise via MediaStore. */
private fun saveFileToDownloads(
    context: Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean = saveFileToDownloads(context, bytes.inputStream(), fileName, mediaType)

private fun saveFileToDownloads(
    context: Context,
    source: java.io.InputStream,
    fileName: String,
    mediaType: String,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, MediaPipeline.safeDisplayName(fileName))
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mediaType.ifBlank { "application/octet-stream" })
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/White Noise")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
    return publishMediaStoreEntry(
        resolver = resolver,
        collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        values = values,
    ) { output -> source.copyTo(output, DEFAULT_BUFFER_SIZE) }
}

private fun saveVideoToGallery(
    context: android.content.Context,
    source: java.io.InputStream,
    fileName: String,
    mediaType: String,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, MediaPipeline.safeDisplayName(fileName))
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, mediaType.ifBlank { "video/mp4" })
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/White Noise")
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
    return publishMediaStoreEntry(
        resolver = resolver,
        collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        values = values,
    ) { output -> source.copyTo(output, DEFAULT_BUFFER_SIZE) }
}

private inline fun publishMediaStoreEntry(
    resolver: android.content.ContentResolver,
    collection: Uri,
    values: android.content.ContentValues,
    write: (java.io.OutputStream) -> Unit,
): Boolean {
    val uri =
        retryNullableMediaStoreInsert {
            resolver.insert(collection, values)
        }
    return try {
        val output =
            attachmentSaveStage(AttachmentSaveStage.MEDIASTORE_OPEN) {
                requireAttachmentOutputStream(resolver.openOutputStream(uri))
            }
        attachmentSaveStage(AttachmentSaveStage.MEDIASTORE_WRITE) {
            output.use(write)
        }
        attachmentSaveStage(AttachmentSaveStage.MEDIASTORE_FINALIZE) {
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            requireMediaStoreFinalized(resolver.update(uri, values, null, null))
        }
        true
    } catch (failure: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw failure
    }
}

private fun requireAttachmentOutputStream(output: java.io.OutputStream?): java.io.OutputStream =
    output ?: throw java.io.IOException("MediaStore returned no output stream")

private fun requireMediaStoreFinalized(updatedRows: Int) {
    if (updatedRows <= 0) throw java.io.IOException("MediaStore finalization failed")
}

@VisibleForTesting
internal fun <T : Any> retryNullableMediaStoreInsert(
    attempts: Int = MEDIA_STORE_INSERT_ATTEMPTS,
    onRetry: () -> Unit = { Thread.sleep(MEDIA_STORE_INSERT_RETRY_DELAY_MILLIS) },
    insert: () -> T?,
): T {
    require(attempts > 0)
    repeat(attempts) { attempt ->
        val value =
            attachmentSaveStage(AttachmentSaveStage.MEDIASTORE_INSERT) {
                insert()
            }
        if (value != null) return value
        if (attempt < attempts - 1) {
            attachmentSaveStage(AttachmentSaveStage.MEDIASTORE_INSERT, onRetry)
        }
    }
    throw AttachmentSaveException(AttachmentSaveStage.MEDIASTORE_INSERT)
}

private inline fun <T> attachmentSaveStage(
    stage: AttachmentSaveStage,
    block: () -> T,
): T =
    try {
        block()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (failure: AttachmentSaveException) {
        throw failure
    } catch (failure: Throwable) {
        throw AttachmentSaveException(stage, failure)
    }

internal fun copyDocumentToDestination(
    context: Context,
    source: java.io.File,
    destination: Uri,
) {
    val output =
        attachmentSaveStage(AttachmentSaveStage.DOCUMENT_DESTINATION_OPEN) {
            context.contentResolver.openOutputStream(destination, "w")
                ?: throw java.io.IOException("document provider returned no output stream")
        }
    attachmentSaveStage(AttachmentSaveStage.DOCUMENT_DESTINATION_WRITE) {
        output.use { destinationStream ->
            source.inputStream().use { input -> input.copyTo(destinationStream, DEFAULT_BUFFER_SIZE) }
        }
    }
}

/** Stream an already-materialized video into a share-safe FileProvider temp. */
internal suspend fun shareVideo(
    context: android.content.Context,
    source: java.io.File,
    fileName: String,
    mediaType: String,
): Result<Unit> =
    runCatchingCancellable {
        val uri =
            withContext(Dispatchers.IO) {
                val dir = java.io.File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
                val file = java.io.File.createTempFile("share_", "_" + MediaPipeline.safeDisplayName(fileName), dir)
                writeSharedMediaFile(file, source)
                fileProviderUri(context, file)
            }
        launchVideoShare(context, uri, mediaType).getOrThrow()
    }

internal fun launchVideoShare(
    context: android.content.Context,
    uri: Uri,
    mediaType: String,
): Result<Unit> =
    runCatchingCancellable {
        val intent =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mediaType.ifBlank { "video/mp4" }
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            android.content.Intent.createChooser(intent, null).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

/**
 * Share [bytes] via a FileProvider Uri using the system share sheet.
 *
 * Suspends because the temp-file write is multi-megabyte for any non-trivial
 * attachment; doing it on the main dispatcher would stall the UI for the
 * write. The `startActivity` call has to run on Main, so the I/O is hopped
 * to `Dispatchers.IO` and the chooser is fired back on Main. Failures are
 * returned so each owning surface can apply its own user-visible contract.
 */
internal suspend fun shareImage(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Result<Unit> =
    runCatchingCancellable {
        val uri =
            withContext(Dispatchers.IO) {
                val dir = java.io.File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
                // Unique temp keyed off a sanitized basename — avoids
                // collisions and path traversal from a remote-supplied
                // filename.
                val file = java.io.File.createTempFile("share_", "_" + MediaPipeline.safeDisplayName(fileName), dir)
                writeSharedMediaFile(file, bytes)
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }
        launchImageShare(context, uri, mediaType).getOrThrow()
    }

internal fun launchImageShare(
    context: android.content.Context,
    uri: Uri,
    mediaType: String,
): Result<Unit> =
    runCatchingCancellable {
        val shareIntent =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mediaType.ifBlank { MediaPipeline.RECOMPRESSED_MIME }
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            android.content.Intent.createChooser(shareIntent, null).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

/** Expected missing-handler outcomes stay non-diagnostic; operational failures retain their cause. */
internal fun WhiteNoiseAppState.presentMediaLaunchFailure(
    @StringRes titleRes: Int,
    operationCode: String,
    throwable: Throwable,
) {
    if (throwable is ActivityNotFoundException) {
        present(titleRes)
    } else {
        presentFailure(titleRes, operationCode, throwable)
    }
}

@Throws(java.io.IOException::class)
private fun writeSharedMediaFile(
    file: java.io.File,
    bytes: ByteArray,
) {
    var protected = false
    try {
        AttachmentPlaintextCache.requireEntryWithinLimit(file, bytes.size.toLong())
        AttachmentPlaintextCache.protectPublicationFile(file)
        protected = true
        file.outputStream().use { it.write(bytes) }
        AttachmentPlaintextCache.finishPublication(file)
        protected = false
    } catch (failure: Throwable) {
        if (protected) AttachmentPlaintextCache.unprotectPublicationFile(file)
        runCatching { file.delete() }
        throw failure
    }
}

@Throws(java.io.IOException::class)
private fun writeSharedMediaFile(
    file: java.io.File,
    source: java.io.File,
) {
    var sourceProtected = false
    var destinationProtected = false
    try {
        AttachmentPlaintextCache.protectPublicationFile(source)
        sourceProtected = true
        val sourceBytes = source.takeIf { it.isFile }?.length()?.takeIf { it > 0L } ?: throw java.io.IOException("missing video source")
        AttachmentPlaintextCache.requireEntryWithinLimit(file, sourceBytes)
        AttachmentPlaintextCache.protectPublicationFile(file)
        destinationProtected = true
        source.inputStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        AttachmentPlaintextCache.finishPublication(file)
        destinationProtected = false
    } catch (failure: Throwable) {
        if (destinationProtected) AttachmentPlaintextCache.unprotectPublicationFile(file)
        runCatching { file.delete() }
        throw failure
    } finally {
        if (sourceProtected) AttachmentPlaintextCache.unprotectPublicationFile(source)
    }
}

/** Create a cache file for a camera capture. Returns null if it can't be made. */
internal fun createImageCaptureFile(context: android.content.Context): java.io.File? =
    try {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        java.io.File.createTempFile("capture_", ".jpg", dir)
    } catch (_: Throwable) {
        null
    }

/**
 * Copy a receive-content image into app-owned cache while the platform's
 * transient URI read grant is still active.
 *
 * Clipboard/IME providers may revoke the grant as soon as the receive-content
 * callback returns. The composer staging shelf previews and sends later, so it
 * must hold a local FileProvider URI rather than the provider's raw URI.
 */
internal fun materializeReceiveContentImageUri(
    context: android.content.Context,
    uri: android.net.Uri,
    maxBytes: Long = ConversationController.MEDIA_RETAINED_MAX_BYTES,
): android.net.Uri? =
    runCatching {
        val resolver = context.contentResolver
        val suffix = receiveContentImageCacheSuffix(safeGetType(resolver, uri))
        val dir = java.io.File(context.cacheDir, MediaCacheDirs.COMPOSER_PASTE).apply { mkdirs() }
        val file = java.io.File.createTempFile("paste_", suffix, dir)
        val copied =
            resolver.openInputStream(uri)?.use { input ->
                MediaPipeline.copyStreamToFileWithinCap(input, file, maxBytes)
            } == true
        if (!copied || file.length() <= 0L) {
            runCatching { file.delete() }
            null
        } else {
            fileProviderUri(context, file)
        }
    }.getOrNull()

internal fun receiveContentImageCacheSuffix(resolvedMime: String): String {
    val extension =
        when {
            resolvedMime.equals("image/jpeg", ignoreCase = true) -> "jpg"
            resolvedMime.startsWith("image/", ignoreCase = true) ->
                android.webkit.MimeTypeMap
                    .getSingleton()
                    .getExtensionFromMimeType(resolvedMime.lowercase())
            else -> null
        }?.takeIf { candidate ->
            candidate.length in 1..8 && candidate.all { it.isLetterOrDigit() }
        } ?: "img"
    return ".$extension"
}

internal fun fileProviderUri(
    context: android.content.Context,
    file: java.io.File,
): android.net.Uri =
    androidx.core.content.FileProvider
        .getUriForFile(context, "${context.packageName}.fileprovider", file)

/**
 * Best-effort wipe of decrypted camera-capture temp files from cache.
 *
 * Intentionally does NOT touch `shared_media`. Those entries back live
 * FileProvider URIs the system may still be reading after the user backs
 * out of a chat (an external PDF reader holding the granted URI, the
 * system share-sheet target, etc.). Yanking the file out from under
 * those readers caused the "opened PDF goes blank when I leave the chat"
 * class of bug — the [sweepStaleSharedMedia] janitor cleans those on a
 * stale-age basis at app start instead.
 */
internal fun clearMediaTempFiles(context: android.content.Context) {
    runCatching { java.io.File(context.cacheDir, "camera").deleteRecursively() }
    runCatching { java.io.File(context.cacheDir, MediaCacheDirs.COMPOSER_PASTE).deleteRecursively() }
}

/**
 * Delete `shared_media` files older than [maxAgeMillis]. Called once at
 * app start so transient FileProvider temps for opened/shared
 * attachments don't accumulate across sessions, without racing the
 * external readers that may still be using them in the current session.
 */
internal fun sweepStaleSharedMedia(
    context: android.content.Context,
    maxAgeMillis: Long,
) {
    runCatching {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        // Same age-based reaper covers the decrypted voice cache too —
        // those bytes are plaintext E2EE-decrypted audio and shouldn't
        // linger past the last MediaPlayer that opened them.
        listOf(
            MediaCacheDirs.SHARED,
            ConversationTranscriptExport.CacheDirName,
            MediaCacheDirs.VOICE,
            MediaCacheDirs.VIDEO,
            MediaCacheDirs.COMPOSER_PASTE,
        ).forEach { name ->
            val dir = java.io.File(context.cacheDir, name)
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { entry ->
                if (entry.isFile && entry.lastModified() < cutoff) {
                    runCatching { entry.delete() }
                }
            }
        }
        AttachmentPlaintextCache.trimKnownDirectories(context.cacheDir)
    }
}

/** Files in `shared_media` older than this are considered safe to delete —
 *  any external reader has had ample time to finish loading the bytes. */
internal const val SHARED_MEDIA_MAX_AGE_MS: Long = 10L * 60L * 1000L

/** Read the user-visible filename a content Uri exposes via OpenableColumns,
 *  falling back to the Uri's path segment. Null when neither is available.
 *
 *  Guarded against a revoked grant: a Photo Picker / SAF Uri staged before
 *  process death (issue #531) comes back as a ghost whose session-scoped read
 *  permission is gone, so `query()` throws `SecurityException` (or the backing
 *  provider may be dead — `IllegalArgumentException` / `NullPointerException`).
 *  We swallow it and fall through to the path-segment fallback so the staging
 *  preview renders a placeholder name instead of crashing; the actual decode
 *  still fails gracefully into the existing toast path. */
internal fun queryDisplayName(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String? {
    runCatching {
        contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
    }
    return uri.lastPathSegment
}

/**
 * Best-effort byte size of a content Uri, queried via `OpenableColumns.SIZE`.
 * Returns -1 when the provider doesn't report a size (some virtual / streamed
 * providers omit it); callers must then enforce a cap via the bounded read.
 *
 * Also returns -1 when the Uri's grant has been revoked (a ghost Uri restored
 * after process death — see [queryDisplayName] / issue #531): the bounded read
 * downstream is itself `SecurityException`-guarded and will reject the file, so
 * treating a revoked grant as "size unknown" routes it into the same graceful
 * rejection rather than crashing the send coroutine.
 */
internal fun queryContentSize(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): Long {
    runCatching {
        contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getLong(0)
                }
            }
    }
    return -1L
}

/** `ContentResolver.getType` for a content Uri whose read grant may have been
 *  revoked (a ghost staging Uri restored after process death — issue #531).
 *  The platform docs say `getType` can throw `SecurityException` for a Uri the
 *  caller can no longer access; an unguarded call on a ghost Uri crashes the
 *  preview composition or the send coroutine before the already-guarded decode
 *  gets a chance to degrade. Returns "" on any failure so callers treat the
 *  ghost as an unknown / non-video type and let the guarded decode reject it
 *  into the existing decode-failure toast. */
internal fun safeGetType(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String = coerceResolvedMime { contentResolver.getType(uri) }

/** Pure swallow-and-default kernel behind [safeGetType], split out so the
 *  ghost-Uri contract (issue #531) — a throwing or null resolver lookup must
 *  collapse to "" rather than propagate — is unit-testable on the JVM without
 *  Robolectric, mirroring the `UriListSaver` codec split. */
internal inline fun coerceResolvedMime(getType: () -> String?): String = runCatching(getType).getOrNull().orEmpty()

/**
 * Predicate for image payloads delivered through Compose's receive-content path.
 *
 * Prefer the resolver's concrete MIME when available: it is per-Uri and catches
 * mixed clip payloads. Fall back to the clip's declared image MIME only when
 * the resolver is silent/guarded, which keeps text/document paste flowing to
 * the text field while still accepting clipboard providers that expose only a
 * clip-level image description.
 */
internal fun receiveContentMimeIsImage(
    resolvedMime: String,
    clipDeclaresImage: Boolean,
): Boolean =
    resolvedMime.startsWith("image/", ignoreCase = true) ||
        (resolvedMime.isBlank() && clipDeclaresImage)

internal fun receiveContentImageUriOrNull(
    item: ClipData.Item,
    clipDescription: ClipDescription?,
    resolveMime: (Uri) -> String?,
): Uri? {
    val clipDeclaresImage = clipDescription?.hasMimeType("image/*") == true
    return receiveContentImageValueOrNull(item.uri, clipDeclaresImage, resolveMime)
}

internal fun <T> receiveContentImageValueOrNull(
    value: T?,
    clipDeclaresImage: Boolean,
    resolveMime: (T) -> String?,
): T? {
    val nonNullValue = value ?: return null
    val resolvedMime = coerceResolvedMime { resolveMime(nonNullValue) }
    return nonNullValue.takeIf { receiveContentMimeIsImage(resolvedMime, clipDeclaresImage) }
}
