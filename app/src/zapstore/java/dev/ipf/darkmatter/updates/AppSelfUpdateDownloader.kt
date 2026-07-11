package dev.ipf.darkmatter.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
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
                val request = Request.Builder().url(asset.downloadUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    if (!response.request.url.isHttps) {
                        throw IOException("Non-HTTPS download URL")
                    }
                    val body = response.body ?: throw IOException("Empty response body")
                    val totalBytes = asset.sizeBytes ?: body.contentLength().takeIf { it > 0L }
                    val digest = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                downloaded += read
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

    companion object {
        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .followSslRedirects(false)
                .build()
    }
}
