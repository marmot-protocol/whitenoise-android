@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.VisibleForTesting
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.SharedPlaintextWrite
import dev.ipf.whitenoise.android.media.captureSharedPlaintextWrite
import dev.ipf.whitenoise.android.media.sharedPlaintextPrefix
import dev.ipf.whitenoise.android.media.withSharedPlaintextWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private val documentMaterializations = SingleFlight<String, java.io.File>()
private val documentGrantNamespace = java.util.UUID.randomUUID()
private const val MAX_DOCUMENT_EXTENSION_LENGTH = 12
internal const val ANDROID_PACKAGE_MIME = "application/vnd.android.package-archive"
internal const val GENERIC_BINARY_MIME = "application/octet-stream"
private val ANDROID_PACKAGE_CONTAINER_MIMES =
    setOf(
        "application/zip",
        "application/x-zip-compressed",
    )

/**
 * Types a browser or viewer may render as live content. Opening one runs its scripts and fetches its remote
 * resources, which tells the sender when, and from which IP address, the attachment was opened. These open as
 * text; saving to Downloads keeps the original type for deliberate use.
 */
private val ACTIVE_CONTENT_MIMES =
    setOf(
        "text/html",
        "application/xhtml+xml",
        "image/svg+xml",
        "text/xml",
        "application/xml",
        "text/javascript",
        "application/javascript",
        "application/x-javascript",
        "application/ecmascript",
        "text/ecmascript",
        "message/rfc822",
        "multipart/related",
        "application/x-mimearchive",
    )
private val ACTIVE_CONTENT_EXTENSIONS =
    setOf(
        "htm",
        "html",
        "shtml",
        "xht",
        "xhtml",
        "svg",
        "svgz",
        "xml",
        "xsl",
        "xslt",
        "xsd",
        "rss",
        "atom",
        "eml",
        "js",
        "mjs",
        "mht",
        "mhtml",
        "hta",
    )
private const val INERT_TEXT_MIME = "text/plain"
private const val INERT_TEXT_EXTENSION = "txt"

/**
 * Materialize a general document once into the bounded shared-media cache.
 * Opening and saving both reuse this complete artifact, avoiding a second
 * multi-megabyte byte-array-to-file copy after an attachment was downloaded.
 */
@VisibleForTesting
internal suspend fun materializeDocumentAttachment(
    context: Context,
    accountRef: String,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: dev.ipf.marmotkit.MediaAttachmentReferenceFfi,
    resolveBytes: suspend () -> ByteArray,
): java.io.File =
    materializeDocumentAttachmentSource(context, accountRef, messageIdHex, attachmentIndex, reference) {
        AttachmentPlaintext.Bytes(resolveBytes())
    }

/** Publishes a closeable document source and reuses a complete stable viewer file. */
internal suspend fun materializeDocumentAttachmentSource(
    context: Context,
    accountRef: String,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: dev.ipf.marmotkit.MediaAttachmentReferenceFfi,
    resolveSource: suspend () -> AttachmentPlaintext,
): java.io.File {
    val writeGeneration = captureSharedPlaintextWrite(accountRef)
    val file =
        withContext(Dispatchers.IO) {
            documentAttachmentCacheFile(context, accountRef, messageIdHex, attachmentIndex, reference, writeGeneration)
        }
    val attachmentKey =
        AttachmentCachePublication.attachmentKey(messageIdHex, attachmentIndex, reference.sourceEpoch)
    return documentMaterializations.run(file.absolutePath) {
        withContext(Dispatchers.IO) {
            validatedAttachmentCacheFile(file) ?: run {
                val permit =
                    AttachmentCachePublication.capturePermit(attachmentKey)
                        ?: throw java.io.IOException("attachment cache publication unavailable")
                resolveSource().use { source ->
                    withSharedPlaintextWrite(writeGeneration) {
                        if (!AttachmentCachePublication.publishSourceWithPermit(attachmentKey, file, source, permit)) {
                            throw java.io.IOException("attachment cache publication aborted")
                        }
                        file
                    }
                }
            }
        }
    }
}

