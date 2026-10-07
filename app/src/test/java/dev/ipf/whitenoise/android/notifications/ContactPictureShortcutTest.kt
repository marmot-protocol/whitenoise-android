package dev.ipf.whitenoise.android.notifications

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import dev.ipf.whitenoise.android.share.ShareShortcutTarget
import dev.ipf.whitenoise.android.share.buildShareShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    /** A native canonical DM answer upgrades a bare legacy shortcut outside the recent share list. */
    @Test fun firstPrivateSelectionAdoptsOnlyTheProvenLegacyAccountAndConversation() {
        val context = RuntimeEnvironment.getApplication()
        val preview = NotificationPreviewPreferences.capture(context)
        val original =
            checkNotNull(
                buildShareShortcut(
                    context,
                    ShareShortcutTarget("a", "canonical", "Maya"),
                    previewToken = preview,
                ),
            )
        val other =
            checkNotNull(
                buildShareShortcut(
                    context,
                    ShareShortcutTarget("b", "canonical", "Maya"),
                    previewToken = preview,
                ),
            )
        var published = listOf(original, other)
        val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val platform =
            object : ContactPictureShortcutPlatform(context) {
                /** Serves the shortcuts the fake platform currently publishes. */
                override fun read() = published

                /** Replaces published shortcuts by id and keeps the untouched ones. */
                override fun update(shortcuts: List<androidx.core.content.pm.ShortcutInfoCompat>) {
                    val changes = shortcuts.associateBy { it.id }
                    published = published.map { changes[it.id] ?: it }
                }
            }
        refreshContactPictureShortcuts(
            context,
            "a",
            "contact",
            { red },
            { true },
            platform,
            legacyConversationId = "wrong",
        )
        assertNotEquals(Color.RED, checkNotNull(published[0].icon?.loadDrawable(context)).toBitmap().getPixel(0, 0))
        refreshContactPictureShortcuts(
            context,
            "a",
            "contact",
            { red },
            { true },
            platform,
            legacyConversationId = "canonical",
        )
        assertEquals(Color.RED, checkNotNull(published[0].icon?.loadDrawable(context)).toBitmap().getPixel(0, 0))
        assertNotEquals(Color.RED, checkNotNull(published[1].icon?.loadDrawable(context)).toBitmap().getPixel(0, 0))
    }

    /** Cached OS entries may outlive the publishing process without outliving their privacy epoch. */
    @Test fun refreshAcceptsPreviousSessionWithinSamePrivacyEpoch() {
        assertPreviewRefresh(previousSession = true)
    }

    /** A first private choice may refresh a proven legacy shortcut only before any preview-policy transition. */
    @Test fun firstPictureAcceptsUnstampedLegacyShortcutBeforeAnyPrivacyTransition() {
        assertPreviewRefresh(legacy = true)
    }

    /** Saving a private choice cannot undo a shortcut's explicit redaction. */
    @Test fun explicitRedactionCannotBeReversedBySavingAPicture() {
        assertPreviewRefresh(hidden = true, expectPrivate = false)
    }

    /** Re-enabling previews cannot authorize a shortcut captured under the previous privacy epoch. */
    @Test fun privacyOptOutAndBackInCannotRevivePreviousEpoch() {
        assertPreviewRefresh(staleEpoch = true, expectPrivate = false)
    }

    /** A direct shortcut's Person is rebuilt from its own label; extras carry no name and groups set no Person. */
    @Test fun refreshedPersonsComeFromTheShortcutLabelOnly() {
        val context = RuntimeEnvironment.getApplication()
        val preview = NotificationPreviewPreferences.capture(context)
        val dmTarget = ShareShortcutTarget("a", "dm", "Maya")
        val dm = checkNotNull(buildShareShortcut(context, dmTarget, previewToken = preview))
        val groupTarget = ShareShortcutTarget("a", "group", "Hikers")
        val group = checkNotNull(buildShareShortcut(context, groupTarget, previewToken = preview))
        stampContactPictureShortcut(dm, "contact", conversationIcon = true)
        stampContactPictureShortcut(group, "contact", conversationIcon = false)
        val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val refreshedDm = withContactPictureIcon(context, dm, "contact", red)
        val refreshedGroup = withContactPictureIcon(context, group, "contact", red)
        val dmPerson = checkNotNull(refreshedDm.persons()).single()
        assertEquals("Maya", dmPerson.name?.toString())
        assertEquals("contact", dmPerson.key)
        assertNull(refreshedGroup.persons())
        listOf(dm, group, refreshedDm, refreshedGroup).forEach { shortcut ->
            assertTrue(checkNotNull(shortcut.extras).keySet().none { it.endsWith(".name") || it.endsWith(".uri") })
        }
    }

    /** Runs the production icon-refresh path against a controlled preview epoch and captured launcher writes. */
    private fun assertPreviewRefresh(
        previousSession: Boolean = false,
        legacy: Boolean = false,
        hidden: Boolean = false,
        staleEpoch: Boolean = false,
        expectPrivate: Boolean = true,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val preview = NotificationPreviewPreferences.capture(context)
        var shortcut =
            checkNotNull(
                buildShareShortcut(context, ShareShortcutTarget("a", "canonical", "Maya"), previewToken = preview),
            )
        val extras = checkNotNull(shortcut.extras)
        if (legacy) {
            extras.remove(NotificationPreviewPreferences.EXTRA_SESSION)
            extras.remove(NotificationPreviewPreferences.EXTRA_REVISION)
            extras.remove(NotificationPreviewPreferences.EXTRA_ALLOWED)
        } else {
            stampContactPictureShortcut(shortcut, "contact", true)
        }
        if (previousSession) extras.putString(NotificationPreviewPreferences.EXTRA_SESSION, "old-process")
        if (hidden) extras.putBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN, true)
        if (staleEpoch) {
            kotlinx.coroutines.runBlocking {
                NotificationPreviewPreferences.setEnabled(context, false) { true }
                NotificationPreviewPreferences.setEnabled(context, true) { true }
            }
        }
        val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val platform =
            object : ContactPictureShortcutPlatform(context) {
                /** Serves the single published shortcut. */
                override fun read() = listOf(shortcut)

                /** Stores the single rewritten shortcut. */
                override fun update(shortcuts: List<androidx.core.content.pm.ShortcutInfoCompat>) {
                    shortcut = shortcuts.single()
                }
            }
        refreshContactPictureShortcuts(
            context,
            "a",
            "contact",
            { red },
            { true },
            platform,
            legacyConversationId = "canonical",
        )
        val pixel = checkNotNull(shortcut.icon?.loadDrawable(context)).toBitmap().getPixel(0, 0)
        if (expectPrivate) {
            assertEquals(Color.RED, pixel)
            assertEquals(true, shortcutPreviewAllowed(context, shortcut))
        } else {
            assertNotEquals(Color.RED, pixel)
            assertEquals(context.getString(dev.ipf.whitenoise.android.R.string.app_name), shortcut.shortLabel)
        }
    }

    /** Replacing or clearing one contact picture refreshes only that owner's published shortcut pixels. */
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
                /** Serves the shortcuts the fake platform currently publishes. */
                override fun read() = published

                /** Replaces published shortcuts by id and keeps the untouched ones. */
                override fun update(shortcuts: List<androidx.core.content.pm.ShortcutInfoCompat>) {
                    val changed = shortcuts.associateBy { it.id }
                    published = published.map { changed[it.id] ?: it }
                }
            }
        refreshContactPictureShortcuts(context, "a", "contact", { blue }, { true }, platform)

        /** Reads the top-left pixel of one published shortcut icon. */
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

    /** Reads the compat shortcut's Person array, which androidx keeps package-private, for assertions. */
    @Suppress("UNCHECKED_CAST")
    private fun androidx.core.content.pm.ShortcutInfoCompat.persons(): Array<androidx.core.app.Person>? =
        androidx.core.content.pm.ShortcutInfoCompat::class.java
            .getDeclaredField("mPersons")
            .apply { isAccessible = true }
            .get(this) as Array<androidx.core.app.Person>?
}
