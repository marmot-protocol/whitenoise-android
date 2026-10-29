package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Tests the real channel creator and public URI boundary; playback remains a device qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class NotificationTonesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun onlyFreshMessageCategoriesUseSignatureAndExistingSilenceSurvives() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannelSpec.GROUP_MESSAGES.id,
                "My silent groups",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null) },
        )
        NotificationChannels.ensureChannels(context)
        assertEquals(
            NotificationTone.DING.uri(context),
            manager.getNotificationChannel(NotificationChannelSpec.DIRECT_MESSAGES.id).sound,
        )
        assertEquals(
            NotificationTone.DING.uri(context),
            manager.getNotificationChannel(NotificationChannelSpec.MENTIONS.id).sound,
        )
        assertNull(manager.getNotificationChannel(NotificationChannelSpec.GROUP_MESSAGES.id).sound)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(NotificationChannelSpec.GROUP_MESSAGES.id).importance,
        )
        assertNull(manager.getNotificationChannel(NotificationChannelSpec.USER_EVENT_SUMMARY.id).sound)
        listOf(
            NotificationChannelSpec.REACTIONS,
            NotificationChannelSpec.INVITES,
            NotificationChannelSpec.AGENT_ACTIVITY,
        ).forEach {
            assertNotEquals(NotificationTone.DING.uri(context), manager.getNotificationChannel(it.id).sound)
        }
    }

    @Test
    fun newConversationAndProfileChildrenCloneSignatureWithoutNewRoutingVersions() {
        NotificationChannels.ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val shortcut = "synthetic-notification-tone-chat"
        ConversationNotificationChannels.ensureConversationChannels(context, shortcut, isDm = true)
        val child =
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.DIRECT_MESSAGES.id,
                shortcut,
            )
        assertEquals(NotificationTone.DING.uri(context), manager.getNotificationChannel(child).sound)
        val profile =
            ProfileNotificationChannels(context).customize(
                "synthetic-account",
                "a".repeat(64),
                "Test person",
                ConversationVibrationPattern.SYSTEM_DEFAULT,
                ConversationVibrationPattern.SYSTEM_DEFAULT,
            )
        assertEquals(NotificationTone.DING.uri(context), manager.getNotificationChannel(profile).sound)
    }

    @Test
    fun retainedVersionOneUrisAlwaysResolveToTheOriginalWaveforms() {
        val provider =
            Robolectric.setupContentProvider(
                NotificationToneProvider::class.java,
                "${context.packageName}.notification-tones",
            )
        val retained =
            listOf(
                Triple(
                    NotificationTone.DING,
                    "ding-v1",
                    "29c4fae0fb9ef09032fdd3bf72d541d1e89f966fabf0b3f246df7047b514c884",
                ),
                Triple(
                    NotificationTone.PULSE,
                    "pulse-v1",
                    "673393abf8cdccbebca572d394d4e9ceca5b19c9157ae3527a163f82151193ca",
                ),
            )
        retained.forEach { (tone, token, expectedHash) ->
            val uri = Uri.parse("content://${context.packageName}.notification-tones/$token")
            assertEquals(token, tone.token)
            assertEquals(tone, NotificationTone.resolve(context, uri))
            val bytes =
                provider.openAssetFile(uri, "r").use {
                    it.createInputStream().use { stream -> stream.readBytes() }
                }
            val hash =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(expectedHash, hash)
        }
    }

    @Test
    fun providerExposesOnlyPermanentPublicAudioAndRejectsPathsAndWrites() {
        val provider =
            Robolectric.setupContentProvider(
                NotificationToneProvider::class.java,
                "${context.packageName}.notification-tones",
            )
        NotificationTone.entries.forEach { tone ->
            val uri = tone.uri(context)
            assertEquals(
                android.webkit.MimeTypeMap
                    .getSingleton()
                    .getMimeTypeFromExtension("wav") ?: "audio/x-wav",
                provider.getType(uri),
            )
            provider.query(uri, arrayOf(android.provider.MediaStore.Audio.Media.TITLE), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(context.getString(tone.title), it.getString(0))
            }
            val expected = context.resources.openRawResource(tone.audio).use { it.readBytes() }
            val actual =
                provider.openAssetFile(uri, "r").use {
                    it.createInputStream().use { stream -> stream.readBytes() }
                }
            assertTrue(expected.contentEquals(actual))
            assertEquals("RIFF", actual.take(4).map { it.toInt().toChar() }.joinToString(""))
            org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) {
                provider.openAssetFile(uri, "rw")
            }
            org.junit.Assert.assertThrows(UnsupportedOperationException::class.java) {
                provider.delete(uri, null, null)
            }
        }
        listOf("../ding-v1", "ding-v1?secret=1", "ding-v1/", "%2e%2e", "unknown").forEach {
            val uri = Uri.parse("content://${context.packageName}.notification-tones/$it")
            assertNull(provider.getType(uri))
            org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) {
                provider.openAssetFile(uri, "r")
            }
        }
        assertNull(provider.getType(Uri.parse("content://other.app.notification-tones/ding-v1")))
    }
}
