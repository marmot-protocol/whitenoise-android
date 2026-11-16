package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** Explicit reuse of received artwork follows the native user-intent lane. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationEmojiAcquisitionTest {
    /** User-requested reads retrieve missing originals even when native automatic image permission is denied. */
    @Test
    fun explicitArtworkReadBypassesAutomaticImageDenial() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val fixture = MediaDownloadIntegrationFixture()
        try {
            runTest(dispatcher) {
                fixture.enforceNativePermission = true
                val bytes = byteArrayOf(1, 2, 3)
                fixture.onDownload = { it.succeed(bytes) }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val controller =
                    ConversationController(
                        fixture.state,
                        conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
                    )
                val source =
                    ConversationEmojiSource(request(0).messageIdHex, 0, reference(0).copy(plaintextSha256 = sha))

                val artwork =
                    controller.readConversationEmojiArtwork(":remote:", source, AttachmentDownloadPriority.Interactive)

                assertArrayEquals(bytes, artwork.bytes)
                assertEquals(1, fixture.explicitDemands.get())
                assertEquals(0, fixture.automaticDemands.get())
            }
        } finally {
            try {
                fixture.closeAndDrain(dispatcher)
            } finally {
                Dispatchers.resetMain()
            }
        }
    }
}
