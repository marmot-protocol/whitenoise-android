package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowClipboardManager

/** A copied secret leaves the clipboard on its own deadline without removing a clip the user copied later. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [SensitiveClipboardTest.HidingClipboardShadow::class])
class SensitiveClipboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    @Before
    fun useTestWork() {
        useTestWorkManager(context)
    }

    @After
    fun showClipboard() {
        HidingClipboardShadow.hidden = false
    }

    /** The clip is marked sensitive and stays until the deadline, then the app removes it. */
    @Test
    fun copiedSecretIsMarkedSensitiveAndClearedAtTheDeadline() {
        copySensitiveToClipboard(context, "Private key", SECRET)
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals(SECRET, clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)

        val pending = pendingClear()
        assertEquals(SENSITIVE_CLIP_LIFETIME_MS, pending.initialDelayMillis)
        assertEquals(
            SECRET,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
        reachDeadline()
        assertFalse(clipboard.hasPrimaryClip())
    }

    /** Text the user copied after the secret belongs to them and survives the deadline. */
    @Test
    fun laterClipSurvivesTheDeadline() {
        copySensitiveToClipboard(context, "Private key", SECRET)
        clipboard.setPrimaryClip(ClipData.newPlainText("Note", OTHER))
        reachDeadline()
        assertEquals(
            OTHER,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }

    /** A second secret copy restarts the deadline instead of being cleared by the first one. */
    @Test
    fun secondCopyRestartsTheDeadline() {
        copySensitiveToClipboard(context, "Private key", SECRET)
        val first = pendingClear().id
        copySensitiveToClipboard(context, "Encrypted backup", SECOND_SECRET)
        val replacement = pendingClear()
        assertNotEquals(first, replacement.id)
        assertEquals(SENSITIVE_CLIP_LIFETIME_MS, replacement.initialDelayMillis)
        val firstState =
            WorkManager
                .getInstance(context)
                .getWorkInfoById(first)
                .get()
                ?.state
        assertTrue(firstState == null || firstState == WorkInfo.State.CANCELLED)
        assertEquals(
            SECOND_SECRET,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
        reachDeadline()
        assertFalse(clipboard.hasPrimaryClip())
    }

    /** When Android hides the clip from a background app, the app cannot tell whose it is and clears it. */
    @Test
    fun unreadableClipboardIsClearedAnyway() {
        copySensitiveToClipboard(context, "Private key", SECRET)
        HidingClipboardShadow.hidden = true
        reachDeadline()
        HidingClipboardShadow.hidden = false
        assertFalse(clipboard.hasPrimaryClip())
    }

    private fun pendingClear(): WorkInfo = pendingSensitiveClear(context)

    private fun reachDeadline() = reachSensitiveClipDeadline(context)

    /** Models Android returning no clip to an app without input focus while a clip still exists. */
    @Implements(ClipboardManager::class)
    class HidingClipboardShadow : ShadowClipboardManager() {
        @Implementation
        override fun getPrimaryClip(): ClipData? = if (hidden) null else super.getPrimaryClip()

        companion object {
            @Volatile
            var hidden = false
        }
    }

    private companion object {
        const val SECRET = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        const val SECOND_SECRET = "ncryptsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        const val OTHER = "meeting notes"
    }
}
