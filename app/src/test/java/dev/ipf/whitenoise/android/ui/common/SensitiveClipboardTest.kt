package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.clearClipboardIfHolding
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
import java.util.concurrent.atomic.AtomicInteger

/** A copied secret leaves the clipboard on its own deadline without removing a clip the user copied later. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [SensitiveClipboardTest.HidingClipboardShadow::class])
class SensitiveClipboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    /** Starts with an empty content-read counter and deterministic WorkManager timing. */
    @Before
    fun useTestWork() {
        HidingClipboardShadow.contentReads.set(0)
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

    /** A replacement with the same label and sensitive flag survives without a content read. */
    @Test
    fun laterClipSurvivesTheDeadline() {
        copySensitiveToClipboard(context, "Private key", SECRET)
        val replacement = ClipData.newPlainText("Private key", OTHER)
        replacement.description.extras =
            PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(replacement)
        reachDeadline()
        assertEquals(0, HidingClipboardShadow.contentReads.get())
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

    /** A URI clip pasted as bunker text is cleared, and replacing it with another clip keeps the new text. */
    @Test
    fun spentBunkerLinkClearsOnlyTheMatchingCoercedClip() {
        clipboard.setPrimaryClip(ClipData.newPlainText("Note", OTHER))
        clearClipboardIfHolding(context, BUNKER)
        assertEquals(OTHER, clipboard.primaryClipPlainText(context))

        clipboard.setPrimaryClip(ClipData.newRawUri("Remote signer", Uri.parse(BUNKER)))
        assertEquals(BUNKER, clipboard.primaryClipPlainText(context))
        clearClipboardIfHolding(context, BUNKER)
        assertFalse(clipboard.hasPrimaryClip())
    }

    /** A hidden clipboard gives no evidence that it still holds the spent bunker link. */
    @Test
    fun spentBunkerLinkKeepsAnUnreadableClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("Remote signer", BUNKER))
        HidingClipboardShadow.hidden = true
        clearClipboardIfHolding(context, BUNKER)
        HidingClipboardShadow.hidden = false
        assertEquals(BUNKER, clipboard.primaryClipPlainText(context))
    }

    private fun pendingClear(): WorkInfo = pendingSensitiveClear(context)

    private fun reachDeadline() = reachSensitiveClipDeadline(context)

    /** Models Android returning no clip to an app without input focus while a clip still exists. */
    @Implements(ClipboardManager::class)
    class HidingClipboardShadow : ShadowClipboardManager() {
        /** Counts content reads separately from description queries, as Android does. */
        @Implementation
        override fun getPrimaryClip(): ClipData? {
            contentReads.incrementAndGet()
            return if (hidden) null else super.getPrimaryClip()
        }

        /** Models Android hiding clipboard metadata at the same time as the clip. */
        @Implementation
        override fun getPrimaryClipDescription(): ClipDescription? {
            val description = super.getPrimaryClipDescription()
            return description.takeUnless { hidden }
        }

        companion object {
            val contentReads = AtomicInteger()

            @Volatile
            var hidden = false
        }
    }

    private companion object {
        const val SECRET = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        const val SECOND_SECRET = "ncryptsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        const val OTHER = "meeting notes"
        const val BUNKER = "bunker://test?relay=wss://signer.example"
    }
}
