package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SensitiveClipboardTest {
    @Test
    @Config(sdk = [36])
    fun clearsPrimaryClipOnModernAndroid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)!!
        clipboard.setPrimaryClip(ClipData.newPlainText("nsec", "nsec1secret"))

        clipboard.clearSensitivePrimaryClip()

        assertNull(clipboard.primaryClipPlainText(context))
    }

    @Test
    fun prePPolicyReplacesPrimaryClipWithEmptyText() {
        var cleared = 0
        var replaced = 0

        clearSensitivePrimaryClipForSdk(
            sdkInt = 27,
            clearPrimaryClip = { cleared += 1 },
            replaceWithEmptyClip = { replaced += 1 },
        )

        assertEquals(0, cleared)
        assertEquals(1, replaced)
    }
}
