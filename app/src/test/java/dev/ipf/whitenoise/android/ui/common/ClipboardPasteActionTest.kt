package dev.ipf.whitenoise.android.ui.common

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [PasteClipboardShadow::class])
class ClipboardPasteActionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Before fun resetClipboard() {
        PasteClipboardShadow.clip = null
        PasteClipboardShadow.denied = false
        PasteClipboardShadow.reads = 0
    }

    @Test fun readableClipIsDispatchedExactlyOnce() {
        PasteClipboardShadow.clip = ClipData.newPlainText("", "synthetic input")
        var value: String? = null
        assertTrue(clipboard.withPrimaryClipForPaste { value = it.plainText(context) })
        assertEquals("synthetic input", value)
        assertEquals(1, PasteClipboardShadow.reads)
    }

    @Test fun readableButRejectedInputDoesNotRequestAnotherPaste() {
        PasteClipboardShadow.clip = ClipData.newPlainText("", "not an identifier")
        var validations = 0
        assertTrue(clipboard.withPrimaryClipForPaste { validations++ })
        assertEquals(1, validations)
        assertEquals(1, PasteClipboardShadow.reads)
    }

    @Test fun nullClipDoesNotDispatch() {
        var calls = 0
        assertFalse(clipboard.withPrimaryClipForPaste { calls++ })
        assertEquals(0, calls)
    }

    @Test fun deniedClipDoesNotDispatchAndCanRetryAfterGrant() {
        PasteClipboardShadow.clip = ClipData.newPlainText("", "synthetic input")
        PasteClipboardShadow.denied = true
        var calls = 0
        assertFalse(clipboard.withPrimaryClipForPaste { calls++ })
        assertEquals(0, calls)
        PasteClipboardShadow.denied = false
        assertTrue(clipboard.withPrimaryClipForPaste { calls++ })
        assertEquals(1, calls)
        assertEquals(2, PasteClipboardShadow.reads)
    }

    @Test fun missingClipboardServiceDoesNotDispatch() {
        val missing: ClipboardManager? = null
        assertFalse(missing.withPrimaryClipForPaste { error("Unexpected paste") })
    }
}

@Implements(ClipboardManager::class)
class PasteClipboardShadow {
    @Implementation fun getPrimaryClip(): ClipData? {
        reads++
        if (denied) throw SecurityException("Clipboard access denied")
        return clip
    }

    companion object {
        var clip: ClipData? = null
        var denied = false
        var reads = 0
    }
}
