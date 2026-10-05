package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaDownloadResultFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MediaUploadAttachmentResultFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.ForwardAttachmentSource
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation

/**
 * The shipping forward path emits one closed phase line per source lookup,
 * source download, destination upload, commit-lock wait, publication and
 * terminal outcome, and none of those lines can carry an identifier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ForwardDiagnosticsProductionPathTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val messageIdCounter = AtomicInteger(0)

    @Suppress("UNCHECKED_CAST")
    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "timelineMessages" ->
                    TimelinePageFfi(messages = emptyList(), hasMoreBefore = false, hasMoreAfter = false)
                "downloadMedia" ->
                    MediaDownloadResultFfi(
                        plaintext = byteArrayOf(1, 2, 3, 4),
                        fileName = SOURCE_FILE,
                        mediaType = "text/plain",
                        sizeBytes = 4uL,
                    )
                "uploadMedia" ->
                    MediaUploadResultFfi(
                        attachments =
                            listOf(
                                MediaUploadAttachmentResultFfi(
                                    reference = mediaReference("destination.txt", "d"),
                                    encryptedSizeBytes = 20uL,
                                ),
                            ),
                        sent = null,
                    )
                "sendMediaAttachments" ->
                    SendSummaryFfi(
                        published = 1u,
                        messageIds = listOf("media-id-${messageIdCounter.incrementAndGet()}"),
                        acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                        maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                    )
                "accountUnreadSummary", "chatList" -> emptyList<Any>()
                "toString" -> "ForwardDiagnosticsMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else ->
                    if (arguments?.lastOrNull() is Continuation<*>) {
                        error("Unexpected Marmot call: ${method.name}")
                    } else {
                        throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
                    }
            }
        } as MarmotInterface

    /** Leaves the process-local diagnostics session closed for later tests. */
    @After
    fun stopDiagnostics() {
        PerformanceDiagnostics.stop()
    }

    /** One uncached single-attachment forward to one destination emits the full phase sequence and nothing else. */
    @Test
    fun uncachedMediaForwardEmitsClosedPhasesWithoutIdentifiers() {
        PerformanceDiagnostics.stop()
        assumeTrue(PerformanceDiagnostics.start().active)
        val appState = appState()

        val started =
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_GROUP),
                messages = listOf(mediaPayload()),
                sourceAccountRef = ACCOUNT,
                destinationAccountRef = ACCOUNT,
            )
        assertTrue(started)
        val terminal = awaitTerminal(appState)
        assertEquals(ForwardOperationPhase.Completed, terminal.phase)

        val lines = PerformanceDiagnostics.exportLines().filter { " op=message_forward " in it }
        val phases = lines.map { line -> line.substringAfter(" phase=").substringBefore(' ') }
        assertEquals(
            listOf(
                "forward_source_lookup",
                "forward_source_download_start",
                "forward_source_download_return",
                "forward_source_ready",
                "media_upload_start",
                "media_upload_return",
                "media_publish_start",
                "media_publish_return",
                "forward_complete",
            ),
            phases.filterNot { it == "commit_lock_acquired" },
        )
        assertTrue(lines.single { "phase=forward_source_lookup " in it }.contains(" result=pending layer=storage"))
        assertTrue(lines.single { "phase=forward_source_download_return " in it }.contains(" result=success layer=mdk"))
        assertTrue(lines.single { "phase=media_upload_return " in it }.contains(" result=success layer=ffi"))
        assertTrue(lines.single { "phase=media_publish_return " in it }.contains(" result=success layer=mdk"))
        assertTrue(lines.single { "phase=forward_complete " in it }.contains(" result=success layer=android count=1"))
        listOf(SOURCE_GROUP, TARGET_GROUP, SOURCE_MESSAGE, ACCOUNT, SOURCE_FILE, "media.example", "media-id-")
            .forEach { denied -> assertTrue("identifier leaked: $denied", lines.none { denied in it }) }
    }

    /** Builds an app state wired to the scripted engine proxy. */
    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = listOf(account()),
            activeAccountRef = ACCOUNT,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /** Builds one signed-in signing-account summary. */
    private fun account() =
        AccountSummaryFfi(
            label = ACCOUNT,
            accountIdHex = "ab".repeat(32),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** Pumps the main looper until the operation leaves its active phases. */
    private fun awaitTerminal(
        appState: WhiteNoiseAppState,
        timeoutMillis: Long = 20_000,
    ): ForwardOperationSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val snapshot = appState.activeForwardOperation.value
            if (snapshot != null && !snapshot.isActive) return snapshot
            Thread.sleep(5)
        }
        error("forward operation did not reach a terminal state")
    }

    /** Builds one complete authoritative media reference. */
    private fun mediaReference(
        fileName: String,
        hashPrefix: String,
    ) = MediaAttachmentReferenceFfi(
        locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://media.example/$fileName")),
        ciphertextSha256 = hashPrefix.repeat(64),
        plaintextSha256 = "b".repeat(64),
        nonceHex = "c".repeat(24),
        fileName = fileName,
        mediaType = "text/plain",
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 4uL,
        dim = null,
        thumbhash = null,
    )

    /** Builds one media payload whose attachment resolves through the source group. */
    private fun mediaPayload() =
        ForwardMessagePayload.Media(
            sourceGroupIdHex = SOURCE_GROUP,
            sourceMessageIdHex = SOURCE_MESSAGE,
            caption = null,
            attachments = listOf(ForwardAttachmentSource(0, mediaReference(SOURCE_FILE, "a"))),
        )

    private companion object {
        const val ACCOUNT = "forward-diagnostics-account"
        const val SOURCE_GROUP = "f1e2d3c4b5a69788"
        const val TARGET_GROUP = "0011223344556677"
        const val SOURCE_MESSAGE = "0f1e2d3c4b5a6978"
        const val SOURCE_FILE = "notes.txt"
    }
}
