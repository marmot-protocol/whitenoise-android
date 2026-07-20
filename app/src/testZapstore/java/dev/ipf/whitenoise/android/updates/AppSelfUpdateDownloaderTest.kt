package dev.ipf.whitenoise.android.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class AppSelfUpdateDownloaderTest {
    @Test
    fun defaultClientBoundsConnectionReadsAndOverallCall() {
        val client = AppSelfUpdateDownloader.defaultHttpClient()

        assertEquals(TimeUnit.SECONDS.toMillis(AppSelfUpdateDownloader.CONNECT_TIMEOUT_SECONDS), client.connectTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(AppSelfUpdateDownloader.READ_TIMEOUT_SECONDS), client.readTimeoutMillis.toLong())
        assertEquals(TimeUnit.MINUTES.toMillis(AppSelfUpdateDownloader.CALL_TIMEOUT_MINUTES), client.callTimeoutMillis.toLong())
    }

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient =
            OkHttpClient
                .Builder()
                .followSslRedirects(false)
                .addInterceptor(rewriteHttpsToLocalHttpInterceptor())
                .build()
        tempDir = Files.createTempDirectory("app-self-update-downloader-").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun trustedHashProducesDestinationAndNoPartialFile() =
        runBlocking {
            val body = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x14)
            val hash = sha256(body).toHex()
            server.enqueue(MockResponse().setBody(body.toRequestBody()))

            val destination = File(tempDir, "darkmatter.apk")
            val asset = assetForBody(hash = hash, sizeBytes = body.size.toLong())
            val result =
                AppSelfUpdateDownloader(httpClient).downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess)
            assertTrue(destination.isFile)
            assertEquals(body.size.toLong(), destination.length())
            assertFalse(File(tempDir, "${destination.name}.part").exists())
        }

    @Test
    fun hashMismatchDeletesDestinationAndPartial() =
        runBlocking {
            val body = byteArrayOf(1, 2, 3, 4, 5)
            server.enqueue(MockResponse().setBody(body.toRequestBody()))

            val destination = File(tempDir, "darkmatter.apk")
            val asset = assetForBody(hash = "c".repeat(64), sizeBytes = body.size.toLong())
            val result =
                AppSelfUpdateDownloader(httpClient).downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AppSelfUpdateDownloader.HashMismatchException)
            assertFalse(destination.exists())
            assertFalse(File(tempDir, "${destination.name}.part").exists())
        }

    @Test
    fun declaredSizeMismatchDeletesFiles() =
        runBlocking {
            val body = byteArrayOf(9, 8, 7)
            val hash = sha256(body).toHex()
            server.enqueue(MockResponse().setBody(body.toRequestBody()))

            val destination = File(tempDir, "darkmatter.apk")
            val asset = assetForBody(hash = hash, sizeBytes = body.size + 1L)
            val result =
                AppSelfUpdateDownloader(httpClient).downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure)
            assertFalse(destination.exists())
            assertFalse(File(tempDir, "${destination.name}.part").exists())
        }

    @Test
    fun oversizedPublishedAssetIsRejectedBeforeNetworkOrDiskWrite() =
        runBlocking {
            val destination = File(tempDir, "darkmatter.apk")
            val asset =
                assetForBody(
                    hash = "a".repeat(64),
                    sizeBytes = AppSelfUpdateDownloader.MAX_APK_BYTES + 1L,
                )

            val result =
                AppSelfUpdateDownloader(httpClient).downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.exceptionOrNull() is AppSelfUpdateDownloader.ApkTooLargeException)
            assertEquals(0, server.requestCount)
            assertFalse(destination.exists())
            assertFalse(File(tempDir, "${destination.name}.part").exists())
        }

    @Test
    fun oversizedHttpBodyIsRejectedBeforeStreaming() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBody(byteArrayOf(1, 2, 3).toRequestBody())
                    .setHeader("Content-Length", AppSelfUpdateDownloader.MAX_APK_BYTES + 1L),
            )
            val destination = File(tempDir, "darkmatter.apk")
            val asset = assetForBody(hash = "a".repeat(64), sizeBytes = null)

            val result =
                AppSelfUpdateDownloader(httpClient).downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.exceptionOrNull() is AppSelfUpdateDownloader.ApkTooLargeException)
            assertFalse(destination.exists())
            assertFalse(File(tempDir, "${destination.name}.part").exists())
        }

    @Test
    fun chunkedBodyCannotStreamPastSignedAssetLength() =
        runBlocking {
            val body = byteArrayOf(1, 2, 3, 4, 5, 6)
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(body), 2))
            val destination = File(tempDir, "darkmatter.apk")
            val asset = assetForBody(hash = sha256(body).toHex(), sizeBytes = 3L)
            var greatestProgress = 0L

            val result =
                AppSelfUpdateDownloader(httpClient).downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { bytesRead, _ -> greatestProgress = maxOf(greatestProgress, bytesRead) },
                )

            assertTrue(result.exceptionOrNull() is AppSelfUpdateDownloader.ApkTooLargeException)
            assertTrue(greatestProgress <= 3L)
            assertFalse(destination.exists())
            assertFalse(File(tempDir, "${destination.name}.part").exists())
        }

    @Test
    fun streamedByteCounterRejectsChunkThatCrossesLimitWithoutOverflow() {
        assertEquals(
            AppSelfUpdateDownloader.MAX_APK_BYTES,
            AppSelfUpdateDownloader.checkedDownloadedApkBytes(
                downloaded = AppSelfUpdateDownloader.MAX_APK_BYTES - 2L,
                nextChunkBytes = 2L,
            ),
        )
        assertThrows(AppSelfUpdateDownloader.ApkTooLargeException::class.java) {
            AppSelfUpdateDownloader.checkedDownloadedApkBytes(
                downloaded = AppSelfUpdateDownloader.MAX_APK_BYTES - 2L,
                nextChunkBytes = 3L,
            )
        }
        assertThrows(AppSelfUpdateDownloader.ApkTooLargeException::class.java) {
            AppSelfUpdateDownloader.checkedDownloadedApkBytes(Long.MAX_VALUE, 1L)
        }
        assertThrows(AppSelfUpdateDownloader.ApkTooLargeException::class.java) {
            AppSelfUpdateDownloader.checkedDownloadedApkBytes(
                downloaded = 2L,
                nextChunkBytes = 2L,
                maximumBytes = 3L,
            )
        }
    }

    @Test
    fun cancellationCleansUpPartialAndDestination() =
        runBlocking {
            val body = ByteArray(8 * 1024) { 0x42 }
            val hash = sha256(body).toHex()
            server.enqueue(
                MockResponse()
                    .throttleBody(64, 100, TimeUnit.MILLISECONDS)
                    .setBody(body.toRequestBody()),
            )

            val destination = File(tempDir, "darkmatter.apk")
            val asset = assetForBody(hash = hash, sizeBytes = body.size.toLong())
            val partial = File(tempDir, "${destination.name}.part")
            val downloader = AppSelfUpdateDownloader(httpClient)
            val download =
                async {
                    downloader.downloadVerifiedApk(
                        asset = asset,
                        destination = destination,
                        onProgress = { _, _ -> },
                    )
                }
            var waitedMs = 0
            while (!partial.exists() && waitedMs < 5_000) {
                delay(25)
                waitedMs += 25
            }
            assertTrue(partial.exists())
            download.cancel()
            val failure =
                runCatching { download.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertFalse(destination.exists())
            assertFalse(partial.exists())
        }

    @Test
    fun streamFailureCleansUpPartialAndDestination() =
        runBlocking {
            val body = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val hash = sha256(body).toHex()
            server.enqueue(
                MockResponse()
                    .setBody(body.toRequestBody())
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )

            val destination = File(tempDir, "darkmatter.apk")
            val asset = assetForBody(hash = hash, sizeBytes = body.size.toLong())
            val result =
                AppSelfUpdateDownloader(httpClient).downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure)
            assertFalse(destination.exists())
            assertFalse(File(tempDir, "${destination.name}.part").exists())
        }

    private fun rewriteHttpsToLocalHttpInterceptor(): Interceptor =
        Interceptor { chain ->
            val httpsRequest = chain.request()
            val localHttpRequest =
                httpsRequest
                    .newBuilder()
                    .url(
                        httpsRequest.url
                            .newBuilder()
                            .scheme("http")
                            .host("127.0.0.1")
                            .port(server.port)
                            .build(),
                    ).build()
            chain
                .proceed(localHttpRequest)
                .newBuilder()
                .request(httpsRequest)
                .build()
        }

    private fun assetForBody(
        hash: String,
        sizeBytes: Long?,
    ): ZapstoreApkAsset =
        ZapstoreApkAsset(
            eventId = "a".repeat(64),
            appId = APP_ID,
            version = VERSION,
            sha256Hex = hash,
            downloadUrl = "https://127.0.0.1:${server.port}/app.apk",
            sizeBytes = sizeBytes,
            platformIds = setOf(PLATFORM_ID),
        )

    private fun ByteArray.toRequestBody(): okio.Buffer = okio.Buffer().write(this)

    private companion object {
        private const val APP_ID = "org.parres.darkmatter"
        private const val VERSION = "2026.6.20"
        private const val PLATFORM_ID = "android-arm64-v8a"
    }
}
