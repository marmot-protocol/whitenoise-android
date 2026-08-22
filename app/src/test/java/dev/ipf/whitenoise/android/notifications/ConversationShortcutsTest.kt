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
    }

    @Test
    fun accountRemovalPlan_isScopedAndPreservesOtherAndLegacyShortcuts() {
        val accountA = shortcut("account-a", "group-a")
        val accountB = shortcut("account-b", "group-b")
        val legacy = shortcut("account-a", "legacy", includeAccountScope = false)

        val plan = accountConversationShortcutCleanupPlan(listOf(accountA, accountB, legacy), "account-a")

        assertEquals(listOf(accountA.id), plan.dynamicIds)
        assertEquals(listOf(accountA.id), plan.longLivedIds)
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
