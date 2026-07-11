package dev.ipf.darkmatter.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class AppSelfUpdateDownloaderTest {
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

    @Test
    fun signedSizeAboveCapRejectsBeforeNetwork() =
        runBlocking {
            val destination = File(tempDir, "darkmatter.apk")
            val asset =
                assetForBody(
                    hash = "a".repeat(64),
                    sizeBytes = AppSelfUpdateLimits.MAX_APK_BYTES + 1L,
                )
            val result =
                AppSelfUpdateDownloader(httpClient).downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AppSelfUpdateDownloader.DownloadSizeLimitExceededException)
            assertEquals(0, server.requestCount)
            assertFalse(destination.exists())
        }

    @Test
    fun oversizedContentLengthRejectsBeforeStreaming() =
        runBlocking {
            val body = byteArrayOf(0x01)
            server.enqueue(MockResponse().setBody(body.toRequestBody()))
            val oversizedClient =
                httpClient
                    .newBuilder()
                    .addInterceptor(
                        Interceptor { chain ->
                            val response = chain.proceed(chain.request())
                            response
                                .newBuilder()
                                .header("Content-Length", (AppSelfUpdateLimits.MAX_APK_BYTES + 1L).toString())
                                .build()
                        },
                    ).build()

            val destination = File(tempDir, "darkmatter.apk")
            val asset = assetForBody(hash = "b".repeat(64), sizeBytes = null)
            val result =
                AppSelfUpdateDownloader(oversizedClient).downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AppSelfUpdateDownloader.DownloadSizeLimitExceededException)
            assertFalse(destination.exists())
            assertFalse(File(tempDir, "${destination.name}.part").exists())
        }

    @Test
    fun chunkedResponseOverCapAbortsDuringRead() =
        runBlocking {
            val body = ByteArray(2_048) { 0x5a }
            val hash = sha256(body).toHex()
            server.enqueue(
                MockResponse()
                    .setChunkedBody(body.toRequestBody(), 256),
            )

            val destination = File(tempDir, "darkmatter.apk")
            val asset = assetForBody(hash = hash, sizeBytes = null)
            val downloader = AppSelfUpdateDownloader(httpClient, maxApkBytes = 1_024L)
            val result =
                downloader.downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AppSelfUpdateDownloader.DownloadSizeLimitExceededException)
            assertFalse(destination.exists())
            assertFalse(File(tempDir, "${destination.name}.part").exists())
        }

    @Test
    fun chunkedResponseOverSignedSizeAbortsDuringRead() =
        runBlocking {
            val body = ByteArray(2_048) { 0x6b }
            server.enqueue(MockResponse().setChunkedBody(body.toRequestBody(), 256))

            val destination = File(tempDir, "darkmatter.apk")
            val asset = assetForBody(hash = sha256(body).toHex(), sizeBytes = 1_024L)
            val result =
                AppSelfUpdateDownloader(httpClient).downloadVerifiedApk(
                    asset = asset,
                    destination = destination,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AppSelfUpdateDownloader.DownloadSizeLimitExceededException)
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