private fun documentAttachmentCacheFile(
    context: Context,
    accountRef: String,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: dev.ipf.marmotkit.MediaAttachmentReferenceFfi,
    writeGeneration: SharedPlaintextWrite,
): java.io.File {
    // A surviving OS grant to a previously evicted file cannot regain access when it is staged after a wipe or restart.
    val digestInput =
        (
            "$documentGrantNamespace\u0000$writeGeneration\u0000$messageIdHex\u0000$attachmentIndex\u0000" +
                "${reference.plaintextSha256}\u0000" +
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
        "${sharedPlaintextPrefix(context, accountRef)}document_$digest.$extension",
    )
}

private fun documentAttachmentExtension(
    fileName: String,
    mediaType: String,
): String {
    val fromName =
        safeDocumentDisplayName(fileName)
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { candidate ->
                candidate.length in 1..MAX_DOCUMENT_EXTENSION_LENGTH && candidate.all { it.isLetterOrDigit() }
            }
    val extension =
        fromName
            ?: android.webkit.MimeTypeMap
                .getSingleton()
                .getExtensionFromMimeType(normalizeDocumentMime(mediaType))
                ?.takeIf { it.length in 1..MAX_DOCUMENT_EXTENSION_LENGTH && it.all(Char::isLetterOrDigit) }
            ?: "bin"
    return inertDocumentExtension(extension, mediaType)
}

/**
 * FileProvider reports a cached document's type from its extension, so the cached copy must agree with the
 * inert open type: an active extension or an active sender type is cached as text, whichever one is present.
 */
@VisibleForTesting
internal fun inertDocumentExtension(
    extension: String,
    mediaType: String,
): String =
    if (isActiveContentExtension(extension) || isActiveContentMime(normalizeDocumentMime(mediaType))) {
        INERT_TEXT_EXTENSION
    } else {
        extension
    }

@VisibleForTesting
internal fun isActiveContentMime(mime: String): Boolean = mime in ACTIVE_CONTENT_MIMES || mime.endsWith("+xml")

/**
 * Whether opening apps see a file named with [extension] as active content: a listed extension, or one the
 * platform types as active, such as an XML dialect (`.xsd`, `.rss`) or a saved web archive. FileProvider reports
 * the platform type, so a short list alone misses those.
 */
@VisibleForTesting
internal fun isActiveContentExtension(
    extension: String,
    platformMime: (String) -> String? = ::platformMimeForExtension,
): Boolean {
    val normalized = extension.lowercase(java.util.Locale.ROOT)
    return normalized in ACTIVE_CONTENT_EXTENSIONS ||
        platformMime(normalized)?.let { isActiveContentMime(normalizeDocumentMime(it)) } == true
}

private fun platformMimeForExtension(extension: String): String? =
    runCatching {
        android.webkit.MimeTypeMap
            .getSingleton()
            .getMimeTypeFromExtension(extension)
    }.getOrNull()

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
    val openMime = normalizeDocumentMime(mediaType)
    return when {
        normalizedMime == ANDROID_PACKAGE_MIME ->
            if (isValidAndroidPackage()) {
                AttachmentOpenClassification.Ready(ANDROID_PACKAGE_MIME)
            } else {
                AttachmentOpenClassification.InvalidAndroidPackage
            }
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
        // The cached copy is named from the sanitized name, so classify that name: an invisible character in the raw
        // name must not separate the checked extension from the one the opening app sees.
        isActiveContentExtension(MediaPipeline.safeDisplayName(fileName).substringAfterLast('.', "")) ->
            AttachmentOpenClassification.Ready(INERT_TEXT_MIME)
        isActiveContentMime(openMime) -> AttachmentOpenClassification.Ready(INERT_TEXT_MIME)
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
