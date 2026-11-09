package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.notifications.NotificationIdentityPictureSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #3635: the late refresh's loader wiring, run as AppState runs it. Every picture it may still supply loads through a
 * loader, never a raw URL, the chat row's peer is carried for the rewrite to record, and a loader starts nothing
 * behind the app lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationLateLoadersTest {
    private val picture: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    /** A missing group picture and a missing sender picture with URLs each get a loader in their own domain. */
    @Test
    fun missingUrlPicturesLoadOnlyThroughLoaders() =
        runBlocking {
            val requested = mutableListOf<Pair<String, MediaTrustDomain>>()
            val sources =
                sources(conversationUrl = GROUP_URL, senderUrl = SENDER_URL).withLateLoaders(
                    isDm = false,
                    reactorOnly = false,
                    storedShown = false,
                    row = { LateRowIdentity(null, null) },
                    encryptedGroupLoader = { error("a group picture with a URL decrypts nothing") },
                    urlLoader = { url, domain ->
                        requested += url to domain
                        suspend { picture }
                    },
                )

            assertEquals(listOf(GROUP_URL to GROUP_DOMAIN, SENDER_URL to SENDER_DOMAIN), requested)
            assertSame(picture, sources.conversationLoader?.invoke())
            assertSame(picture, sources.senderLoader?.invoke())
            assertTrue("no slot falls back to its raw URL", sources.hasConversationLoader)
        }

    /** A group picture that has no URL, such as a Marmot encrypted image, gets the encrypted-image loader. */
    @Test
    fun groupPictureWithoutAUrlGetsTheEncryptedLoader() =
        runBlocking {
            val encrypted: suspend () -> Bitmap? = { picture }
            val sources =
                sources().withLateLoaders(
                    isDm = false,
                    reactorOnly = false,
                    storedShown = false,
                    row = { null },
                    encryptedGroupLoader = { encrypted },
                    urlLoader = { _, _ -> error("no URL to load") },
                )

            assertSame(encrypted, sources.conversationLoader)
        }

    /** A direct chat has no group picture, so a missing URL never starts an encrypted-image load. */
    @Test
    fun directChatWithoutAUrlGetsNoEncryptedLoader() =
        runBlocking {
            val sources =
                sources().withLateLoaders(
                    isDm = true,
                    reactorOnly = false,
                    storedShown = false,
                    row = { null },
                    encryptedGroupLoader = { error("a direct chat has no group picture") },
                    urlLoader = { _, _ -> error("no URL to load") },
                )

            assertNull(sources.conversationLoader)
        }

    /** A conversation picture the card already shows is never fetched again from its URL. */
    @Test
    fun shownConversationPictureGetsNoUrlLoader() =
        runBlocking {
            val sources =
                sources(conversation = picture, conversationUrl = GROUP_URL).withLateLoaders(
                    isDm = false,
                    reactorOnly = false,
                    storedShown = false,
                    row = { null },
                    encryptedGroupLoader = { error("a shown picture decrypts nothing") },
                    urlLoader = { _, _ -> error("a shown picture is never fetched") },
                )

            assertNull(sources.conversationLoader)
        }

    /**
     * The row's stored-picture loader and the peer whose private picture it shows are carried, and the peer alone is
     * enough for a refresh, so the rewrite can record them.
     */
    @Test
    fun rowsStoredLoaderAndPeerAreCarried() =
        runBlocking {
            val stored: suspend () -> Bitmap? = { picture }
            val withStored =
                sources().withLateLoaders(
                    isDm = true,
                    reactorOnly = false,
                    storedShown = false,
                    row = { LateRowIdentity(stored, PEER) },
                    encryptedGroupLoader = { null },
                    urlLoader = { _, _ -> error("no URL to load") },
                )
            val peerOnly =
                sources().withLateLoaders(
                    isDm = true,
                    reactorOnly = false,
                    storedShown = false,
                    row = { LateRowIdentity(null, PEER) },
                    encryptedGroupLoader = { null },
                    urlLoader = { _, _ -> error("no URL to load") },
                )

            assertSame(stored, withStored.storedConversationLoader)
            assertEquals(PEER, withStored.identityContact)
            assertFalse("nothing is missing", peerOnly.hasMissingPicture)
            assertTrue("but the peer may still need recording", peerOnly.mayRefresh)
        }

    /** A reaction card, or a card already showing the stored picture, reads no row, while its sender still loads. */
    @Test
    fun settledConversationPictureReadsNoRow() =
        runBlocking {
            for ((reaction, storedShown) in listOf(true to false, false to true)) {
                val sources =
                    sources(senderUrl = SENDER_URL).withLateLoaders(
                        isDm = false,
                        reactorOnly = reaction,
                        storedShown = storedShown,
                        row = { error("a settled conversation picture reads no row") },
                        encryptedGroupLoader = { error("a settled conversation picture decrypts nothing") },
                        urlLoader = { _, _ -> suspend { picture } },
                    )

                assertNull(sources.storedConversationLoader)
                assertNull(sources.identityContact)
                assertSame(picture, sources.senderLoader?.invoke())
            }
        }

    /** While the app lock shows, the loader starts no download. */
    @Test
    fun lockCheckedLoaderStartsNothingWhileLocked() =
        runBlocking {
            val loader =
                lockCheckedPictureLoader(SENDER_URL, SENDER_DOMAIN, locked = { true }) { _, _ ->
                    error("nothing starts behind the lock")
                }

            assertNull(loader())
        }

    /** A picture that finishes after the lock appears is dropped, and one that finishes unlocked is returned. */
    @Test
    fun lockCheckedLoaderDropsAPictureThatFinishesBehindTheLock() =
        runBlocking {
            var locked = false
            val lockedMidLoad =
                lockCheckedPictureLoader(SENDER_URL, SENDER_DOMAIN, locked = { locked }) { _, _ ->
                    locked = true
                    picture
                }
            assertNull(lockedMidLoad())

            locked = false
            val unlocked = lockCheckedPictureLoader(SENDER_URL, SENDER_DOMAIN, locked = { locked }) { _, _ -> picture }
            assertSame(picture, unlocked())
        }

    /** Late sources whose shown pictures and URLs the scenario chooses, each slot in its own domain. */
    private fun sources(
        conversation: Bitmap? = null,
        conversationUrl: String? = null,
        senderUrl: String? = null,
    ) = NotificationIdentityPictureSources(
        conversationBitmap = conversation,
        conversationUrl = conversationUrl,
        senderBitmap = null,
        senderUrl = senderUrl,
        conversationDomain = GROUP_DOMAIN,
        senderDomain = SENDER_DOMAIN,
    )

    private companion object {
        const val GROUP_URL = "https://example.invalid/group.png"
        const val SENDER_URL = "https://example.invalid/sender.png"
        val PEER = "a".repeat(64)
        val GROUP_DOMAIN = MediaTrustDomain.conversation("account-a", "group-a")
        val SENDER_DOMAIN = MediaTrustDomain.publicProfiles("account-a")
    }
}
