package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.IntentSender
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

/** Platform responses are scripted; actual shortcut objects and private credential storage are exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PinnedConversationShortcutsTest {
    /**
     * Runs production encrypted persistence with a deterministic test key instead of an unavailable Android
     * Keystore.
     */
    @Before fun installTestKey() {
        setPinnedConversationTestKey(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
    }

    /** Prevents the fixture's key from leaking into another sandbox or provider-failure scenario. */
    @After fun releaseTestKey() {
        setPinnedConversationTestKey(null)
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val tokens get() = PinnedConversationTokens.create(context)
    private val capability get() = requireNotNull(tokens.issue(ACCOUNT, GROUP))

    /** Unsupported launchers never receive a request. */
    @Test
    fun unsupportedAndStaleOwnerDoNotRequestAPin() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        platform.supported = false
        assertEquals(ConversationPinResult.UNSUPPORTED, owner.request(capability, "Friends", null) { true })
        platform.supported = true
        assertEquals(ConversationPinResult.UNAVAILABLE, owner.request(capability, "Friends", null) { false })
        assertTrue(platform.requests.isEmpty())
    }

    /** Denial and accepted-but-unapproved requests never create an app-owned phantom pinned state. */
    @Test
    fun deniedRequestAndProcessRecreationUseLauncherInventory() {
        val platform = Platform(context)
        val cap = capability
        platform.accepted = false
        assertEquals(
            ConversationPinResult.FAILED,
            PinnedConversationShortcuts(context, platform).request(cap, "Friends", null) { true },
        )
        platform.accepted = true
        assertEquals(
            ConversationPinResult.REQUESTED,
            PinnedConversationShortcuts(context, platform).request(capability, "Friends", null) { true },
        )
        assertEquals(listOf(cap.shortcutId, cap.shortcutId), platform.requests.map { it.id })
        assertTrue(platform.inventory.isEmpty())
        platform.approve(platform.requests.last())
        assertEquals(
            ConversationPinResult.ALREADY_PINNED,
            PinnedConversationShortcuts(context, platform).request(capability, "Friends", null) { true },
        )
        assertEquals(2, platform.requests.size)
        assertEquals(
            ConversationPinResult.ALREADY_PINNED,
            PinnedConversationShortcuts(context, platform).request(capability, "Renamed", null) { true },
        )
        assertEquals(
            "Renamed",
            platform.updates
                .last()
                .single()
                .longLabel,
        )
        assertEquals(2, platform.requests.size)
    }

    /** A pending approval arriving after deletion disables its old ID without touching the recreated pin. */
    @Test
    fun staleCallbackCannotRecreateOrDisableTheNewIncarnation() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val old = capability
        owner.request(old, "Old conversation", null) { true }
        val oldRequest = platform.requests.single()
        owner.removeGroup(ACCOUNT, GROUP)
        assertFalse(tokens.isValid(old))
        val replacement = capability
        assertNotEquals(old.shortcutId, replacement.shortcutId)
        owner.request(replacement, "New conversation", null) { true }
        platform.approve(platform.requests.last())
        platform.approve(oldRequest)
        owner.approved(old)
        owner.approved(old)
        assertTrue(old.shortcutId in platform.disabled)
        assertFalse(replacement.shortcutId in platform.disabled)
        assertTrue(tokens.isValid(replacement))
        assertEquals(2, platform.requests.size)
    }

    /** An opt-out between request and launcher approval cannot restore a private conversation label. */
    @Test
    fun approvalAfterPrivacyChangeScrubsTheLateShortcut() =
        runBlocking {
            val platform = Platform(context)
            val owner = PinnedConversationShortcuts(context, platform)
            val cap = capability
            owner.request(cap, "Private group", null) { true }
            val original = platform.requests.single()
            NotificationPreviewPreferences.setEnabled(context, false) { true }
            platform.approve(original)
            owner.approved(cap)
            val scrubbed = platform.updates.last().single()
            assertEquals(context.getString(R.string.app_name), scrubbed.longLabel)
            assertEquals(original.intent.data, scrubbed.intent.data)
            assertFalse(scrubbed.intent.toUri(0).contains("Private group"))
        }

    /** Rich refresh failure attempts a generic label while preserving the same validated destination intent. */
    @Test
    fun refreshFailureFallsBackToGenericAndKeepsExactRouting() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val cap = capability
        owner.request(cap, "Old label", null) { true }
        platform.approve(platform.requests.single())
        platform.failNextUpdate = true
        assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to PinnedConversationPresentation("New label"))))
        assertEquals(
            "New label",
            platform.updates
                .first()
                .single()
                .longLabel,
        )
        val generic = platform.updates.last().single()
        assertEquals(context.getString(R.string.app_name), generic.longLabel)
        assertEquals(cap.shortcutId, generic.id)
        assertTrue(tokens.isValid(requireNotNull(PinnedConversationNavigation.capability(generic.intent))))
    }

    /** A projection miss redacts only the requested account; switching accounts does not retarget existing pins. */
    @Test
    fun missingProjectionIsGenericAndOtherAccountsAreUntouched() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val first = capability
        val second = requireNotNull(tokens.issue("another-account", GROUP))
        owner.request(first, "First", null) { true }
        platform.approve(platform.requests.last())
        owner.request(second, "Second", null) { true }
        platform.approve(platform.requests.last())
        assertTrue(owner.refresh(ACCOUNT, emptyMap()))
        val updated = platform.updates.single().single()
        assertEquals(first.shortcutId, updated.id)
        assertEquals(context.getString(R.string.app_name), updated.longLabel)
        assertTrue(tokens.isValid(second))
    }

    /** Opted-out publication reveals neither a title nor avatar while keeping an exact account/group intent. */
    @Test
    fun privacyDisabledRequestUsesGenericPresentation() =
        runBlocking {
            NotificationPreviewPreferences.setEnabled(context, false) { true }
            val platform = Platform(context)
            val cap = capability
            val owner = PinnedConversationShortcuts(context, platform)
            owner.request(cap, "Private group", "https://private.example/avatar") { true }
            val requested = platform.requests.single()
            assertEquals(context.getString(R.string.app_name), requested.longLabel)
            assertTrue(requested.extras!!.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN))
            assertEquals(cap.groupIdHex, PinnedConversationNavigation.capability(requested.intent)?.groupIdHex)
        }

    /** Cached pictures are bounded; subsequent projection refreshes use the current title and pixels. */
    @Test
    fun cachedAvatarsAreBoundedAndRefreshFromTheCurrentProjection() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val cap = capability
        val original = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        assertEquals(
            ConversationPinResult.REQUESTED,
            owner.request(cap, "Friends", null, original) { true },
        )
        val requested = platform.requests.single()
        val icon = (requested.icon!!.loadDrawable(context) as BitmapDrawable).bitmap
        assertEquals(192, icon.width)
        assertEquals(96, icon.height)
        assertEquals(1200, original.width)
        platform.approve(requested)
        val updated = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to PinnedConversationPresentation("Current name", updated))))
        val refresh = platform.updates.single().single()
        assertEquals("Current name", refresh.longLabel)
        assertEquals(40, (refresh.icon!!.loadDrawable(context) as BitmapDrawable).bitmap.width)
        assertEquals(requested.intent.data, refresh.intent.data)
    }

    /** A failed label scrub cannot skip disabling a removed pin, including a repeated late approval. */
    @Test
    fun scrubFailureStillDisablesRemovedPinsAndLateApprovals() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val cap = capability
        owner.request(cap, "Private group", null) { true }
        platform.approve(platform.requests.single())
        platform.failNextUpdate = true
        assertTrue(runCatching { owner.removeGroup(ACCOUNT, GROUP) }.isFailure)
        assertFalse(tokens.isValid(cap))
        assertTrue(cap.shortcutId in platform.disabled)
        platform.disabled.clear()
        platform.failNextUpdate = true
        assertTrue(runCatching { owner.approved(cap) }.isFailure)
        assertTrue(cap.shortcutId in platform.disabled)
        assertEquals(1, platform.requests.size)
    }

    /** The existing account-cleanup entry point revokes even requests absent from the OS inventory. */
    @Test
    fun accountCleanupRevokesPendingRequestWithoutAffectingAnotherAccount() {
        val removed = capability
        val kept = requireNotNull(tokens.issue("another-account", GROUP))
        clearConversationShortcutsForAccount(context, ACCOUNT, includeUnscopedLegacy = false)
        assertFalse(tokens.isValid(removed))
        assertTrue(tokens.isValid(kept))
    }

    /** Keeps Android's real pinned flags while scripting approval timing and failed update responses. */
    private class Platform(
        private val context: Context,
    ) : PinnedShortcutPlatform {
        var supported = true
        var accepted = true
        var failNextUpdate = false
        var inventory = emptyList<ShortcutInfoCompat>()
        val requests = mutableListOf<ShortcutInfoCompat>()
        val updates = mutableListOf<List<ShortcutInfoCompat>>()
        val disabled = mutableListOf<String>()

        override fun supported(): Boolean = supported

        override fun shortcuts(): List<ShortcutInfoCompat> = inventory

        override fun request(
            shortcut: ShortcutInfoCompat,
            callback: IntentSender,
        ): Boolean {
            requests += shortcut
            return accepted
        }

        override fun update(shortcuts: List<ShortcutInfoCompat>): Boolean {
            updates += shortcuts
            if (failNextUpdate) {
                failNextUpdate = false
                error("scripted launcher update failure")
            }
            return true
        }

        override fun disable(ids: List<String>) {
            disabled += ids
        }

        /** Only this explicit fixture approval makes the shortcut appear in Android's pinned inventory. */
        fun approve(shortcut: ShortcutInfoCompat) {
            context.getSystemService(ShortcutManager::class.java).requestPinShortcut(shortcut.toShortcutInfo(), null)
            inventory = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
        }
    }

    private companion object {
        const val ACCOUNT = "test-account"
        val GROUP = "ab".repeat(32)
    }
}
