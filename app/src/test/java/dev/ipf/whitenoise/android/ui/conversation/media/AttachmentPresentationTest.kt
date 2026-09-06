package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ActivityNotFoundException
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.state.AttachmentOpenIntentClaim
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.AutomaticBacklogStoppedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AttachmentPresentationTest {
    /** Remote reply filenames cannot expose paths, controls, or bidi spoofing. */
    @Test
    fun replyDisplayNameRemovesPathsAndDirectionalSpoofing() {
        assertEquals("release.apk", safeAttachmentDisplayName("../../release.apk"))
        assertEquals("report.apk", safeAttachmentDisplayName("folder/report.\u202Eapk"))
        assertEquals("release candidate.apk", safeAttachmentDisplayName("release\ncandidate.apk"))
        assertNull(safeAttachmentDisplayName(".."))
        assertNull(safeAttachmentDisplayName("\u202E"))
    }

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
            AttachmentOpenClassification.Ready(ANDROID_PACKAGE_MIME),
            classifyAttachmentOpen("application/zip", "release.apk", validArchive),
        )
        assertEquals(
            AttachmentOpenClassification.Ready(ANDROID_PACKAGE_MIME),
            classifyAttachmentOpen("application/x-zip-compressed", "release.apk", validArchive),
        )
        assertEquals(
            AttachmentOpenClassification.Ready("application/pdf"),
            classifyAttachmentOpen("application/pdf", "misleading.apk", validArchive),
        )
        assertEquals(4, archiveChecks)
    }

    /** Only plausible APKs move from the route-scoped viewer to the app owner. */
    @Test
    fun installerHandoffCandidateHonorsSpecificMimeMetadata() {
        assertTrue(isAndroidPackageOpenCandidate(ANDROID_PACKAGE_MIME, "payload.bin"))
        assertTrue(isAndroidPackageOpenCandidate(GENERIC_BINARY_MIME, "release.APK"))
        assertTrue(isAndroidPackageOpenCandidate("", "release.apk"))
        assertTrue(isAndroidPackageOpenCandidate("application/zip", "release.apk"))
        assertTrue(isAndroidPackageOpenCandidate("application/x-zip-compressed", "release.apk"))
        assertFalse(isAndroidPackageOpenCandidate("application/pdf", "misleading.apk"))
        assertFalse(isAndroidPackageOpenCandidate(GENERIC_BINARY_MIME, "notes.pdf"))
    }

    @Test
    fun genericApkFilenameRejectsAnArtifactThatIsNotAnAndroidPackage() {
        assertEquals(
            AttachmentOpenClassification.InvalidAndroidPackage,
            classifyAttachmentOpen(GENERIC_BINARY_MIME, "release.apk") { false },
        )
        assertEquals(
            AttachmentOpenClassification.InvalidAndroidPackage,
            classifyAttachmentOpen("application/zip", "release.apk") { false },
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
    fun persistedInstallerPermissionHandoffCompletesBeforeRetryingTheExactAttachment() =
        runTest {
            val source = File("agent-build.apk")
            val events = mutableListOf<String>()
            var opens = 0

            val result =
                openAttachmentWithPersistedInstallerPermission(
                    source = source,
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "agent-build.apk",
                    open = { requestedSource, requestedMediaType, requestedFileName ->
                        assertSame(source, requestedSource)
                        assertEquals(ANDROID_PACKAGE_MIME, requestedMediaType)
                        assertEquals("agent-build.apk", requestedFileName)
                        opens += 1
                        events += "open-$opens"
                        if (opens == 1) {
                            OpenAttachmentResult.InstallPermissionRequired
                        } else {
                            OpenAttachmentResult.Opened
                        }
                    },
                    requestInstallPermission = {
                        events += "settings"
                        true
                    },
                    persistence =
                        InstallerPermissionPersistence(
                            claim = AttachmentOpenIntentClaim.Fresh,
                            begin = {
                                events += "begin"
                                true
                            },
                            finish = {
                                events += "finish"
                                true
                            },
                            abandon = { events += "abandon" },
                        ),
                )

            assertEquals(OpenAttachmentResult.Opened, result)
            assertEquals(listOf("open-1", "begin", "settings", "finish", "open-2"), events)
        }

    @Test
    fun recoveredDeniedInstallerPermissionDoesNotReopenSettings() =
        runTest {
            var permissionRequests = 0
            val result =
                openAttachmentWithPersistedInstallerPermission(
                    source = File("agent-build.apk"),
                    mediaType = ANDROID_PACKAGE_MIME,
                    fileName = "agent-build.apk",
                    open = { _, _, _ -> OpenAttachmentResult.InstallPermissionRequired },
                    requestInstallPermission = {
                        permissionRequests += 1
                        false
                    },
                    persistence =
                        InstallerPermissionPersistence(
                            claim = AttachmentOpenIntentClaim.InstallPermissionRecovery,
                            begin = { error("recovery must not begin another Settings handoff") },
                            finish = { error("recovery claim was already consumed") },
                            abandon = { error("no Settings request should be active") },
                        ),
                )

            assertEquals(OpenAttachmentResult.InstallPermissionDenied, result)
            assertEquals(0, permissionRequests)
        }

    @Test
    fun cancelledInstallerSettingsKeepsDurableHandoffForAnotherOwner() =
        runTest {
            var abandoned = false
            val failure =
                runCatching {
                    openAttachmentWithPersistedInstallerPermission(
                        source = File("agent-build.apk"),
                        mediaType = ANDROID_PACKAGE_MIME,
                        fileName = "agent-build.apk",
                        open = { _, _, _ -> OpenAttachmentResult.InstallPermissionRequired },
                        requestInstallPermission = { throw CancellationException("composition replaced") },
                        persistence =
                            InstallerPermissionPersistence(
                                claim = AttachmentOpenIntentClaim.Fresh,
                                begin = { true },
                                finish = { error("cancelled request must not finish") },
                                abandon = { abandoned = true },
                            ),
                    )
                }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue(abandoned)
        }

    @Test
    fun explicitAutomaticPauseBlocksOnlyNetworkFallbacks() {
        assertFalse(
            shouldMaterializeAttachmentAutomatically(
                mine = false,
                mediaAutoDownloadAllowed = true,
                automaticDownloadsPaused = true,
            ),
        )
        assertFalse(
            shouldMaterializeAttachmentAutomatically(
                mine = true,
                mediaAutoDownloadAllowed = false,
                automaticDownloadsPaused = true,
            ),
        )
        assertTrue(
            shouldMaterializeAttachmentAutomatically(
                mine = true,
                mediaAutoDownloadAllowed = false,
                automaticDownloadsPaused = true,
                hasRetainedPlaintext = true,
            ),
        )
        assertTrue(
            shouldMaterializeAttachmentAutomatically(
                mine = false,
                mediaAutoDownloadAllowed = false,
                automaticDownloadsPaused = true,
                hasCachedAttachment = true,
            ),
        )
        assertTrue(
            shouldMaterializeAttachmentAutomatically(
                mine = true,
                mediaAutoDownloadAllowed = false,
                automaticDownloadsPaused = false,
            ),
        )
    }

    @Test
    fun policyTighteningDoesNotRevokeAutomaticOrInteractiveMaterialization() {
        val automatic = AttachmentMaterializationIntent.Idle.withPolicyAllowed(allowed = true)
        val interactive = automatic.afterInteractiveRequest()

        assertEquals(
            AttachmentMaterializationIntent.Automatic,
            automatic.withPolicyAllowed(allowed = false),
        )
        assertEquals(
            AttachmentMaterializationIntent.Interactive,
            interactive.withPolicyAllowed(allowed = false),
        )
    }

    @Test
    fun queuedAutomaticCancellationReturnsToIdleUntilPolicyRestarts() {
        val automatic = AttachmentMaterializationIntent.Idle.withPolicyAllowed(allowed = true)
        val idle =
            automatic.afterProducerCancellation(
                AutomaticBacklogStoppedException(),
            )

        assertEquals(AttachmentMaterializationIntent.Idle, idle)
        assertEquals(
            AttachmentMaterializationIntent.Idle,
            idle.withPolicyAllowed(allowed = false),
        )
        assertEquals(
            AttachmentMaterializationIntent.Automatic,
            idle.withPolicyAllowed(allowed = true),
        )
    }

    @Test
    fun ordinaryCompositionCancellationIsStillPropagated() {
        val cancellation = CancellationException("composition disposed")
        val failure =
            runCatching {
                AttachmentMaterializationIntent.Automatic.afterProducerCancellation(cancellation)
            }.exceptionOrNull()

        assertSame(cancellation, failure)
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
