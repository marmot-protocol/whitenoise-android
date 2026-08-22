package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.buildShareShortcutIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationShortcutsTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    @Test
    fun accountSwitchPlan_removesOnlyDynamicDirectShareExposure() {
        val accountA = shortcut("account-a", "group-a")
        val accountB = shortcut("account-b", "group-b")

        val plan = directShareConversationShortcutCleanupPlan(listOf(accountA, accountB))

        assertEquals(listOf(accountA.id, accountB.id), plan.dynamicIds)
        assertTrue(plan.longLivedIds.isEmpty())
        assertTrue(plan.disabledIds.isEmpty())
    }

    @Test
    fun partialAccountRemovalPlan_preservesOtherAccountAndUnscopedLegacyShortcuts() {
        val accountA = shortcut("account-a", "group-a")
        val accountB = shortcut("account-b", "group-b")
        val unscopedLegacy = shortcut("account-b", "legacy", includeAccountScope = false)

        val plan =
            accountConversationShortcutCleanupPlan(
                shortcuts = listOf(accountA, accountB, unscopedLegacy),
                accountRef = "account-a",
                includeUnscopedLegacy = false,
            )

        assertEquals(listOf(accountA.id), plan.dynamicIds)
        assertEquals(listOf(accountA.id), plan.longLivedIds)
        assertEquals(listOf(accountA.id), plan.disabledIds)
    }

    @Test
    fun lastAccountRemovalPlan_removesUnscopedLegacyShortcuts() {
        val accountA = shortcut("account-a", "group-a")
        val unscopedLegacy = shortcut("account-a", "legacy", includeAccountScope = false)

        val plan =
            accountConversationShortcutCleanupPlan(
                shortcuts = listOf(accountA, unscopedLegacy),
                accountRef = "account-a",
                includeUnscopedLegacy = true,
            )

        assertEquals(listOf(accountA.id, unscopedLegacy.id), plan.dynamicIds)
        assertEquals(listOf(accountA.id, unscopedLegacy.id), plan.longLivedIds)
        assertEquals(
            "owned and legacy pinned copies must be disabled when no other account can own them",
            listOf(accountA.id, unscopedLegacy.id),
            plan.disabledIds,
        )
    }

    @Test
    fun accountRemovalPlan_disablesOwnedShortcutForPinnedCopies() {
        val owned = shortcut("account-a", "pinned")

        val plan =
            accountConversationShortcutCleanupPlan(
                shortcuts = listOf(owned),
                accountRef = "account-a",
                includeUnscopedLegacy = false,
            )

        assertEquals(
            "owned IDs must be disabled so Android invalidates any user-pinned copy",
            listOf(owned.id),
            plan.disabledIds,
        )
    }

    @Test
    fun accountRemovalPlan_doesNotDisableAnotherAccountsPinnedCopy() {
        val retained = shortcut("account-b", "pinned")

        val plan =
            accountConversationShortcutCleanupPlan(
                shortcuts = listOf(retained),
                accountRef = "account-a",
                includeUnscopedLegacy = false,
            )

        assertTrue(plan.disabledIds.isEmpty())
    }

    @Test
    fun hideConversationShortcutsFromDirectShare_removesDynamicTargets() {
        val shortcut = shortcut("acct", "group")
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

        hideConversationShortcutsFromDirectShare(context)

        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == shortcut.id })
    }

    @Test
    fun conversationShortcutIsRich_requiresLocusId() {
        val shortcutId = conversationShortcutId("acct", "group")!!
        val rich =
            ShortcutInfoCompat
                .Builder(context, shortcutId)
                .setShortLabel("Chat")
                .setLongLabel("Chat")
                .setIntent(buildShareShortcutIntent(context))
                .setLocusId(LocusIdCompat(shortcutId))
                .setPerson(Person.Builder().setName("Alice").build())
                .build()
        assertTrue(conversationShortcutIsRich(rich))
        val basic =
            ShortcutInfoCompat
                .Builder(context, shortcutId)
                .setShortLabel("Chat")
                .setLongLabel("Chat")
                .setIntent(buildShareShortcutIntent(context))
                .build()
        assertFalse(conversationShortcutIsRich(basic))
    }

    private fun shortcut(
        accountRef: String,
        groupId: String,
        includeAccountScope: Boolean = true,
    ): ShortcutInfoCompat {
        val builder =
            ShortcutInfoCompat
                .Builder(context, conversationShortcutId(accountRef, groupId)!!)
                .setShortLabel("Chat")
                .setLongLabel("Chat")
                .setIntent(buildShareShortcutIntent(context))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise))
                .setLongLived(true)
                .setCategories(setOf(CONVERSATION_SHARE_TARGET_CATEGORY))
        if (includeAccountScope) {
            builder.setExtras(checkNotNull(conversationShortcutAccountExtras(accountRef)))
        }
        return builder.build()
    }
}
