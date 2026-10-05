package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationEmojiArtworkTest {
    @get:Rule val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        manager.cancelAll()
        pruneNotificationEmojiArtwork(context, emptyArray())
        File(context.filesDir, "emoji").listFiles().orEmpty()
            .filter { it.nameWithoutExtension == "wn" }.forEach(File::delete)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun builtinPreviewIsBoundedPrivateAndReadable() =
        runBlocking {
            assertEquals(listOf(":wn:", ":marmot:"), EmojiShortcodes.presentationShortcodes(":wn: :marmot: :wn:"))
            val directory = File(context.cacheDir, "notification_emoji").apply { mkdirs() }
            val probe = File(directory, "provider-probe.png")
            try {
                probe.writeBytes(byteArrayOf())
                assertNotNull(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", probe))
            } finally {
                probe.delete()
            }
            val artifact = requireNotNull(notificationEmojiArtwork(context, ":wn: :marmot: :wn:"))
            artifact.use {
                val bitmap = context.contentResolver.openInputStream(
                    it.uri,
                ).use { input -> BitmapFactory.decodeStream(input) }
                assertEquals(256, bitmap.width)
                assertEquals(128, bitmap.height)
                assertEquals("image/png", context.contentResolver.getType(it.uri))
                assertFalse(it.uri.toString().contains("marmot"))
                assertFalse(it.uri.toString().contains("files/emoji"))
                val provider = context.packageManager.resolveContentProvider("${context.packageName}.fileprovider", 0)
                assertFalse(requireNotNull(provider).exported)
                assertTrue(provider.grantUriPermissions)
            }
            pruneNotificationEmojiArtwork(context, emptyArray())
            assertTrue(artifacts().isEmpty())
        }

    @Test
    fun defaultPresenterPreparerPostsARealLocalPreview() = runBlocking {
        // Warm native graphics outside the optional notification budget.
        requireNotNull(notificationEmojiArtwork(context, ":wn:")).close()
        pruneNotificationEmojiArtwork(context, emptyArray())
        val presenter = LocalNotificationPresenter(
            context,
            groupReconciliation = {},
            enrichmentLauncher = {},
            emojiArtworkTimeoutMs = 5_000L,
        )
        presenter.ensureChannels()
        assertTrue(presenter.show(messageUpdate(":wn:", '6'), shortNpub = { "npub1fixture" }))
        val messages = styleOf(manager.activeNotifications.single().notification).messages
        assertNotNull(messages.first().dataUri)
        assertEquals(":wn:", messages.last().text.toString())
    }

    @Test
    fun carriedHistorySurvivesPruningBetweenReadAndPost() = runBlocking {
        val presenter = LocalNotificationPresenter(
            context,
            groupReconciliation = {},
            enrichmentLauncher = {},
            emojiArtworkTimeoutMs = 5_000L,
        )
        presenter.ensureChannels()
        assertTrue(presenter.show(messageUpdate(":wn:", '4'), shortNpub = { "npub1fixture" }))
        val original =
            requireNotNull(styleOf(manager.activeNotifications.single().notification).messages.first().dataUri)
        try {
            ConversationCardPostSynchronizer.testHook = object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (op == ConversationCardOp.SHOW_NOTIFY && barrier == ConversationCardBarrier.AFTER_READ) {
                        // Simulate SystemUI removing the card before its asynchronous delete callback.
                        synchronized(UserEventNotificationGroup.mutationLock) {
                            manager.cancelAll()
                            pruneNotificationEmojiArtwork(context, emptyArray())
                        }
                        assertNotNull(
                            context.contentResolver.openInputStream(original)?.use(BitmapFactory::decodeStream),
                        )
                    }
                }
            }
            assertTrue(presenter.show(messageUpdate("Second message", '5'), shortNpub = { "npub1fixture" }))
            val messages = styleOf(manager.activeNotifications.single().notification).messages
            assertEquals(original, messages.first().dataUri)
            assertEquals("Second message", messages.last().text.toString())
        } finally {
            ConversationCardPostSynchronizer.testHook = null
        }
        manager.cancelAll()
        pruneNotificationEmojiArtwork(context, emptyArray())
        assertTrue(artifacts().isEmpty())
    }

    @Test
    fun overlappingHistoryLeasesAreIndependentAndIdempotent() = runBlocking {
        val artifact = requireNotNull(notificationEmojiArtwork(context, ":wn:"))
        val sender = androidx.core.app.Person.Builder().setName("Alice").build()
        val history = notificationEmojiMessages(":wn:", 1L, sender, artifact.uri)
        val first = retainNotificationEmojiHistoryArtwork(context, history)
        val second = retainNotificationEmojiHistoryArtwork(context, history)
        artifact.close()
        first.close()
        first.close()
        pruneNotificationEmojiArtwork(context, emptyArray())
        assertEquals(1, artifacts().size)
        second.close()
        pruneNotificationEmojiArtwork(context, emptyArray())
        assertTrue(artifacts().isEmpty())
    }

    @Test
    fun notificationAndStoreShareModernAndLegacyPrecedence() = runBlocking {
        val directory = File(context.filesDir, CustomEmojiStore.DIRECTORY).apply { mkdirs() }
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            // Legacy decodable artwork stays renderable, including names other than .img.
            File(directory, "wn.bmp").outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            source.eraseColor(Color.BLUE)
            File(directory, "wn.PNG").outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val store = CustomEmojiStore(directory)
            store.load()
            val expected = requireNotNull(store.emoji[":wn:"]).image.asAndroidBitmap().getPixel(16, 16)
            val artifact = requireNotNull(notificationEmojiArtwork(context, ":wn:"))
            artifact.use {
                val actual = context.contentResolver.openInputStream(
                    it.uri,
                ).use { input -> BitmapFactory.decodeStream(input) }
                assertEquals(expected, actual.getPixel(64, 64))
                assertEquals(Color.BLUE, expected)
                actual.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun timedOutPreparationKeepsTextAndRequestsOrphanCleanup() = runBlocking {
        var reconciliations = 0
        val presenter = LocalNotificationPresenter(
            context,
            groupReconciliation = { reconciliations++ },
            enrichmentLauncher = {},
            emojiArtworkPreparer = { text, _ ->
                val artifact = requireNotNull(notificationEmojiArtwork(context, text))
                try {
                    kotlinx.coroutines.delay(NOTIFICATION_EMOJI_PREPARE_TIMEOUT_MS + 50L)
                    artifact
                } finally {
                    artifact.close()
                }
            },
        )
        presenter.ensureChannels()
        assertTrue(presenter.show(messageUpdate(":wn:", '7'), shortNpub = { "npub1fixture" }))
        val messages = styleOf(manager.activeNotifications.single().notification).messages
        assertTrue(messages.all { it.dataUri == null })
        assertEquals(":wn:", messages.last().text.toString())
        assertTrue(reconciliations > 0)
        pruneNotificationEmojiArtwork(context, manager.activeNotifications)
        assertTrue(artifacts().isEmpty())
    }

    @Test
    fun timeoutThatDiscardsACompletedResultReleasesItsLease() = runBlocking {
        val artifact = requireNotNull(notificationEmojiArtwork(context, ":wn:"))
        val result = prepareNotificationEmojiArtwork(
            prepare = { _, _ ->
                // Simulate a source finishing despite cancellation just as the deadline wins the handoff.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.delay(40L)
                    artifact
                }
            },
            text = ":wn:",
            source = ":wn:",
            timeoutMs = 5L,
        )
        assertNull(result)
        pruneNotificationEmojiArtwork(context, emptyArray())
        assertTrue(artifacts().isEmpty())
    }

    @Test
    fun codeLinksAndUnknownArtworkKeepTextOnly() =
        runBlocking {
            assertNull(notificationEmojiArtwork(context, "`:wn:`\n```\n:marmot:\n```"))
            assertNull(notificationEmojiArtwork(context, ":wn:", "`:wn:`"))
            assertNull(notificationEmojiArtwork(context, "link :wn:", "[link](https://example.com/:wn:)"))
            assertNull(notificationEmojiArtwork(context, ":unknown:"))
            assertNull(
                notificationEmojiArtwork(
                    context,
                    ":wn:",
                    sourceText = null,
                ),
            )
            assertNull(
                notificationEmojiArtwork(
                    context,
                    ":wn:",
                    sourceText = ":wn:" + "x".repeat(8192),
                ),
            )
            assertTrue(artifacts().isEmpty())
        }

    @Test
    fun pendingPreviewSurvivesPruningUntilOwnershipIsReleased() =
        runBlocking {
            val artifact = requireNotNull(notificationEmojiArtwork(context, ":wn:"))
            pruneNotificationEmojiArtwork(context, emptyArray())
            assertEquals(1, artifacts().size)
            artifact.close()
            pruneNotificationEmojiArtwork(context, emptyArray())
            assertTrue(artifacts().isEmpty())
        }

    @Test
    fun localFileTakesPrecedenceAndItsSourceIsNotExported() =
        runBlocking {
            val store = CustomEmojiStore(File(context.filesDir, CustomEmojiStore.DIRECTORY))
            val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val bytes =
                java.io.ByteArrayOutputStream().use { stream ->
                    source.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.toByteArray()
                }
            store.save("wn", bytes)
            val legacy = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            File(context.filesDir, "emoji/wn.img").outputStream().use {
                check(legacy.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val artifact = requireNotNull(notificationEmojiArtwork(context, ":wn:"))
            artifact.use {
                val bitmap = context.contentResolver.openInputStream(
                    it.uri,
                ).use { input -> BitmapFactory.decodeStream(input) }
                assertEquals(Color.GREEN, bitmap.getPixel(64, 64))
                assertTrue(File(context.filesDir, "emoji/wn.png").isFile)
            }
            assertTrue(File(context.filesDir, "emoji/wn.img").delete())
        }

    @Test
    fun ordinaryMessagesRetainTextActionsAndOneLogicalMessage() =
        runBlocking {
            post(":wn:")
            val notification = manager.activeNotifications.single().notification
            val messages = styleOf(notification).messages
            assertEquals(2, messages.size)
            assertEquals(1, notificationLogicalMessageGroups(messages).size)
            assertNotNull(messages.first().dataUri)
            assertEquals(":wn:", messages.last().text.toString())
            assertEquals(2, notification.actions.size)
            assertNull(
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification.publicVersion),
            )
            pruneNotificationEmojiArtwork(context, manager.activeNotifications)
            assertEquals(1, artifacts().size)
            manager.cancelAll()
            pruneNotificationEmojiArtwork(context, manager.activeNotifications)
            assertTrue(artifacts().isEmpty())
        }

    @Test
    fun redactedCardsNeverExportArtwork() =
        runBlocking {
            post(
                ":wn:",
                redacted = true,
            )
            val notification = manager.activeNotifications.single().notification
            val style = styleOf(notification)
            assertTrue(style.messages.all { it.dataUri == null })
            assertFalse(style.messages.any { it.text.toString().contains(":wn:") })
            assertTrue(artifacts().isEmpty())
        }

    @Test
    @Config(sdk = [30])
    fun carriedPairsSurvivePlatformRoundTripAndReplaceTogether() =
        runBlocking {
            post(
                ":wn:",
                messageChar = '3',
            )
            val firstStyle = styleOf(manager.activeNotifications.single().notification)
            val firstUri = firstStyle.messages.first().dataUri
            post(
                ":marmot:",
                messageChar = '4',
            )
            post(
                "Updated :wn:",
                messageChar = '4',
                replacement = true,
            )
            val finalStyle = styleOf(manager.activeNotifications.single().notification)
            val groups = notificationLogicalMessageGroups(finalStyle.messages)
            assertEquals(2, groups.size)
            assertEquals(4, finalStyle.messages.size)
            assertEquals(firstUri, groups.first().first().dataUri)
            assertEquals(
                ":wn:",
                groups
                    .first()
                    .last()
                    .text
                    .toString(),
            )
            assertEquals(
                "Updated :wn:",
                groups
                    .last()
                    .last()
                    .text
                    .toString(),
            )
        }

    @Test
    fun optionalPreparationFailureStillPostsReadableText() =
        runBlocking {
            post(
                ":wn:",
                preparationFailure = IllegalStateException("synthetic image failure"),
            )
            val style = styleOf(manager.activeNotifications.single().notification)
            assertEquals(1, style.messages.size)
            assertEquals(
                ":wn:",
                style.messages
                    .single()
                    .text
                    .toString(),
            )
            assertNull(style.messages.single().dataUri)
            assertTrue(artifacts().isEmpty())
        }

    @Test
    fun callerCancellationIsNotTurnedIntoSuccessfulPublication() =
        runBlocking {
            var cancelled = false
            try {
                post(
                    ":wn:",
                    preparationFailure = kotlinx.coroutines.CancellationException("synthetic caller cancellation"),
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertTrue(manager.activeNotifications.isEmpty())
        }

    @Test
    fun dismissalDuringArtworkPreparationCannotReplayTheCard() =
        runBlocking {
            val update = messageUpdate(":wn:", '3')
            val key = LocalNotificationFormatter.conversationDismissalKey(update.accountRef, update.groupIdHex)
            val artifact = requireNotNull(notificationEmojiArtwork(context, ":wn:"))
            val presenter =
                LocalNotificationPresenter(
                    context,
                    groupReconciliation = {},
                    enrichmentLauncher = {},
                    emojiArtworkPreparer = { _, _ ->
                        ConversationCardPostSynchronizer.markDismissed(key.tag, key.id)
                        artifact
                    },
                )
            presenter.ensureChannels()
            assertFalse(
                presenter.show(
                    update,
                    shortNpub = { "npub1fixture" },
                ),
            )
            assertTrue(manager.activeNotifications.isEmpty())
            // The rejected post releases its artifact lease instead of leaking protected exports.
            pruneNotificationEmojiArtwork(context, manager.activeNotifications)
            assertTrue(artifacts().isEmpty())
        }

    private suspend fun post(
        text: String,
        redacted: Boolean = false,
        preparationFailure: Throwable? = null,
        messageChar: Char = '3',
        replacement: Boolean = false,
    ) {
        val update = messageUpdate(text, messageChar)
        val artifact = if (redacted || preparationFailure != null) null else notificationEmojiArtwork(context, text)
        val presenter =
            LocalNotificationPresenter(
                context,
                groupReconciliation = {},
                enrichmentLauncher = {},
                emojiArtworkPreparer = { _, _ ->
                    check(!redacted) { "Redacted cards must never prepare artwork" }
                    preparationFailure?.let { throw it }
                    artifact
                },
            )
        presenter.ensureChannels()
        assertTrue(
            presenter.show(
                update,
                redactContent = redacted,
                silentUpdate = replacement,
                replaceCurrentMessage = replacement,
                shortNpub = { "npub1fixture" },
            ),
        )
    }

    private fun messageUpdate(
        text: String,
        messageChar: Char,
    ): NotificationUpdateFfi {
        val sender = NotificationUserFfi("1".repeat(64), "Alice", null)
        return NotificationUpdateFfi(
            notificationKey = "emoji-test",
            conversationKey = "conversation",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "account",
            accountIdHex = "2".repeat(64),
            groupIdHex = "group",
            groupName = "Team",
            isDm = false,
            messageIdHex = messageChar.toString().repeat(64),
            sender = sender,
            receiver =
                sender.copy(
                    accountIdHex = "2".repeat(64),
                    displayName = "Me",
                ),
            previewText = text,
            timestampMs = 0L,
            isFromSelf = false,
            isMention = false,
            reactionEmoji = null,
            reactedToPreview = null,
        )
    }

    private fun artifacts(): List<File> = File(context.cacheDir, "notification_emoji").listFiles().orEmpty().toList()

    private fun styleOf(notification: android.app.Notification): NotificationCompat.MessagingStyle =
        requireNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
}
