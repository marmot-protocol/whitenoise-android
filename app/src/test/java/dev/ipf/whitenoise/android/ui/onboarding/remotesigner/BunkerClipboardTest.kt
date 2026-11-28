package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.common.plainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Spent-link cleanup never repeats an explicit provider read or clears a later copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BunkerClipboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private var providerReads = 0

    /** A scan or manual entry provides no proof about an unrelated provider clipboard. */
    @Test
    fun cleanupWithoutAPasteLeavesProviderUntouched() {
        copyProviderClip(1)

        clearClipboardIfHolding(context, BUNKER)

        assertEquals(0, providerReads)
        assertTrue(clipboard.hasPrimaryClip())
    }

    /** Only the same provider copy of the submitted value is retired after an explicit paste. */
    @Test
    fun cleanupUsesThePasteIdentityWithoutReopeningTheProvider() {
        copyProviderClip(1)
        val original = recordPaste()

        clearClipboardIfHolding(context, "$BUNKER-edited", original)
        assertTrue(clipboard.hasPrimaryClip())
        copyProviderClip(2)
        clearClipboardIfHolding(context, BUNKER, original)
        assertTrue(clipboard.hasPrimaryClip())
        assertEquals(1, providerReads)

        val replacement = recordPaste()
        clearClipboardIfHolding(context, BUNKER, replacement)
        assertFalse(clipboard.hasPrimaryClip())
        assertEquals(2, providerReads)
    }

    /** Models the stream coercion done by the user-facing Paste button. */
    private fun recordPaste(): BunkerClipboardPaste {
        val clip = requireNotNull(clipboard.primaryClip)
        return requireNotNull(BunkerClipboardPaste.capture(clip, requireNotNull(clip.plainText(context))))
    }

    /** Models Android assigning a copy time even when a provider reuses the same URI. */
    private fun copyProviderClip(timestamp: Long) {
        val item =
            object : ClipData.Item(Uri.parse("content://fixture.signer/link")) {
                /** Counts every time someone asks the clipboard item to open its text representation. */
                override fun coerceToText(context: Context): CharSequence {
                    providerReads++
                    return BUNKER
                }
            }
        val clip = ClipData("Signer link", arrayOf("text/plain"), item)
        clipboard.setPrimaryClip(clip)
        val current = requireNotNull(clipboard.primaryClip)
        ReflectionHelpers.callInstanceMethod<Unit>(
            current.description,
            "setTimestamp",
            ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, timestamp),
        )
    }

    private companion object {
        const val BUNKER = "bunker://test?relay=wss://signer.example"
    }
}
