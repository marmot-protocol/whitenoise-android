package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.buildShareShortcutIntent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowShortcutManager

/** Account cleanup removes unpinned shortcuts even when the launcher refuses to redact a pinned one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [FailingUpdateShortcutManager::class])
class PinnedRedactionFailureCleanupTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val accountScope = checkNotNull(conversationShortcutAccountScope("account-a"))

    /** Starts each case with no shortcuts and working redaction. */
    @Before
    fun setUp() {
        FailingUpdateShortcutManager.failUpdates = false
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        shadowOf(context.getSystemService(ShortcutManager::class.java)).setIsRequestPinShortcutSupported(true)
    }

    /** Restores working redaction for later tests. */
    @After
    fun tearDown() {
        FailingUpdateShortcutManager.failUpdates = false
    }

    /**
     * After the channels are deleted, a failed pinned redaction keeps that pin, still labelled and enabled, for the
     * retention scan to retry. The account's unpinned shortcut is still removed now.
     */
    @Test
    fun failedPinnedRedactionStillRemovesUnpinnedShortcuts() {
        val unpinned = checkNotNull(conversationShortcutId("account-a", "group-unpinned"))
        val pinned = checkNotNull(conversationShortcutId("account-a", "group-pinned"))
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(unpinned, "Unpinned Chat"))
        ShortcutManagerCompat.requestPinShortcut(context, shortcut(pinned, "Pinned Chat"), null)
        assertTrue(pinnedShortcuts().any { it.id == pinned && it.isPinned })

        FailingUpdateShortcutManager.failUpdates = true
        clearRevokedConversationShortcutsForAccount(context, "account-a", includeUnscopedLegacy = false)

        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == unpinned })
        val kept = pinnedShortcuts().single { it.id == pinned }
        assertTrue("an unredacted pin must not be disabled", kept.isEnabled)
        assertEquals("Pinned Chat", kept.shortLabel.toString())
    }

    /** The account's pinned shortcuts as the launcher reports them. */
    private fun pinnedShortcuts(): List<ShortcutInfoCompat> {
        val pinned = ShortcutManagerCompat.FLAG_MATCH_PINNED
        return ShortcutManagerCompat.getShortcuts(context, pinned)
    }

    /** A long-lived conversation shortcut stamped as owned by account-a. */
    private fun shortcut(
        id: String,
        label: String,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat
            .Builder(context, id)
            .setShortLabel(label)
            .setIntent(buildShareShortcutIntent(context))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise))
            .setLongLived(true)
            .setExtras(conversationShortcutAccountScopeExtras(accountScope))
            .build()
}

/** A launcher that refuses shortcut updates while [failUpdates] is set. */
@Implements(ShortcutManager::class)
class FailingUpdateShortcutManager : ShadowShortcutManager() {
    /** Returns false, as a rate-limited or failing launcher does, while [failUpdates] is set. */
    @Implementation
    override fun updateShortcuts(shortcutInfoList: List<ShortcutInfo>): Boolean {
        if (failUpdates) return false
        return super.updateShortcuts(shortcutInfoList)
    }

    companion object {
        @Volatile
        var failUpdates = false
    }
}
