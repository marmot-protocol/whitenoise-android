package dev.ipf.whitenoise.android.ui.settings

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.NotificationTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers

/** The actual OS channel's ability to alert wins over its retained sound URI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class NotificationSoundLabelsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun silentBlockedAndAppDisabledChannelsNeverAdvertiseDing() {
        channel(
            NotificationChannelSpec.DIRECT_MESSAGES,
            NotificationManager.IMPORTANCE_LOW,
            NotificationTone.DING.uri(context),
        )
        channel(
            NotificationChannelSpec.GROUP_MESSAGES,
            NotificationManager.IMPORTANCE_NONE,
            NotificationTone.DING.uri(context),
        )
        channel(
            NotificationChannelSpec.MENTIONS,
            NotificationManager.IMPORTANCE_HIGH,
            NotificationTone.DING.uri(context),
        )
        channel(
            NotificationChannelSpec.REACTIONS,
            NotificationManager.IMPORTANCE_MIN,
            NotificationTone.DING.uri(context),
        )
        channel(NotificationChannelSpec.INVITES, NotificationManager.IMPORTANCE_HIGH, null)
        val labels = readNotificationSoundLabels(context)
        assertEquals("Silent", labels[NotificationChannelSpec.DIRECT_MESSAGES])
        assertEquals("Off", labels[NotificationChannelSpec.GROUP_MESSAGES])
        assertEquals("White Noise Ding", labels[NotificationChannelSpec.MENTIONS])
        assertEquals("Silent", labels[NotificationChannelSpec.REACTIONS])
        assertEquals("Silent", labels[NotificationChannelSpec.INVITES])
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(setOf("Off"), readNotificationSoundLabels(context).values.toSet())
    }

    @Test
    fun blockedAndroidChannelGroupShowsOffDespiteRetainedImportanceAndSound() {
        val group = NotificationChannelGroup("global_notification_defaults", "Global defaults")
        ReflectionHelpers.setField(group, "mBlocked", true)
        manager.createNotificationChannelGroup(group)
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannelSpec.DIRECT_MESSAGES.id,
                "DMs",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                this.group = group.id
                setSound(NotificationTone.DING.uri(context), NotificationTone.audioAttributes)
            },
        )
        assertEquals(true, manager.getNotificationChannelGroup(group.id).isBlocked)
        assertEquals("Off", readNotificationSoundLabels(context)[NotificationChannelSpec.DIRECT_MESSAGES])
    }

    @Test
    fun transientServiceFailureRetainsLabelsAndCancellationStillStopsTheRefresh() {
        val previous = mapOf(NotificationChannelSpec.DIRECT_MESSAGES to "White Noise Ding")
        val changed = mapOf(NotificationChannelSpec.DIRECT_MESSAGES to "Silent")
        assertEquals(changed, notificationSoundLabelsOrPrevious(previous) { changed })
        assertSame(
            previous,
            notificationSoundLabelsOrPrevious(previous) {
                throw IllegalStateException("Binder unavailable")
            },
        )
        val cancellation = kotlinx.coroutines.CancellationException("Screen left")
        val thrown =
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                notificationSoundLabelsOrPrevious(previous) { throw cancellation }
            }
        assertSame(cancellation, thrown)
    }

    @Test
    fun namedColumnsBeatUnrelatedIdsAndDefaultUriGetsHonestFallback() {
        val provider = MetadataProvider()
        ShadowContentResolver.registerProviderInternal("tone-labels", provider)
        val uri = Uri.parse("content://tone-labels/current")
        channel(NotificationChannelSpec.DIRECT_MESSAGES, NotificationManager.IMPORTANCE_HIGH, uri)
        channel(
            NotificationChannelSpec.GROUP_MESSAGES,
            NotificationManager.IMPORTANCE_HIGH,
            Settings.System.DEFAULT_NOTIFICATION_URI,
        )
        assertEquals("Actual tone", readNotificationSoundLabels(context)[NotificationChannelSpec.DIRECT_MESSAGES])
        assertEquals("Default", readNotificationSoundLabels(context)[NotificationChannelSpec.GROUP_MESSAGES])
        provider.title = ""
        assertEquals("Named audio.wav", readNotificationSoundLabels(context)[NotificationChannelSpec.DIRECT_MESSAGES])
        provider.onlyUnrelatedColumns = true
        assertEquals("Android sound", readNotificationSoundLabels(context)[NotificationChannelSpec.DIRECT_MESSAGES])
    }

    private fun channel(
        spec: NotificationChannelSpec,
        importance: Int,
        sound: Uri?,
    ) {
        manager.createNotificationChannel(
            NotificationChannel(spec.id, spec.id, importance).apply {
                setSound(sound, null)
            },
        )
    }

    private class MetadataProvider : ContentProvider() {
        var title = "Actual tone"
        var onlyUnrelatedColumns = false

        override fun onCreate() = true

        override fun getType(uri: Uri) = "audio/x-wav"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            if (onlyUnrelatedColumns) {
                MatrixCursor(arrayOf("_id", "value")).apply { addRow(arrayOf("42", "unrelated")) }
            } else {
                MatrixCursor(arrayOf("_id", OpenableColumns.DISPLAY_NAME, MediaStore.Audio.Media.TITLE)).apply {
                    addRow(arrayOf("42", "Named audio.wav", title))
                }
            }

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = error("Read only")

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = error("Read only")

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = error("Read only")
    }
}
