package dev.ipf.darkmatter.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppSelfUpdateVerifierTest {
    @Test
    fun verifyCachedApkMatchesSignedHashAndSize() =
        runBlocking {
            val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
            val asset = sampleAsset(bytes)
            val file = File.createTempFile("verified", ".apk")
            try {
                file.writeBytes(bytes)
                assertTrue(
                    AppSelfUpdateVerifier.verifyCachedApk(
                        apkFile = file,
                        asset = asset,
                        dispatcher = Dispatchers.Unconfined,
                    ),
                )
            } finally {
                file.delete()
            }
        }

    @Test
    fun verifyCachedApkRejectsSameLengthTamperedContent() =
        runBlocking {
            val bytes = byteArrayOf(0x01, 0x02, 0x03)
            val asset = sampleAsset(bytes)
            val file = File.createTempFile("tampered", ".apk")
            try {
                file.writeBytes(byteArrayOf(0x01, 0x02, 0x04))
                assertFalse(
                    AppSelfUpdateVerifier.verifyCachedApk(
                        apkFile = file,
                        asset = asset,
                        dispatcher = Dispatchers.Unconfined,
                    ),
                )
            } finally {
                file.delete()
            }
        }

    @Test
    fun expectedUpdateArchiveRequiresPackageVersionAndSigningLineage() {
        val installed = identity(packageName = "dev.ipf.darkmatter", versionCode = 6, current = setOf("old"))
        assertTrue(
            isExpectedUpdateArchive(
                installed,
                identity(
                    packageName = "dev.ipf.darkmatter",
                    versionCode = 7,
                    current = setOf("new"),
                    history = setOf("old", "new"),
                ),
                expectedVersion = VERSION,
            ),
        )
        assertFalse(
            isExpectedUpdateArchive(
                installed,
                identity("dev.ipf.other", 7, setOf("old")),
                expectedVersion = VERSION,
            ),
        )
        assertFalse(
            isExpectedUpdateArchive(
                installed,
                identity("dev.ipf.darkmatter", 6, setOf("old")),
                expectedVersion = VERSION,
            ),
        )
        assertFalse(
            isExpectedUpdateArchive(
                installed,
                identity("dev.ipf.darkmatter", 7, setOf("attacker")),
                expectedVersion = VERSION,
            ),
        )
        assertFalse(
            isExpectedUpdateArchive(
                installed,
                identity("dev.ipf.darkmatter", 7, setOf("old"), versionName = "2026.6.98"),
                expectedVersion = VERSION,
            ),
        )
    }

    private fun sampleAsset(bytes: ByteArray): ZapstoreApkAsset =
        ZapstoreApkAsset(
            eventId = "a".repeat(64),
            appId = "org.parres.darkmatter",
            version = VERSION,
            sha256Hex = sha256(bytes).toHex(),
            downloadUrl = "https://cdn.example.com/app.apk",
            sizeBytes = bytes.size.toLong(),
            platformIds = setOf("android-arm64-v8a"),
        )

    private fun identity(
        packageName: String,
        versionCode: Long,
        current: Set<String>,
        history: Set<String> = current,
        versionName: String = VERSION,
    ): ApkArchiveIdentity =
        ApkArchiveIdentity(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            currentSignerDigests = current,
            signerHistoryDigests = history,
        )

    private companion object {
        private const val VERSION = "2026.6.99"
    }
}
