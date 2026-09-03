package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.AttachmentDownloadIntentStore
import dev.ipf.whitenoise.android.state.AttachmentInstallerHandoffRequest
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.AttachmentOpenIntentClaim
import dev.ipf.whitenoise.android.state.AttachmentOpenRequest
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceivedApkAttachmentOpenIntegrationTest {
    private val artifacts = mutableListOf<File>()

    /** Warms Robolectric's FileProvider roots before tests dispatch from background contexts. */
    @Before
    fun setUp() {
        clearFileProviderStrategyCache()
        // Robolectric lazily parses FileProvider roots and cannot perform that
        // package-manager lookup from Dispatchers.IO. Android's provider is
        // thread-safe; warm the test shadow on the runner thread first.
        val seed = artifact("provider-roots.seed").apply { writeText("seed") }
        fileProviderUri(applicationContext(), seed)
    }

    /** Removes generated artifacts and resets FileProvider's process-local strategy cache. */
    @After
    fun tearDown() {
        artifacts.forEach(File::delete)
        clearFileProviderStrategyCache()
    }

    /** Targets unknown-sources permission to White Noise instead of a generic settings screen. */
    @Test
    fun installerPermissionRecoveryTargetsThisApplicationPackage() {
        val context = applicationContext()

        val intent = androidPackageInstallPermissionIntent(context)

        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.data?.toString())
    }

    /** Normalizes explicit and filename-inferred APK candidates to the platform installer contract. */
    @Test
    fun correctlyTypedGenericAndBlankReceivedApksLaunchTheInstallerIntent() =
        runTest {
            listOf(ANDROID_PACKAGE_MIME, GENERIC_BINARY_MIME, "").forEachIndexed { index, advertisedMime ->
                val source = validApkArtifact("received-$index.apk")
                val context = RecordingContext(applicationContext())

                val result =
                    openAttachmentExternally(
                        context = context,
                        source = source,
                        mediaType = advertisedMime,
                        fileName = "../WhiteNoise-release.APK",
                        selfUpdateEnabled = true,
                        sdkInt = 36,
                        canRequestPackageInstalls = { true },
                    )

                assertEquals(OpenAttachmentResult.Opened, result)
                val intent = requireNotNull(context.startedIntent)
                assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
                assertEquals(ANDROID_PACKAGE_MIME, intent.type)
                assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                assertEquals(intent.data, intent.clipData?.getItemAt(0)?.uri)
                val opened =
                    applicationContext()
                        .contentResolver
                        .openInputStream(requireNotNull(intent.data))
                        ?.use { it.readBytes() }
                assertNotNull(opened)
                assertArrayEquals(source.readBytes(), opened)
            }
        }

    /** Leaving the tapped chat cannot revoke the app-owned installer launch. */
    @Test
    fun downloadedApkLaunchesExactlyOnceAfterAnInAppDestinationChange() =
        runTest {
            val source = validApkArtifact("navigation-survivor.apk")
            val context = RecordingContext(applicationContext())
            val preferences =
                ApplicationProvider
                    .getApplicationContext<Context>()
                    .getSharedPreferences("received-apk-navigation-test", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val store = AttachmentDownloadIntentStore(preferences)
            val transfer =
                AttachmentTransferRequest(
                    accountRef = "account-a",
                    groupIdHex = "ab".repeat(16),
                    messageIdHex = "cd".repeat(32),
                    attachmentIndex = 0,
                )
            val installerRequest = AttachmentInstallerHandoffRequest(transfer, sourceEpoch = 7uL)
            val oldViewerRequest = AttachmentOpenRequest(transfer, navigationGeneration = 7L)
            val nextDestination =
                AttachmentOpenDestination(
                    accountRef = "account-b",
                    groupIdHex = "ef".repeat(16),
                    navigationGeneration = 8L,
                )
            assertTrue(store.markInstallerHandoff(installerRequest))

            store.retainOpenIntentsForDestination(nextDestination)
            val claim = requireNotNull(store.claimInstallerHandoff(installerRequest))
            val result =
                openAttachmentWithPersistedInstallerPermission(
                    source = source,
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "navigation-survivor.apk",
                    open = { requestedSource, requestedMediaType, requestedFileName ->
                        openAttachmentExternally(
                            context = context,
                            source = requestedSource,
                            mediaType = requestedMediaType,
                            fileName = requestedFileName,
                            selfUpdateEnabled = true,
                            canRequestPackageInstalls = { true },
                        )
                    },
                    requestInstallPermission = { true },
                    persistence =
                        InstallerPermissionPersistence(
                            claim = claim,
                            begin = { store.beginInstallerPermissionHandoff(installerRequest) },
                            finish = { store.finishInstallerPermissionHandoff(installerRequest) },
                            abandon = { store.abandonInstallerPermissionHandoff(installerRequest) },
                        ),
                )

            assertEquals(OpenAttachmentResult.Opened, result)
            assertEquals(Intent.ACTION_INSTALL_PACKAGE, context.startedIntent?.action)
            assertNull(store.claimInstallerHandoff(installerRequest))
            assertNull(store.pendingInstallerHandoff())
            assertTrue(oldViewerRequest.navigationGeneration != nextDestination.navigationGeneration)
        }

    /** Rejects filename-inferred APKs whose verified payload is not an Android package. */
    @Test
    fun genericApkNameWithNonApkArtifactIsRejectedBeforeDispatch() =
        runTest {
            val context = RecordingContext(applicationContext())
            val invalidArtifacts =
                listOf(
                    artifact("not-an-apk.bin").apply { writeText("not a zip") },
                    zipWithManifest("plain-manifest.zip", "<manifest />".toByteArray()),
                    zipWithManifest(
                        "forged-header.zip",
                        byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x7f, 0x00, 0x00, 0x00),
                    ),
                )

            invalidArtifacts.forEach { source ->
                val result =
                    openAttachmentExternally(
                        context = context,
                        source = source,
                        mediaType = GENERIC_BINARY_MIME,
                        fileName = "release.apk",
                        selfUpdateEnabled = true,
                        canRequestPackageInstalls = { true },
                    )
                assertEquals(OpenAttachmentResult.InvalidPackage, result)
            }
            assertNull(context.startedIntent)
        }

    /** Preserves a specific non-APK MIME type even when the remote filename ends in APK. */
    @Test
    fun conflictingNonGenericMimeNeverUsesFilenameInference() =
        runTest {
            val source = artifact("misleading.bin").apply { writeText("ordinary document") }
            val context = RecordingContext(applicationContext())

            val result =
                openAttachmentExternally(
                    context = context,
                    source = source,
                    mediaType = "application/pdf",
                    fileName = "release.apk",
                    selfUpdateEnabled = true,
                    canRequestPackageInstalls = { true },
                )

            assertEquals(OpenAttachmentResult.Opened, result)
            assertEquals("application/pdf", context.startedIntent?.type)
        }

    @Test
    fun zapstorePermissionAndPlayPolicyAreExplicitBeforeInstallerLaunch() =
        runTest {
            val source = validApkArtifact("policy.apk")
            val zapstoreContext = RecordingContext(applicationContext())
            val playContext = RecordingContext(applicationContext())

            val zapstoreResult =
                openAttachmentExternally(
                    context = zapstoreContext,
                    source = source,
                    mediaType = GENERIC_BINARY_MIME,
                    fileName = "policy.apk",
                    selfUpdateEnabled = true,
                    sdkInt = 36,
                    canRequestPackageInstalls = { false },
                )
            val playResult =
                openAttachmentExternally(
                    context = playContext,
                    source = source,
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "policy.apk",
                    selfUpdateEnabled = false,
                    sdkInt = 36,
                    canRequestPackageInstalls = { false },
                )

            assertEquals(OpenAttachmentResult.InstallPermissionRequired, zapstoreResult)
            assertEquals(OpenAttachmentResult.InstallUnsupported, playResult)
            assertNull(zapstoreContext.startedIntent)
            assertNull(playContext.startedIntent)
        }

    @Test
    fun missingInstallerAndRevokedUriGrantHaveDistinctOutcomes() =
        runTest {
            val source = validApkArtifact("failures.apk")

            val noInstaller =
                openAttachmentExternally(
                    context = RecordingContext(applicationContext(), ActivityNotFoundException()),
                    source = source,
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "failures.apk",
                    selfUpdateEnabled = true,
                    canRequestPackageInstalls = { true },
                )
            val securityFailure =
                openAttachmentExternally(
                    context = RecordingContext(applicationContext(), SecurityException("revoked grant")),
                    source = source,
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "failures.apk",
                    selfUpdateEnabled = true,
                    canRequestPackageInstalls = { true },
                )

            assertEquals(OpenAttachmentResult.NoInstaller, noInstaller)
            assertEquals(OpenAttachmentResult.SecurityFailure, securityFailure)
        }

    @Test
    fun navigationChangeDuringApkPreparationSuppressesTheInstallerAtDispatch() =
        runTest {
            val source = validApkArtifact("stale-destination.apk")
            val context = RecordingContext(applicationContext())
            var visibilityChecks = 0
            var platformResult: OpenAttachmentResult? = null

            val result =
                openAttachmentExternally(
                    context = context,
                    source = source,
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "stale-destination.apk",
                    selfUpdateEnabled = true,
                    canRequestPackageInstalls = { true },
                    dispatchGuard =
                        AttachmentDispatchGuard(
                            canDispatch = { ++visibilityChecks == 1 },
                            onPlatformDispatchResult = { platformResult = it },
                        ),
                )

            assertEquals(OpenAttachmentResult.DestinationNotVisible, result)
            assertEquals(OpenAttachmentResult.DestinationNotVisible, platformResult)
            assertEquals(2, visibilityChecks)
            assertNull(context.startedIntent)
        }

    @Test
    fun navigationChangeDuringUnknownSourcesSettingsSuppressesTheInstallerRetry() =
        runTest {
            val source = validApkArtifact("permission-destination-change.apk")
            val context = RecordingContext(applicationContext())
            var destinationVisible = true
            var permissionGranted = false
            val persistenceEvents = mutableListOf<String>()
            val dispatchGuard = AttachmentDispatchGuard(canDispatch = { destinationVisible })

            val result =
                openAttachmentWithPersistedInstallerPermission(
                    source = source,
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "permission-destination-change.apk",
                    open = { requestedSource, requestedMediaType, requestedFileName ->
                        openAttachmentExternally(
                            context = context,
                            source = requestedSource,
                            mediaType = requestedMediaType,
                            fileName = requestedFileName,
                            selfUpdateEnabled = true,
                            canRequestPackageInstalls = { permissionGranted },
                            dispatchGuard = dispatchGuard,
                        )
                    },
                    requestInstallPermission = {
                        permissionGranted = true
                        destinationVisible = false
                        true
                    },
                    persistence =
                        InstallerPermissionPersistence(
                            claim = AttachmentOpenIntentClaim.Fresh,
                            begin = {
                                persistenceEvents += "begin"
                                true
                            },
                            finish = {
                                persistenceEvents += "finish"
                                true
                            },
                            abandon = { persistenceEvents += "abandon" },
                        ),
                )

            assertEquals(OpenAttachmentResult.DestinationNotVisible, result)
            assertEquals(listOf("begin", "finish"), persistenceEvents)
            assertNull(context.startedIntent)
        }

    @Test
    fun trimmedArtifactHasAStableMissingOutcomeWithoutLaunching() =
        runTest {
            val missing = artifact("trimmed.apk")
            val context = RecordingContext(applicationContext())

            val result =
                openAttachmentExternally(
                    context = context,
                    source = missing,
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "trimmed.apk",
                    selfUpdateEnabled = true,
                    canRequestPackageInstalls = { true },
                )

            assertEquals(OpenAttachmentResult.MissingArtifact, result)
            assertNull(context.startedIntent)
        }

    @Test
    fun attachmentOpenCancellationEscapesTheProductionPath() =
        runTest {
            val source = artifact("cancelled.pdf").apply { writeText("document") }
            val cancellationContexts =
                listOf(
                    CancellingApplicationContext(applicationContext()),
                    RecordingContext(applicationContext(), CancellationException("cancel launch")),
                )

            cancellationContexts.forEach { context ->
                var cancellationEscaped = false
                try {
                    openAttachmentExternally(
                        context = context,
                        source = source,
                        mediaType = "application/pdf",
                        fileName = "cancelled.pdf",
                    )
                } catch (_: CancellationException) {
                    cancellationEscaped = true
                }

                assertTrue(cancellationEscaped)
            }
        }

    @Test
    fun conversationAndMediaLibraryPassFilenameIntoTheVerifiedOpener() {
        val conversationMediaPath = "app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media"
        val bubble = projectFile("$conversationMediaPath/MediaFileBubble.kt").readText()
        val library =
            projectFile("app/src/main/java/dev/ipf/whitenoise/android/ui/medialibrary/MediaLibrary.kt")
                .readText()
        val normalizedLibrary = library.replace(Regex("\\s+"), " ")
        val normalizedBubble = bubble.replace(Regex("\\s+"), " ")
        val mainShell =
            projectFile("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt")
                .readText()
                .replace(Regex("\\s+"), " ")

        assertTrue(
            normalizedBubble.contains(
                "openAttachment( file, reference.mediaType, reference.fileName, " +
                    "InstallerPermissionPersistence(",
            ),
        )
        assertTrue(normalizedBubble.contains("AttachmentDispatchGuard("))
        assertTrue(normalizedBubble.contains("appState.attachmentOpens.isVisible(request)"))
        assertTrue(mainShell.contains("appState.attachmentOpens.setDestination("))
        assertTrue(mainShell.contains("mutableLongStateOf(newAttachmentOpenNavigationGeneration())"))
        assertTrue(
            normalizedLibrary.contains(
                "openAttachment( fetchFile(), row.reference.mediaType, row.reference.fileName, null, null, )",
            ),
        )
    }

    private fun validApkArtifact(name: String): File =
        zipWithManifest(
            name,
            byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x08, 0x00, 0x00, 0x00),
        )

    private fun zipWithManifest(
        name: String,
        manifestBytes: ByteArray,
    ): File =
        artifact(name).also { file ->
            ZipOutputStream(file.outputStream()).use { archive ->
                archive.putNextEntry(ZipEntry("AndroidManifest.xml"))
                archive.write(manifestBytes)
                archive.closeEntry()
            }
        }

    private fun artifact(name: String): File {
        val directory = File(applicationContext().cacheDir, "shared_media").apply { mkdirs() }
        return File(directory, "issue-2196-${System.nanoTime()}-$name").also(artifacts::add)
    }

    private fun applicationContext(): Context = RuntimeEnvironment.getApplication()

    private fun projectFile(path: String): File =
        listOf(File(path), File("../$path"))
            .firstOrNull(File::exists)
            ?: File(path)

    private fun clearFileProviderStrategyCache() {
        val cacheField = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (cacheField.get(null) as MutableMap<String, *>).clear()
    }

    private class RecordingContext(
        base: Context,
        private val launchFailure: RuntimeException? = null,
    ) : ContextWrapper(base) {
        var startedIntent: Intent? = null
            private set

        override fun startActivity(intent: Intent) {
            launchFailure?.let { throw it }
            startedIntent = intent
        }
    }

    private class CancellingApplicationContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = throw CancellationException("test cancellation")
    }
}
