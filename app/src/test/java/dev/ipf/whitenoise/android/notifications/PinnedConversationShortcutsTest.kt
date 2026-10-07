package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.IntentSender
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
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
import java.util.concurrent.atomic.AtomicLong
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

    /** A bounded projection miss retains current authorized metadata without retargeting another account. */
    @Test
    fun missingProjectionPreservesCurrentPinsAndOtherAccountsAreUntouched() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val first = capability
        val second = requireNotNull(tokens.issue("another-account", GROUP))
        owner.request(first, "First", null) { true }
        platform.approve(platform.requests.last())
        owner.request(second, "Second", null) { true }
        platform.approve(platform.requests.last())
        assertTrue(owner.refresh(ACCOUNT, emptyMap()))
        assertTrue(platform.updates.isEmpty())
        assertEquals("First", platform.inventory.single { it.id == first.shortcutId }.longLabel)
        assertEquals("Second", platform.inventory.single { it.id == second.shortcutId }.longLabel)
        assertTrue(platform.disabled.isEmpty())
        assertTrue(tokens.isValid(first))
        assertTrue(tokens.isValid(second))
    }

    /** A failed rich update cannot scrub a valid omitted pin through the generic fallback batch. */
    @Test
    fun mixedProjectionFailureUpdatesOnlyThePreparedSubset() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val retained = capability
        val visibleGroup = "cd".repeat(16)
        val visible = requireNotNull(tokens.issue(ACCOUNT, visibleGroup))
        owner.request(retained, "Retained", null) { true }
        platform.approve(platform.requests.last())
        owner.request(visible, "Old label", null) { true }
        platform.approve(platform.requests.last())
        platform.failNextUpdate = true

        assertTrue(owner.refresh(ACCOUNT, mapOf(visibleGroup to PinnedConversationPresentation("Current"))))
        assertEquals(2, platform.updates.size)
        assertEquals(listOf(visible.shortcutId, visible.shortcutId), platform.updates.flatten().map { it.id })
        assertEquals(
            "Current",
            platform.updates
                .first()
                .single()
                .longLabel,
        )
        assertEquals(
            context.getString(R.string.app_name),
            platform.updates
                .last()
                .single()
                .longLabel,
        )
        assertTrue(tokens.isValid(retained))
        assertTrue(platform.disabled.isEmpty())
    }

    /** Omission does not preserve an authority that was durably revoked before the refresh. */
    @Test
    fun revokedOmittedPinIsScrubbedAndDisabled() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val cap = capability
        owner.request(cap, "Removed", null) { true }
        platform.approve(platform.requests.last())
        tokens.revokeGroup(ACCOUNT, GROUP)

        assertTrue(owner.refresh(ACCOUNT, emptyMap()))
        assertEquals(
            context.getString(R.string.app_name),
            platform.updates
                .single()
                .single()
                .longLabel,
        )
        assertEquals(listOf(cap.shortcutId), platform.disabled)
        assertFalse(tokens.isValid(cap))
    }

    /** A missed projection must still enforce a disabled-preview transition without relying on global scrubbing. */
    @Test
    fun omittedPinIsScrubbedWhenPreviewsAreDisabled() = assertOmittedPinScrubbed(reenable = false)

    /** Re-enabling previews cannot reauthorize retained metadata stamped before the intervening opt-out. */
    @Test
    fun omittedPinIsScrubbedAfterPreviewsAreDisabledAndReenabled() = assertOmittedPinScrubbed(reenable = true)

    /** Creates a genuine rich pin, changes the preview epoch without scrubbing inventory, then refreshes no rows. */
    private fun assertOmittedPinScrubbed(reenable: Boolean) =
        runBlocking {
            val platform = Platform(context)
            val owner = PinnedConversationShortcuts(context, platform)
            val cap = capability
            owner.request(cap, "Private", null) { true }
            platform.approve(platform.requests.last())
            assertTrue(NotificationPreviewPreferences.setEnabled(context, false) { true })
            if (reenable) assertTrue(NotificationPreviewPreferences.setEnabled(context, true) { true })

            assertTrue(owner.refresh(ACCOUNT, emptyMap()))
            assertEquals(
                context.getString(R.string.app_name),
                platform.updates
                    .single()
                    .single()
                    .longLabel,
            )
            assertTrue(platform.disabled.isEmpty())
            assertTrue(tokens.isValid(cap))
        }

    /** Previous-process provenance cannot authorize keeping a rich label absent from the current projection. */
    @Test
    fun omittedPinWithPriorSessionProvenanceIsScrubbed() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val cap = capability
        owner.request(cap, "Private", null) { true }
        platform.approve(platform.requests.last())
        platform.inventory
            .single()
            .extras!!
            .putString(NotificationPreviewPreferences.EXTRA_SESSION, "previous-process")

        assertTrue(owner.refresh(ACCOUNT, emptyMap()))
        assertEquals(
            context.getString(R.string.app_name),
            platform.updates
                .single()
                .single()
                .longLabel,
        )
        assertTrue(tokens.isValid(cap))
    }

    /** An available projection with no avatar is a real clear, so it must replace the old bitmap. */
    @Test
    fun availableProjectionWithoutAvatarClearsThePreviousPixels() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val cap = capability
        val picture = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        owner.request(cap, "Picture", null, picture) { true }
        platform.approve(platform.requests.last())

        assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to PinnedConversationPresentation("Current", null))))
        val updated = platform.updates.single().single()
        assertEquals("Current", updated.longLabel)
        assertNotEquals(Color.MAGENTA, (updated.icon!!.loadDrawable(context) as BitmapDrawable).bitmap.getPixel(0, 0))
    }

    /** A queued request reads current private pixels at publication rather than retaining its captured picture. */
    @Test
    fun privatePictureClearedBeforeRequestIsNotPublished() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val stale = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        var current: Bitmap? = stale
        val presentation = PinnedConversationPresentation("Peer", stale, "peer", { current })
        platform.afterInventoryRead = { current = null }
        owner.request(capability, "Peer", null, stale, presentation) { true }
        val requested = platform.requests.single()
        val icon = requested.icon!!.loadDrawable(context) as BitmapDrawable
        assertNotEquals(Color.MAGENTA, icon.bitmap.getPixel(0, 0))
    }

    /**
     * Ordinary projection refresh preserves the private override and stamps ownership for off-window reconciliation.
     */
    @Test
    fun privatePictureRefreshAndOffWindowClearUseCurrentOwnedPixels() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val privatePicture = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val presentation = PinnedConversationPresentation("Peer", contact = "peer", currentAvatar = { privatePicture })
        owner.request(capability, "Peer", null, presentation = presentation) { true }
        platform.approve(platform.requests.single())
        assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to presentation)))
        val refreshed = platform.updates.single().single()
        assertEquals(
            Color.MAGENTA,
            (refreshed.icon!!.loadDrawable(context) as BitmapDrawable).bitmap.getPixel(0, 0),
        )
        assertTrue(owner.refresh(ACCOUNT, emptyMap()))
        val reconciled = mutableListOf<ShortcutInfoCompat>()
        refreshContactPictureShortcuts(
            context,
            ACCOUNT,
            "peer",
            currentAvatar = { null },
            isCurrent = { true },
            platform =
                object : ContactPictureShortcutPlatform(context) {
                    /** Reads the approved pin even after it leaves the recent projection. */
                    override fun read(): List<ShortcutInfoCompat> = listOf(refreshed)

                    /** Captures the actual replacement icon and routing metadata for the clear assertion. */
                    override fun update(shortcuts: List<ShortcutInfoCompat>) {
                        reconciled += shortcuts
                    }
                },
        )
        val cleared = reconciled.single()
        assertEquals(refreshed.intent.data, cleared.intent.data)
        assertNotEquals(Color.MAGENTA, (cleared.icon!!.loadDrawable(context) as BitmapDrawable).bitmap.getPixel(0, 0))
    }

    /** A Clear or replacement while approval waits cannot retain the request's old private photo. */
    @Test
    fun approvalAfterPrivatePictureEditRebuildsOnlyCurrentPixels() {
        val platform = Platform(context)
        val cap = capability
        val stale = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        var current: Bitmap? = stale
        val presentation = PinnedConversationPresentation("Peer", contact = "peer", currentAvatar = { current })
        val owner = PinnedConversationShortcuts(context, platform)
        owner.request(cap, "Peer", null, presentation = presentation) { true }
        current = null
        platform.approve(platform.requests.single())

        assertTrue(PinnedConversationShortcuts(context, platform).approved(cap))
        val scrubbed = platform.updates.single().single()
        assertEquals(context.getString(R.string.app_name), scrubbed.longLabel)
        assertEquals(IconCompat.TYPE_RESOURCE, scrubbed.icon!!.type)
        val requested = platform.requests.single()
        assertEquals(requested.intent.data, scrubbed.intent.data)
        val decoded = requireNotNull(PinnedConversationNavigation.capability(scrubbed.intent))
        assertEquals(cap.accountRef, decoded.accountRef)
        assertEquals(cap.groupIdHex, decoded.groupIdHex)
        assertEquals(cap.shortcutId, decoded.shortcutId)
        assertTrue(tokens.isValid(decoded))
        assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to presentation)))
        val cleared = platform.updates.last().single()
        assertNotEquals(Color.MAGENTA, (cleared.icon!!.loadDrawable(context) as BitmapDrawable).bitmap.getPixel(0, 0))

        current = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to presentation)))
        val replaced = platform.updates.last().single()
        assertEquals(Color.BLUE, (replaced.icon!!.loadDrawable(context) as BitmapDrawable).bitmap.getPixel(0, 0))
    }

    /** Replacement before approval is resolved from the current source rather than the captured request bitmap. */
    @Test
    fun approvalAfterPrivatePictureReplacementUsesTheNewPicture() {
        val platform = Platform(context)
        val cap = capability
        var current = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val presentation = PinnedConversationPresentation("Peer", contact = "peer", currentAvatar = { current })
        val owner = PinnedConversationShortcuts(context, platform)
        owner.request(cap, "Peer", null, presentation = presentation) { true }
        current = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        platform.approve(platform.requests.single())
        assertTrue(owner.approved(cap))
        assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to presentation)))
        val rebuilt = platform.updates.last().single()
        assertEquals(Color.BLUE, (rebuilt.icon!!.loadDrawable(context) as BitmapDrawable).bitmap.getPixel(0, 0))
    }

    /** A pin outside the projection stays generic after approval when canonical lookup cannot supply its row. */
    @Test
    fun approvalWithoutCurrentRowCannotRestorePendingPrivatePixels() {
        val platform = Platform(context)
        val cap = capability
        val stale = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val presentation = PinnedConversationPresentation("Peer", stale, "peer")
        val owner = PinnedConversationShortcuts(context, platform)
        owner.request(cap, "Peer", null, presentation = presentation) { true }
        platform.approve(platform.requests.single())
        assertTrue(PinnedConversationShortcuts(context, platform).approved(cap))
        assertTrue(owner.refresh(ACCOUNT, emptyMap()))
        val retained = platform.updates.single().single()
        assertEquals(IconCompat.TYPE_RESOURCE, retained.icon!!.type)
        assertEquals(context.getString(R.string.app_name), retained.longLabel)
        assertTrue(tokens.isValid(cap))
    }

    /** A group-owned picture has no contact-pixel ownership and does not need a peer-photo approval scrub. */
    @Test
    fun approvalPreservesGroupOwnedPicture() {
        val platform = Platform(context)
        val cap = capability
        val groupPicture = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val owner = PinnedConversationShortcuts(context, platform)
        owner.request(cap, "Group", null, groupPicture) { true }
        platform.approve(platform.requests.single())
        assertFalse(owner.approved(cap))
        assertTrue(platform.updates.isEmpty())
    }

    /** Failed approval scrubbing makes that exact pin unavailable rather than leaving usable old private pixels. */
    @Test
    fun failedApprovalScrubDisablesOnlyTheAffectedPin() {
        val platform = Platform(context)
        val cap = capability
        val presentation = PinnedConversationPresentation("Peer", contact = "peer")
        val owner = PinnedConversationShortcuts(context, platform)
        owner.request(cap, "Peer", null, presentation = presentation) { true }
        platform.approve(platform.requests.single())
        platform.failNextUpdate = true
        assertTrue(runCatching { owner.approved(cap) }.isFailure)
        assertEquals(listOf(cap.shortcutId), platform.disabled)
    }

    /** An older avatar-only refresh cannot overwrite a newer projection after waiting on launcher inventory. */
    @Test
    fun sharedPresentationRevisionRejectsAnOlderRefreshAfterANewerRename() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        owner.request(capability, "Original", null) { true }
        platform.approve(platform.requests.single())
        val revision = AtomicLong(1)
        platform.afterInventoryRead = {
            platform.afterInventoryRead = null
            revision.incrementAndGet()
            val newer = mapOf(GROUP to PinnedConversationPresentation("Renamed"))
            assertTrue(owner.refresh(ACCOUNT, newer) { revision.get() == 2L })
        }
        val older = mapOf(GROUP to PinnedConversationPresentation("Old avatar snapshot"))
        assertFalse(owner.refresh(ACCOUNT, older) { revision.get() == 1L })
        val update = platform.updates.single()
        assertEquals("Renamed", update.single().longLabel)
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

    /** The account-cleanup sequence revokes even requests absent from the OS inventory before platform removal. */
    @Test
    fun accountCleanupRevokesPendingRequestWithoutAffectingAnotherAccount() {
        val removed = capability
        val kept = requireNotNull(tokens.issue("another-account", GROUP))
        synchronized(UserEventNotificationGroup.mutationLock) { tokens.revokeAccount(ACCOUNT) }
        clearRevokedConversationShortcutsForAccount(context, ACCOUNT, includeUnscopedLegacy = false)
        assertFalse(tokens.isValid(removed))
        assertTrue(tokens.isValid(kept))
    }

    /** Pending requests do not trigger expensive refresh preparation; only approved pins owned by this account do. */
    @Test
    fun pinPresenceFollowsLauncherApprovalAndAccountOwnership() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        assertFalse(owner.hasPinnedConversations(ACCOUNT))
        owner.request(capability, "Friends", null) { true }
        assertFalse(owner.hasPinnedConversations(ACCOUNT))
        platform.approve(platform.requests.single())
        assertTrue(owner.hasPinnedConversations(ACCOUNT))
        assertFalse(owner.hasPinnedConversations("another-account"))
    }

    /** Cancellation after inventory acquisition prevents an old refresh from publishing over a newer label. */
    @Test
    fun staleRefreshAfterInventoryReadCannotPublish() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        owner.request(capability, "Original", null) { true }
        platform.approve(platform.requests.single())
        var current = true
        platform.afterInventoryRead = { current = false }
        assertFalse(owner.refresh(ACCOUNT, mapOf(GROUP to PinnedConversationPresentation("Obsolete"))) { current })
        assertTrue(platform.updates.isEmpty())
        assertTrue(platform.disabled.isEmpty())
        platform.afterInventoryRead = null
        assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to PinnedConversationPresentation("Current"))))
        assertEquals(
            "Current",
            platform.updates
                .single()
                .single()
                .longLabel,
        )
    }

    /** An action revoked during a launcher read must not issue a late pin request from the IO coroutine. */
    @Test
    fun obsoleteRequestCannotPublishAfterInventoryRead() {
        val platform = Platform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        var current = true
        platform.afterInventoryRead = { current = false }
        assertEquals(ConversationPinResult.UNAVAILABLE, owner.request(capability, "Obsolete", null) { current })
        assertTrue(platform.requests.isEmpty())
        assertTrue(platform.updates.isEmpty())
    }

    /** Keeps Android's real pinned flags while scripting approval timing and failed update responses. */
    private class Platform(
        private val context: Context,
    ) : PinnedShortcutPlatform {
        var supported = true
        var accepted = true
        var failNextUpdate = false
        var inventory = emptyList<ShortcutInfoCompat>()
        var afterInventoryRead: (() -> Unit)? = null
        val requests = mutableListOf<ShortcutInfoCompat>()
        val updates = mutableListOf<List<ShortcutInfoCompat>>()
        val disabled = mutableListOf<String>()

        override fun supported(): Boolean = supported

        /** Runs the injected inventory-read race hook before returning the fake launcher inventory. */
        override fun shortcuts(): List<ShortcutInfoCompat> = inventory.also { afterInventoryRead?.invoke() }

        /** Captures launcher requests and their approval callback without implicitly approving a pin. */
        override fun request(
            shortcut: ShortcutInfoCompat,
            callback: IntentSender,
        ): Boolean {
            requests += shortcut
            return accepted
        }

        /** Applies icon/label replacements only to existing fake launcher entries. */
        override fun update(shortcuts: List<ShortcutInfoCompat>): Boolean {
            updates += shortcuts
            if (failNextUpdate) {
                failNextUpdate = false
                error("scripted launcher update failure")
            }
            return true
        }

        /** Retains the launcher item while making its revoked identity unavailable to future taps. */
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

        /** The 32-hex shape MDK emits for a 16-byte group ID. */
        val GROUP = "ab".repeat(16)
    }
}
