package dev.ipf.whitenoise.android.updates

import dev.ipf.whitenoise.android.core.nostr.sha256
import dev.ipf.whitenoise.android.core.nostr.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val VERIFICATION_TIMEOUT_MS = 5_000L
private const val SHA256_HEX_LENGTH = 64

/** Executes the actual stream/digest/file boundary with an in-process HTTP response and no network. */
class AppSelfUpdateVerificationBoundaryTest {
    @get:Rule val directory = TemporaryFolder()

    /** Verification starts after every byte but before digest comparison publishes the destination. */
    @Test fun checksumBoundaryFollowsBytesAndPrecedesDestination() =
        runBlocking {
            val destination = File(directory.root, "candidate.apk")
            val progress = mutableListOf<Long>()
            var boundaries = 0
            val result =
                verificationDownloader().downloadVerifiedApk(
                    verificationAsset(),
                    destination,
                    onVerificationStarted = {
                        boundaries++
                        assertEquals(VERIFICATION_BYTES.size.toLong(), progress.last())
                        assertFalse(destination.exists())
                        assertTrue(File(directory.root, "candidate.apk.part").isFile)
                    },
                    onProgress = { bytes, _ -> progress += bytes },
                )
            assertTrue(result.isSuccess)
            assertEquals(1, boundaries)
            assertTrue(destination.readBytes().contentEquals(VERIFICATION_BYTES))
            assertFalse(File(directory.root, "candidate.apk.part").exists())
        }

    /** A bad checksum remains its typed failure after the real verification phase begins. */
    @Test fun mismatchAnnouncesVerificationAndDeletesBothFiles() =
        runBlocking {
            val destination = File(directory.root, "candidate.apk")
            var verifying = false
            val result =
                verificationDownloader().downloadVerifiedApk(
                    verificationAsset().copy(sha256Hex = "0".repeat(SHA256_HEX_LENGTH)),
                    destination,
                    onVerificationStarted = { verifying = true },
                    onProgress = { _, _ -> },
                )
            assertTrue(verifying)
            assertTrue(result.exceptionOrNull() is AppSelfUpdateDownloader.HashMismatchException)
            assertFalse(destination.exists())
            assertFalse(File(directory.root, "candidate.apk.part").exists())
        }

    /** Cancellation exactly at verification prevents rename and cleans the fully downloaded partial file. */
    @Test fun cancellationAtChecksumBoundaryNeverPublishesDestination() =
        runBlocking {
            val destination = File(directory.root, "candidate.apk")
            val entered = CompletableDeferred<Unit>()
            val job =
                async {
                    verificationDownloader().downloadVerifiedApk(
                        verificationAsset(),
                        destination,
                        onVerificationStarted = {
                            entered.complete(Unit)
                            awaitCancellation()
                        },
                        onProgress = { _, _ -> },
                    )
                }
            withTimeout(VERIFICATION_TIMEOUT_MS) { entered.await() }
            assertFalse(destination.exists())
            job.cancelAndJoin()
            assertFalse(destination.exists())
            assertFalse(File(directory.root, "candidate.apk.part").exists())
        }
}

internal val VERIFICATION_BYTES = byteArrayOf(0x50, 0x4b, 3, 4, 1, 2, 3, 4)

/** Synthetic signed-asset projection; these tests verify download hashing, not Zapstore event signatures. */
internal fun verificationAsset() =
    ZapstoreApkAsset(
        eventId = "a".repeat(SHA256_HEX_LENGTH),
        appId = "dev.ipf.whitenoise",
        version = "2026.9.12",
        sha256Hex = sha256(VERIFICATION_BYTES).toHex(),
        downloadUrl = "https://example.invalid/update.apk",
        sizeBytes = VERIFICATION_BYTES.size.toLong(),
        platformIds = setOf("android-arm64-v8a"),
    )

/** Only the HTTP transport is replaced; production streaming, size limits, digest and file publication execute. */
internal fun verificationDownloader() =
    AppSelfUpdateDownloader(
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(java.net.HttpURLConnection.HTTP_OK)
                    .message("OK")
                    .body(VERIFICATION_BYTES.toResponseBody())
                    .build()
            }.build(),
    )
