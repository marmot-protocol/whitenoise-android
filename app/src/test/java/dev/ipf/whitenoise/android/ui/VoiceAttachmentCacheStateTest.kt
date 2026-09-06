package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.ui.conversation.media.VoicePresentationAttachmentKey
import dev.ipf.whitenoise.android.ui.conversation.media.cachedVoiceAttachmentFile
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVoiceAttachmentSource
import dev.ipf.whitenoise.android.ui.conversation.media.shouldInvalidateVoiceAttachmentCache
import dev.ipf.whitenoise.android.ui.conversation.media.shouldStartVoiceAttachmentDownload
import dev.ipf.whitenoise.android.ui.conversation.media.voicePlaybackKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceAttachmentCacheStateTest {
    private companion object {
        private const val TEST_HANG_GUARD_MS = 30_000L
    }

    @Test
    fun cachedVoiceFileIsFoundOnEntry() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "voice-cache-hit"
        val reference = mediaReference(mediaType = "audio/mp4")
        val expected =
            File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-1-1.m4a")
        expected.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(expected, cachedVoiceAttachmentFile(context, messageId, 1, reference))
        assertTrue(
            shouldStartVoiceAttachmentDownload(
                mine = false,
                audioAutoDownload = false,
                hasCachedAttachment = false,
                hasCachedFile = true,
            ),
        )
    }

    @Test
    fun emptyVoiceFileIsNotTreatedAsCached() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "voice-empty-cache"
        val reference = mediaReference(mediaType = "audio/aac")
        File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-2-1.aac")
            .writeBytes(byteArrayOf())

        assertNull(cachedVoiceAttachmentFile(context, messageId, 2, reference))
    }

    @Test
    fun plaintextCacheHitStartsMaterializationWhenAutoDownloadIsOff() {
        assertTrue(
            shouldStartVoiceAttachmentDownload(
                mine = false,
                audioAutoDownload = false,
                hasCachedAttachment = true,
                hasCachedFile = false,
            ),
        )
    }

    @Test
    fun uncachedIncomingClipStillWaitsForUserOptInWhenAutoDownloadIsOff() {
        assertFalse(
            shouldStartVoiceAttachmentDownload(
                mine = false,
                audioAutoDownload = false,
                hasCachedAttachment = false,
                hasCachedFile = false,
            ),
        )
    }

    @Test
    fun playbackPrepareFailureInvalidatesSeededCachedVoiceFile() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "voice-corrupt-cache"
        val reference = mediaReference(mediaType = "audio/mp4")
        val cached =
            File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-3-1.m4a")
        cached.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(cached, cachedVoiceAttachmentFile(context, messageId, 3, reference))
        assertTrue(
            shouldInvalidateVoiceAttachmentCache(
                VoicePlaybackController.PlaybackStartResult.PrepareFailed,
            ),
        )
        cached.delete()
        assertNull(cachedVoiceAttachmentFile(context, messageId, 3, reference))
    }

    @Test
    fun focusDenialDoesNotInvalidateCachedVoiceFile() {
        assertFalse(
            shouldInvalidateVoiceAttachmentCache(
                VoicePlaybackController.PlaybackStartResult.FocusDenied,
            ),
        )
    }

    /** Guards source epoch as part of the stable voice-cache destination filename. */
    @Test
    fun sourceEpochIsPartOfVoiceCacheDestinationIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "voice-epoch-identity"
        val epochOne = mediaReference(mediaType = "audio/mp4", sourceEpoch = 1uL)
        val epochTwo = mediaReference(mediaType = "audio/mp4", sourceEpoch = 2uL)
        val epochOneFile =
            File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-1-1.m4a")
        epochOneFile.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(epochOneFile, cachedVoiceAttachmentFile(context, messageId, 1, epochOne))
        assertNull(cachedVoiceAttachmentFile(context, messageId, 1, epochTwo))
    }

    /** Guards source epoch as part of playback identity so stale callbacks cannot match replacements. */
    @Test
    fun sourceEpochIsPartOfVoicePlaybackIdentity() {
        val messageId = "voice-playback-epoch-identity"

        val epochOneKey = voicePlaybackKey(messageId, 1, 1uL)
        val epochTwoKey = voicePlaybackKey(messageId, 1, 2uL)

        assertEquals("$messageId#1#1", epochOneKey)
        assertEquals("$messageId#1#2", epochTwoKey)
        assertFalse(
            "a stale playback callback must not match a newer attachment revision",
            epochOneKey == epochTwoKey,
        )
    }

    /** Guards sibling Compose identity across attachment insertion, removal, and replacement. */
    @Test
    fun voicePresentationAttachmentIdentityDistinguishesSiblingsAndRevisions() {
        val first = VoicePresentationAttachmentKey("message", 0, 1uL)
        val renderer = bubbleContentBlocksSource().readText()

        assertEquals(first, VoicePresentationAttachmentKey("message", 0, 1uL))
        assertFalse(first == VoicePresentationAttachmentKey("message", 1, 1uL))
        assertFalse(first == VoicePresentationAttachmentKey("message", 0, 2uL))
        assertFalse(first == VoicePresentationAttachmentKey("replacement", 0, 1uL))
        assertEquals(
            "confirmed and pending sibling loops must both include attachment identity",
            2,
            Regex("""key\(presentationOwner, attachmentKey\)""").findAll(renderer).count(),
        )
    }

    /** Guards that production voice publication enters single-flight before its cache probe. */
    @Test
    fun voiceMaterializationUsesSharedSingleFlight() {
        val source = mediaVoiceSource().readText()

        assertTrue(
            "voice materialization should use the shared single-flight utility",
            Regex(
                """private\s+val\s+voiceMaterializations\s*=\s*SingleFlight<VoiceMaterializationFlightKey,""" +
                    """\s*java\.io\.File>\(\)""",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the controller-owned flight must begin before the materializer checks the cache fast path",
            "voiceMaterializations.run(VoiceMaterializationFlightKey(file.absolutePath, materializationOwner))" in
                source
                    .substringAfter("internal suspend fun materializeVoiceAttachmentSource("),
        )
    }

    /** Proves identical attachment ids cannot join a previous controller owner's suspended flight. */
    @Test
    fun samePathDifferentOwnersMaterializeIndependently() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                val context = RuntimeEnvironment.getApplication()
                val messageId = "voice-owner-flight-${System.nanoTime()}"
                val attachmentIndex = 1
                val reference = mediaReference(mediaType = "audio/mp4")
                val firstOwner = Any()
                val secondOwner = Any()
                val firstEntered = CompletableDeferred<Unit>()
                val secondEntered = CompletableDeferred<Unit>()
                val releaseFirst = CompletableDeferred<Unit>()
                val releaseSecond = CompletableDeferred<Unit>()
                val attachment = OwnerFlightAttachment(context, messageId, attachmentIndex, reference)
                val cacheFile =
                    File(
                        File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() },
                        "$messageId-$attachmentIndex-${reference.sourceEpoch}.m4a",
                    )
                cacheFile.delete()

                val first =
                    startFailingOwnerFlight(
                        attachment,
                        firstOwner,
                        firstEntered,
                        releaseFirst,
                        "first owner released",
                    )
                val second =
                    startFailingOwnerFlight(
                        attachment,
                        secondOwner,
                        secondEntered,
                        releaseSecond,
                        "second owner released",
                    )
                try {
                    firstEntered.await()
                    secondEntered.await()
                    assertFalse("both owner flights must remain suspended", first.isCompleted || second.isCompleted)

                    releaseFirst.complete(Unit)
                    releaseSecond.complete(Unit)
                    assertEquals("first owner released", first.await().exceptionOrNull()?.message)
                    assertEquals("second owner released", second.await().exceptionOrNull()?.message)
                } finally {
                    releaseFirst.complete(Unit)
                    releaseSecond.complete(Unit)
                    cacheFile.delete()
                }
            }
        }
    }

    /** Starts one owner-scoped source load that remains held until its expected failure is released. */
    private fun CoroutineScope.startFailingOwnerFlight(
        attachment: OwnerFlightAttachment,
        owner: Any,
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
        failureMessage: String,
    ): Deferred<Result<File>> =
        async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                materializeVoiceAttachmentSource(
                    context = attachment.context,
                    messageIdHex = attachment.messageId,
                    attachmentIndex = attachment.attachmentIndex,
                    reference = attachment.reference,
                    materializationOwner = owner,
                    resolveSource = {
                        entered.complete(Unit)
                        release.await()
                        error(failureMessage)
                    },
                )
            }
        }

    /** Stable attachment identity shared by two deliberately distinct owner flights. */
    private data class OwnerFlightAttachment(
        val context: android.content.Context,
        val messageId: String,
        val attachmentIndex: Int,
        val reference: MediaAttachmentReferenceFfi,
    )

    /** Proves a waiter cannot accept a partial file while the owner publishes the same path. */
    @Test
    fun samePathWaiterAwaitsActiveMaterializationDespitePartialCacheFile() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                val context = RuntimeEnvironment.getApplication()
                val messageId = "voice-single-flight-waiter-${System.nanoTime()}"
                val attachmentIndex = 1
                val reference = mediaReference(mediaType = "audio/mp4")
                val cacheFile =
                    File(
                        File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() },
                        "$messageId-$attachmentIndex-${reference.sourceEpoch}.m4a",
                    )
                cacheFile.delete()

                val fullBytes = ByteArray(128) { (it + 1).toByte() }
                val partialBytes = fullBytes.copyOfRange(0, 16)
                val downloadEntered = CompletableDeferred<Unit>()
                val releaseDownload = CompletableDeferred<Unit>()

                val owner =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        materializeVoiceAttachmentSource(
                            context = context,
                            messageIdHex = messageId,
                            attachmentIndex = attachmentIndex,
                            reference = reference,
                            resolveSource = {
                                downloadEntered.complete(Unit)
                                releaseDownload.await()
                                AttachmentPlaintext.Bytes(fullBytes)
                            },
                        )
                    }
                try {
                    downloadEntered.await()

                    cacheFile.writeBytes(partialBytes)
                    assertEquals(partialBytes.size.toLong(), cacheFile.length())
                    assertEquals(cacheFile, cachedVoiceAttachmentFile(context, messageId, attachmentIndex, reference))

                    val waiter =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            materializeVoiceAttachmentSource(
                                context = context,
                                messageIdHex = messageId,
                                attachmentIndex = attachmentIndex,
                                reference = reference,
                                resolveSource = { error("waiter must join the owner's flight") },
                            )
                        }

                    val earlyWaiterFile = withTimeoutOrNull(100) { waiter.await() }

                    releaseDownload.complete(Unit)
                    val ownerFile = owner.await()

                    if (earlyWaiterFile != null) {
                        fail(
                            "same-path waiter must not return a partial cache file while materialization is in flight",
                        )
                    }

                    val waiterFile = waiter.await()
                    assertArrayEquals(fullBytes, ownerFile.readBytes())
                    assertArrayEquals(fullBytes, waiterFile.readBytes())
                    assertEquals(fullBytes.size.toLong(), waiterFile.length())
                } finally {
                    releaseDownload.complete(Unit)
                    cacheFile.delete()
                }
            }
        }
    }

    private fun mediaVoiceSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVoice.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVoice.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MediaVoice.kt source file")

    private fun bubbleContentBlocksSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/BubbleContentBlocks.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/BubbleContentBlocks.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing BubbleContentBlocks.kt source file")

    private fun mediaReference(
        mediaType: String,
        sourceEpoch: ULong = 1uL,
    ): MediaAttachmentReferenceFfi =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "",
            plaintextSha256 = "",
            nonceHex = "",
            fileName = "voice",
            mediaType = mediaType,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            sourceEpoch = sourceEpoch,
            dim = null,
            thumbhash = null,
        )
}
