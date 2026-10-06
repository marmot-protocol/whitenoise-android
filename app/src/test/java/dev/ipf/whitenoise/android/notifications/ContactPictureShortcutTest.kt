package dev.ipf.whitenoise.android.notifications

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import dev.ipf.whitenoise.android.share.ShareShortcutTarget
import dev.ipf.whitenoise.android.share.buildShareShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Platform shortcuts retain routing while replacement and clear revoke every previously published private icon. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ContactPictureShortcutTest {
    @Test fun replacementAndClearRefreshPublishedShortcutsWithoutCrossingAccounts() {
        val context = RuntimeEnvironment.getApplication()
        val preview = NotificationPreviewPreferences.capture(context)
        val first =
            checkNotNull(buildShareShortcut(context, ShareShortcutTarget("a", "group", "Maya"), previewToken = preview))
        val other =
            checkNotNull(buildShareShortcut(context, ShareShortcutTarget("b", "group", "Maya"), previewToken = preview))
        listOf(first, other).forEach { stampContactPictureShortcut(it, "contact", true) }
        val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        var published = listOf(first, other).map { withContactPictureIcon(context, it, "contact", red) }
        val platform =
            object : ContactPictureShortcutPlatform(context) {
                override fun read() = published

                override fun update(shortcuts: List<androidx.core.content.pm.ShortcutInfoCompat>) {
                    val changed = shortcuts.associateBy { it.id }
                    published = published.map { changed[it.id] ?: it }
                }
            }
        refreshContactPictureShortcuts(context, "a", "contact", { blue }, { true }, platform)

        fun pixel(id: String): Int =
            checkNotNull(published.single { it.id == id }.icon?.loadDrawable(context))
                .toBitmap()
                .getPixel(0, 0)
        assertEquals(Color.BLUE, pixel(first.id))
        assertEquals(Color.RED, pixel(other.id))
        refreshContactPictureShortcuts(context, "a", "contact", { null }, { false }, platform)
        assertEquals(Color.BLUE, pixel(first.id))
        refreshContactPictureShortcuts(context, "a", "contact", { null }, { true }, platform)
        assertNotEquals(Color.BLUE, pixel(first.id))
        assertEquals(Color.RED, pixel(other.id))
        val refreshed = published.single { it.id == first.id }
        assertEquals(first.intent.action, refreshed.intent.action)
        assertEquals(first.categories, refreshed.categories)
        assertEquals(
            first.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA),
            refreshed.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA),
        )
    }
}
