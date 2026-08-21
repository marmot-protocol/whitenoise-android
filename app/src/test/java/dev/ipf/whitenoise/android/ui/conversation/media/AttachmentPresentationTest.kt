package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ActivityNotFoundException
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AttachmentPresentationTest {
    @Test
    fun commonMimeTypesUseHumanFriendlyLabelsAndSemanticCategories() {
        val cases =
            listOf(
                Triple(
                    "application/vnd.android.package-archive",
                    "release.bin",
                    "APK" to AttachmentIconCategory.AndroidPackage,
                ),
                Triple("application/pdf", "paper.bin", "PDF" to AttachmentIconCategory.Pdf),
                Triple("application/zip", "bundle.bin", "ZIP" to AttachmentIconCategory.Archive),
                Triple(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "notes.bin",
                    "DOCX" to AttachmentIconCategory.Document,
                ),
                Triple(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "budget.bin",
                    "XLSX" to AttachmentIconCategory.Spreadsheet,
                ),
                Triple(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "deck.bin",
                    "PPTX" to AttachmentIconCategory.Presentation,
                ),
                Triple("text/markdown", "readme.bin", "Markdown" to AttachmentIconCategory.Text),
                Triple("application/json", "payload.bin", "JSON" to AttachmentIconCategory.Code),
            )

        cases.forEach { (mime, name, expected) ->
            val actual = resolveAttachmentPresentation(mime, name)
            assertEquals(expected.first, actual.formatLabel)
            assertEquals(expected.second, actual.iconCategory)
        }
    }

    @Test
    fun mimeNormalizationHandlesCaseWhitespaceAndParameters() {
        val presentation = resolveAttachmentPresentation("  Application/PDF ; charset=binary ", "file.dat")

        assertEquals("PDF", presentation.formatLabel)
        assertEquals(AttachmentIconCategory.Pdf, presentation.iconCategory)
    }

    @Test
    fun genericMimeUsesRecognizedSafeExtensionIncludingCompoundArchives() {
        assertEquals(
            AttachmentPresentation("APK", AttachmentIconCategory.AndroidPackage),
            resolveAttachmentPresentation("application/octet-stream", "release.APK"),
        )
        assertEquals(
            AttachmentPresentation("TAR.GZ", AttachmentIconCategory.Archive),
            resolveAttachmentPresentation("", "backup.tar.gz"),
        )
    }

    @Test
    fun knownMimeWinsAConflictingFilenameExtension() {
        val presentation = resolveAttachmentPresentation("application/pdf", "definitely-not-an-app.apk")

        assertEquals("PDF", presentation.formatLabel)
        assertEquals(AttachmentIconCategory.Pdf, presentation.iconCategory)
    }

    @Test
    fun familyMimeOnlyUsesAnExtensionFromTheSameFamily() {
        val presentation = resolveAttachmentPresentation("text/plain", "misleading.apk")

        assertNull(presentation.formatLabel)
        assertEquals(AttachmentIconCategory.Text, presentation.iconCategory)
    }

    @Test
    fun unknownVendorMimeNeverLeaksRawMimeJargon() {
        val withExtension = resolveAttachmentPresentation("application/vnd.acme.machine-part", "board.pcb")
        val withoutExtension = resolveAttachmentPresentation("application/vnd.acme.machine-part", "README")

        assertEquals("PCB", withExtension.formatLabel)
        assertEquals(AttachmentIconCategory.Generic, withExtension.iconCategory)
        assertNull(withoutExtension.formatLabel)
        assertFalse(withExtension.formatLabel!!.contains("VND", ignoreCase = true))
    }

    @Test
    fun unsafeOrUnboundedExtensionsFallBackToGenericFile() {
        val bidi = resolveAttachmentPresentation("application/octet-stream", "report.\u202Eapk")
        val tooLong = resolveAttachmentPresentation("", "report.thisextensionistoolong")

        assertNull(bidi.formatLabel)
        assertNull(tooLong.formatLabel)
        assertTrue(bidi.iconCategory == AttachmentIconCategory.Generic)
        assertTrue(tooLong.iconCategory == AttachmentIconCategory.Generic)
    }

    @Test
    fun codeAndMediaExtensionsRetainRecognizableShortFormats() {
        assertEquals(
            AttachmentPresentation("KT", AttachmentIconCategory.Code),
            resolveAttachmentPresentation("application/octet-stream", "Main.kt"),
        )
        assertEquals(
            AttachmentPresentation("JPG", AttachmentIconCategory.Image),
            resolveAttachmentPresentation("image/jpeg", "photo.jpeg"),
        )
    }

    @Test
    fun verifiedApkFilenameRefinesOnlyGenericOpenMime() {
        var archiveChecks = 0
        val validArchive = {
            archiveChecks += 1
            true
        }

        assertEquals(
            AttachmentOpenClassification.Ready(ANDROID_PACKAGE_MIME),
            classifyAttachmentOpen("", "../release.APK", validArchive),
        )
        assertEquals(
            AttachmentOpenClassification.Ready(ANDROID_PACKAGE_MIME),
            classifyAttachmentOpen(" Application/Octet-Stream ; charset=binary ", "release.apk", validArchive),
        )
        assertEquals(
            AttachmentOpenClassification.Ready("application/pdf"),
            classifyAttachmentOpen("application/pdf", "misleading.apk", validArchive),
        )
        assertEquals(2, archiveChecks)
    }

    @Test
    fun genericApkFilenameRejectsAnArtifactThatIsNotAnAndroidPackage() {
        assertEquals(
            AttachmentOpenClassification.InvalidAndroidPackage,
            classifyAttachmentOpen(GENERIC_BINARY_MIME, "release.apk") { false },
        )
    }

    @Test
    fun nonApkAndExplicitPackageMimeDoNotNeedFilenameInference() {
        var archiveChecks = 0
        val unexpectedCheck = {
            archiveChecks += 1
            false
        }

        assertEquals(
            AttachmentOpenClassification.Ready(GENERIC_BINARY_MIME),
            classifyAttachmentOpen("", "notes.pdf", unexpectedCheck),
        )
        assertEquals(
            AttachmentOpenClassification.Ready(ANDROID_PACKAGE_MIME),
            classifyAttachmentOpen(" Application/Vnd.Android.Package-Archive ", "payload.bin", unexpectedCheck),
        )
        assertEquals(0, archiveChecks)
    }

    @Test
    fun zapstoreApkOpenRequestsMissingInstallerPermissionOnlyWhenRequired() {
        assertTrue(
            requiresAndroidPackageInstallPermission(
                mediaType = ANDROID_PACKAGE_MIME,
                selfUpdateEnabled = true,
                sdkInt = 37,
                canRequestPackageInstalls = { false },
            ),
        )
        assertFalse(
            requiresAndroidPackageInstallPermission(
                mediaType = ANDROID_PACKAGE_MIME,
                selfUpdateEnabled = true,
                sdkInt = 37,
                canRequestPackageInstalls = { true },
            ),
        )
        assertFalse(
            requiresAndroidPackageInstallPermission(
                mediaType = "application/pdf",
                selfUpdateEnabled = true,
                sdkInt = 37,
                canRequestPackageInstalls = { false },
            ),
        )
        assertFalse(
            requiresAndroidPackageInstallPermission(
                mediaType = ANDROID_PACKAGE_MIME,
                selfUpdateEnabled = false,
                sdkInt = 37,
                canRequestPackageInstalls = { false },
            ),
        )
        assertFalse(
            requiresAndroidPackageInstallPermission(
                mediaType = ANDROID_PACKAGE_MIME,
                selfUpdateEnabled = true,
                sdkInt = 25,
                canRequestPackageInstalls = { false },
            ),
        )
    }

    @Test
    fun installerPermissionReturnRetriesTheOriginalAttachmentOpen() =
        runTest {
            val source = File("agent-build.apk")
            val openRequests = mutableListOf<Triple<File, String, String>>()
            var permissionRequests = 0

            val result =
                openAttachmentWithInstallerPermission(
                    source = source,
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "agent-build.apk",
                    open = { requestedSource, requestedMediaType, requestedFileName ->
                        openRequests += Triple(requestedSource, requestedMediaType, requestedFileName)
                        if (openRequests.size == 1) {
                            OpenAttachmentResult.InstallPermissionRequired
                        } else {
                            OpenAttachmentResult.Opened
                        }
                    },
                    requestInstallPermission = {
                        permissionRequests += 1
                        true
                    },
                )

            assertEquals(OpenAttachmentResult.Opened, result)
            assertEquals(
                listOf(
                    Triple(source, ANDROID_PACKAGE_MIME, "agent-build.apk"),
                    Triple(source, ANDROID_PACKAGE_MIME, "agent-build.apk"),
                ),
                openRequests,
            )
            assertEquals(1, permissionRequests)
        }

    @Test
    fun deniedInstallerPermissionDoesNotRetryOrReportGenericFailure() =
        runTest {
            var opens = 0
            val result =
                openAttachmentWithInstallerPermission(
                    source = File("agent-build.apk"),
                    mediaType = GENERIC_BINARY_MIME,
                    fileName = "agent-build.apk",
                    open = { _, _, _ ->
                        opens += 1
                        OpenAttachmentResult.InstallPermissionRequired
                    },
                    requestInstallPermission = { false },
                )

            assertEquals(OpenAttachmentResult.InstallPermissionDenied, result)
            assertEquals(1, opens)
        }

    @Test
    fun unavailableInstallerSettingsDoesNotRetry() =
        runTest {
            var opens = 0
            val result =
                openAttachmentWithInstallerPermission(
                    source = File("agent-build.apk"),
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "agent-build.apk",
                    open = { _, _, _ ->
                        opens += 1
                        OpenAttachmentResult.InstallPermissionRequired
                    },
                    requestInstallPermission = { throw ActivityNotFoundException("missing settings") },
                )

            assertEquals(OpenAttachmentResult.InstallPermissionUnavailable, result)
            assertEquals(1, opens)
        }

    @Test
    fun unexpectedInstallerSettingsRuntimeFailureIsDeterministic() =
        runTest {
            val result =
                openAttachmentWithInstallerPermission(
                    source = File("agent-build.apk"),
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "agent-build.apk",
                    open = { _, _, _ -> OpenAttachmentResult.InstallPermissionRequired },
                    requestInstallPermission = { throw UnsupportedOperationException("platform failure") },
                )

            assertEquals(OpenAttachmentResult.InstallPermissionUnavailable, result)
        }

    @Test
    fun installerPermissionRoundTripPinsTheExactArtifactUntilRetryCompletes() =
        runTest {
            val directory = Files.createTempDirectory("installer-permission-artifact").toFile()
            val source = File(directory, "agent-build.apk").apply { writeText("verified attachment") }
            var opens = 0
            try {
                val result =
                    openAttachmentWithInstallerPermission(
                        source = source,
                        mediaType = ANDROID_PACKAGE_MIME,
                        fileName = "agent-build.apk",
                        open = { requestedSource, _, _ ->
                            opens += 1
                            assertTrue(requestedSource.exists())
                            if (opens == 1) {
                                OpenAttachmentResult.InstallPermissionRequired
                            } else {
                                OpenAttachmentResult.Opened
                            }
                        },
                        requestInstallPermission = {
                            AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L)
                            assertTrue(source.exists())
                            true
                        },
                    )

                assertEquals(OpenAttachmentResult.Opened, result)
                assertEquals(2, opens)
                AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L)
                assertFalse(source.exists())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun unresolvedCacheStateNeverStartsAnAutomaticDownload() {
        assertFalse(shouldStartAttachmentDownload(AttachmentTransferState.Resolving, true, 12uL, mine = false))
        assertFalse(shouldStartAttachmentDownload(AttachmentTransferState.Available, true, 12uL, mine = false))
        assertTrue(shouldStartAttachmentDownload(AttachmentTransferState.Remote, true, 12uL, mine = false))
        assertFalse(shouldStartAttachmentDownload(AttachmentTransferState.Remote, true, 0uL, mine = false))
        assertFalse(shouldStartAttachmentDownload(AttachmentTransferState.Failed, true, 12uL, mine = false))
        assertFalse(shouldStartAttachmentDownload(AttachmentTransferState.NotRetained, true, 12uL, mine = false))
    }

    @Test
    fun tapCanJoinAnAutomaticDownloadAlreadyInFlight() {
        assertTrue(canRequestAttachmentOpen(AttachmentTransferState.Resolving, sourceEpoch = 12uL, mine = false))
        assertTrue(canRequestAttachmentOpen(AttachmentTransferState.Downloading, sourceEpoch = 12uL, mine = false))
        assertTrue(canRequestAttachmentOpen(AttachmentTransferState.Available, sourceEpoch = 12uL, mine = false))
        assertFalse(canRequestAttachmentOpen(AttachmentTransferState.Remote, sourceEpoch = 0uL, mine = false))
    }
}
