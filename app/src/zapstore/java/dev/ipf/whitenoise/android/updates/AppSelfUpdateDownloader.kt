package dev.ipf.whitenoise.android.updates

import dev.ipf.whitenoise.android.core.HostSafety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class AppSelfUpdateDownloader(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    suspend fun downloadVerifiedApk(
        asset: ZapstoreApkAsset,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val partial = File(destination.parentFile, "${destination.name}.part")
            AppSelfUpdateStorage.deleteFile(destination)
            AppSelfUpdateStorage.deleteFile(partial)
            try {
                requireApkLengthWithinLimit(asset.sizeBytes, "published asset")
                val maximumDownloadBytes = asset.sizeBytes ?: MAX_APK_BYTES
                val request = Request.Builder().url(asset.downloadUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    if (!response.request.url.isHttps) {
                        throw IOException("Non-HTTPS download URL")
                    }
                    val body = response.body ?: throw IOException("Empty response body")
                    val responseBytes = body.contentLength().takeIf { it >= 0L }
                    requireApkLengthWithinLimit(responseBytes, "HTTP response")
                    if (responseBytes != null && responseBytes > maximumDownloadBytes) {
                        throw ApkTooLargeException("HTTP response exceeds the signed asset length")
                    }
                    val totalBytes = asset.sizeBytes ?: responseBytes?.takeIf { it > 0L }
                    val digest = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                downloaded = checkedDownloadedApkBytes(downloaded, read.toLong(), maximumDownloadBytes)
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                onProgress(downloaded, totalBytes)
                            }
                            if (asset.sizeBytes != null && downloaded != asset.sizeBytes) {
                                throw IOException("Downloaded size mismatch")
                            }
                        }
                    }
                    val computed = digest.digest()
                    if (!constantTimeEqualsHex(computed, asset.sha256Hex)) {
                        throw HashMismatchException()
                    }
                    if (!partial.renameTo(destination)) {
                        partial.copyTo(destination, overwrite = true)
                        AppSelfUpdateStorage.deleteFile(partial)
                    }
                }
                Result.success(Unit)
            } catch (error: CancellationException) {
                AppSelfUpdateStorage.deleteFile(partial)
                AppSelfUpdateStorage.deleteFile(destination)
                throw error
            } catch (error: HashMismatchException) {
                AppSelfUpdateStorage.deleteFile(partial)
                AppSelfUpdateStorage.deleteFile(destination)
                Result.failure(error)
            } catch (error: Exception) {
                AppSelfUpdateStorage.deleteFile(partial)
                AppSelfUpdateStorage.deleteFile(destination)
                Result.failure(error)
            }
        }

    class HashMismatchException : IOException("APK hash mismatch")

    class ApkTooLargeException(
        detail: String = "APK exceeds the download size limit",
    ) : IOException(detail)

    companion object {
        internal const val MAX_APK_BYTES: Long = 256L * 1024L * 1024L
        internal const val CONNECT_TIMEOUT_SECONDS = 30L
        internal const val READ_TIMEOUT_SECONDS = 30L
        internal const val CALL_TIMEOUT_MINUTES = 30L

        internal fun requireApkLengthWithinLimit(
            bytes: Long?,
            source: String,
        ) {
            if (bytes != null && (bytes <= 0L || bytes > MAX_APK_BYTES)) {
                throw ApkTooLargeException("Invalid $source length: $bytes")
            }
        }

        internal fun checkedDownloadedApkBytes(
            downloaded: Long,
            nextChunkBytes: Long,
            maximumBytes: Long = MAX_APK_BYTES,
        ): Long {
            if (
                maximumBytes <= 0L ||
                maximumBytes > MAX_APK_BYTES ||
                downloaded < 0L ||
                nextChunkBytes < 0L ||
                downloaded > maximumBytes ||
                nextChunkBytes > maximumBytes - downloaded
            ) {
                throw ApkTooLargeException()
            }
            return downloaded + nextChunkBytes
        }

        // Reject any host that resolves to a private/loopback/link-local address
        // before a byte is fetched — defence in depth, even though the download
        // URL is publisher-signed. Catches both literal-private hosts and
        // public names that resolve inward (DNS rebinding). Tests inject their
        // own client, so their loopback mock server is unaffected.
        private val ssrfSafeDns =
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    if (addresses.any(HostSafety::isPrivateOrLoopbackAddress)) {
                        throw UnknownHostException("Refusing private/loopback host: $hostname")
                    }
                    return addresses
                }
            }

        internal fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .followSslRedirects(false)
                .dns(ssrfSafeDns)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .build()
    }
}
