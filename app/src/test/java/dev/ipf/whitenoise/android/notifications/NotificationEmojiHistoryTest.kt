package dev.ipf.whitenoise.android.notifications

import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationEmojiHistoryTest {
    private val sender =
        Person
            .Builder()
            .setName("Alice")
            .setKey("alice")
            .build()

    @Test
    fun artworkAndAccessibleTextShareOneLogicalIdentity() {
        val pair = notificationEmojiMessages("hello :wn:", 10L, sender, Uri.parse("content://test/image.png"))
        assertEquals(2, pair.size)
        assertEquals("image/png", pair.first().dataMimeType)
        assertEquals("hello :wn:", pair.last().text.toString())
        assertNull(pair.last().dataUri)
        assertEquals(1, notificationLogicalMessageGroups(pair).size)
        val copied = pair.map { copiedMessage(it, sender, boundedText = true) }
        assertEquals(1, notificationLogicalMessageGroups(copied).size)
        assertEquals(pair.first().dataUri, copied.first().dataUri)
    }

    @Test
    fun replacementRemovesTheWholePairAndPreservesEarlierMessages() {
        val earlier = NotificationCompat.MessagingStyle.Message("earlier", 1L, sender)
        val pair = notificationEmojiMessages(":wn:", 2L, sender, Uri.parse("content://test/art.png"))
        assertEquals(listOf(earlier), dropLastNotificationLogicalMessage(listOf(earlier) + pair))
        assertEquals(
            emptyList<NotificationCompat.MessagingStyle.Message>(),
            dropLastNotificationLogicalMessage(listOf(earlier)),
        )
    }

    @Test
    fun historyNeverExceedsPlatformRowsOrSplitsAPair() {
        val history =
            (1L..25L).flatMap {
                notificationEmojiMessages(":wn:", it, sender, Uri.parse("content://test/$it.png"))
            }
        val capped = capNotificationLogicalHistory(history, logicalCap = 24, rowCap = 23)
        assertEquals(22, capped.size)
        assertEquals(11, notificationLogicalMessageGroups(capped).size)
        assertTrue(notificationLogicalMessageGroups(capped).all { it.size == 2 })
        assertEquals(25L, capped.last().timestamp)
        assertTrue(capNotificationLogicalHistory(history, logicalCap = 0).isEmpty())
    }

    @Test
    fun legacyRowsWithEqualTimesAreNotAccidentallyCombined() {
        val text = NotificationCompat.MessagingStyle.Message("text", 1L, sender)
        val image =
            NotificationCompat.MessagingStyle.Message("image", 1L, sender).setData(
                "image/png",
                Uri.parse("content://test/legacy.png"),
            )
        assertEquals(2, notificationLogicalMessageGroups(listOf(image, text)).size)
        assertEquals(listOf(text), capNotificationLogicalHistory(listOf(image, text), logicalCap = 1))
    }
}
