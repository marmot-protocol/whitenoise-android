package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MediaUploadAttachmentResultFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.notifications.pumpingMainLooper
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the public controller mutation, including its outer busy retry and optimistic-intent drain. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CustomEmojiReactionControllerTest {
    /** A busy rejection followed by a local definition replacement must still publish the originally selected bytes. */
    @Test
    @Suppress("LongMethod") // Keep the real public-controller retry interleaving and its boundary assertions together.
    fun busyRetryKeepsTheSelectedDefinition() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication<android.app.Application>()
            val code = ":busy_${UUID.randomUUID().toString().replace("-", "")}:"
            val file = File(context.filesDir, "${CustomEmojiStore.DIRECTORY}/${code.trim(':')}.png")
            check(requireNotNull(file.parentFile).mkdirs() || requireNotNull(file.parentFile).isDirectory)
            val original = byteArrayOf(1, 2, 3)
            file.writeBytes(original)
            val uploads = CopyOnWriteArrayList<ByteArray>()
            var publishes = 0
            val native =
                nativeBoundary { method, args ->
                    when (method) {
                        "groupMlsState" ->
                            AppGroupMlsStateFfi(
                                ConversationTimelineTestIds.GROUP_ID,
                                AppProtocolProfileFfi.CURRENT,
                                GroupLifecycleStateFfi.STABLE,
                                7uL,
                                1u,
                                false,
                                emptyList(),
                                false,
                                false,
                                emptyList(),
                                null,
                            )
                        "uploadMedia" -> {
                            uploads +=
                                (args[2] as MediaUploadRequestFfi)
                                    .attachments
                                    .single()
                                    .plaintext
                                    .copyOf()
                            MediaUploadResultFfi(listOf(MediaUploadAttachmentResultFfi(reference(), 3uL)), null)
                        }
                        "reactWithMedia" -> {
                            if (++publishes == 1) {
                                file.writeBytes(byteArrayOf(9, 8, 7))
                                throw MarmotKitException.AccountWorkerBusy()
                            }
                            SendSummaryFfi(
                                1u,
                                listOf("reaction"),
                                SendAcceptDispositionFfi.PUBLISHED,
                                SendMaintenanceDispositionFfi.READY,
                            )
                        }
                        else -> null
                    }
                }
            val state =
                conversationTimelineTestAppState(
                    ConversationLiveSubscriptions(
                        { _, _, _ -> error("no subscription") },
                        { _, _ -> error("no subscription") },
                    ),
                    marmotRuntime = AppMarmotRuntime(context.cacheDir.path, native),
                )
            val controller =
                ConversationController(state, conversationTimelineTestGroup(), conversationTimelineMemberSnapshot())
            try {
                withTimeout(10_000L) { pumpingMainLooper { controller.toggleReaction(code, message()) } }
                assertEquals(2, publishes)
                assertEquals("a busy retry must not upload the replacement definition", 1, uploads.size)
                assertArrayEquals(original, uploads.single())
            } finally {
                controller.onCleared()
                state.mutationsScope.cancel()
                check(file.delete())
            }
        }

    /** A normal Unicode reaction never acquires a dependency on the optional custom-artwork lookup. */
    @Test
    fun unicodeReactionSkipsArtworkLookupEvenWithAnExistingReactionId() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication<android.app.Application>()
            val reads = CopyOnWriteArrayList<String>()
            val native =
                nativeBoundary { method, _ ->
                    if (method == "reactionAttachment") {
                        reads += method
                        error("no artwork read expected")
                    }
                    null
                }
            val state =
                conversationTimelineTestAppState(
                    ConversationLiveSubscriptions(
                        { _, _, _ -> error("no subscription") },
                        { _, _ -> error("no subscription") },
                    ),
                    marmotRuntime = AppMarmotRuntime(context.cacheDir.path, native),
                )
            try {
                org.junit.Assert.assertNull(state.reactionArtworkForSend("👍", "account", "group", "plain-reaction"))
                assertEquals(emptyList<String>(), reads)
            } finally {
                state.mutationsScope.cancel()
            }
        }

    /** A generated upload reference; the fake boundary retains its exact locator and original epoch. */
    private fun reference() =
        MediaAttachmentReferenceFfi(
            listOf(MediaLocatorFfi("blossom", "https://fixture.invalid/original")),
            "",
            "",
            "",
            "emoji.png",
            "image/png",
            EncryptedMediaVersionFfi.V1,
            7uL,
            null,
            null,
        )

    /** A valid target in the controller's generated account and group. */
    private fun message() =
        AppMessageRecordFfi(
            messageIdHex = ConversationTimelineTestIds.MESSAGE_A,
            direction = "sent",
            groupIdHex = ConversationTimelineTestIds.GROUP_ID,
            sender = ConversationTimelineTestIds.ACCOUNT_ID,
            plaintext = "Target",
            contentTokens = MarkdownDocumentFfi(false, emptyList(), ByteArray(0)),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
            sticker = null,
        )
}
