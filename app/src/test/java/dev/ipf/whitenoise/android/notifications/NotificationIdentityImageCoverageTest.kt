package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate for #3311 and #3505: every rich message card uses the native conversation template and
 * carries one shared conversation identity snapshot through first post, enrichment, private contact pictures and
 * every rewrite of a live card. Its identity shortcut is published before the single first post, so even a
 * brand-new conversation's first card is a conversation, while redacted and hidden-preview cards stay generic.
 */
class NotificationIdentityImageCoverageTest {
    /** The presenter sets the picture only through the shared snapshot, and never on a redacted card. */
    @Test
    fun richCardsShareOneIdentitySnapshotAndRedactedCardsGetNone() {
        val presenter = source("LocalNotificationPresenter.kt")
        assertFalse("identity pictures go through setConversationIdentityImage", ".setLargeIcon(" in presenter)
        assertFalse(
            "content rewrites use the same identity snapshot",
            ".setLargeIcon(" in source("NotificationCardContent.kt"),
        )
        val firstPost =
            presenter
                .substringAfter("NotificationStyleChoice.Messaging -> {")
                .substringBefore("messagingPost =")
        val guarded =
            firstPost
                .substringAfter("if (!redactContent) {\n")
                .substringBefore("\n                            }")
        assertTrue("the first payload carries the identity picture", "setConversationIdentityImage(" in guarded)
        assertTrue("the shortcut monogram seed keeps card and shortcut alike", "seed = messagingShortcutId" in guarded)
        val image = source("NotificationIdentityImage.kt").extensionBody("identityImage")
        assertTrue("a group never borrows a member's face", "senderAvatar.takeIf { directChat }" in image)
    }

    /** Long first messages never switch the image into BigTextStyle's trailing picture slot. */
    @Test
    fun everyNewMessageUsesTheSameNativeConversationTemplate() {
        val show = source("LocalNotificationPresenter.kt").functionBody("show")
        val richWrite =
            show
                .substringAfter("val presentationTimestampMs = nowMillis()")
                .substringBefore("val notification = buildSettled(false)")
        assertTrue("the message branch builds a conversation", "messagingStyle(" in richWrite)
        assertFalse("no body-length switch", "shouldUseExpandedSingleMessageStyle" in show)
        assertFalse("no trailing big-text picture for messages", "BigTextStyle" in richWrite)
    }

    /**
     * Android draws the conversation layout and its picture only when the shortcut exists at post time, so the
     * identity shortcut is published before the one first post, on the preview-allowed rich path only, through the
     * existing shortcut owner and with no second card write or network wait.
     */
    @Test
    fun identityShortcutIsPublishedBeforeTheFirstPost() {
        val presenter = source("LocalNotificationPresenter.kt")
        val show = presenter.functionBody("show")
        val publish = show.indexOf("publishShortcutBeforePost(")
        assertTrue("the first post publishes its identity shortcut", publish >= 0)
        assertTrue("before the pacer slot and the write", publish < show.indexOf("postPacer.awaitSlot()"))
        val guard = show.substring(show.lastIndexOf("messagingPost?.takeIf", publish), publish)
        assertTrue("only on the preview-allowed rich path", "!redactContent && previewToken.allowed" in guard)
        val before = presenter.functionBody("publishShortcutBeforePost")
        assertTrue("through the existing shortcut owner", "publishConversationShortcut(" in before)
        assertFalse("never a second card write", "postNotificationSafely" in before || "notificationPoster" in before)
        assertFalse("no network wait on the posting path", "avatarBitmapResolver" in before)
        val payload = presenter.functionBody("previewPayload")
        assertTrue(
            "a hidden card makes the next rich post publish again",
            "conversationShortcuts::forgetShortcutSnapshot" in payload,
        )
        assertTrue("the shortcut is held for the coming write", "holdForPost = true" in before)
        assertTrue("and released once that write ends", "InFlightConversationShortcuts::release" in show)
        val release = show.indexOf("InFlightConversationShortcuts::release")
        assertTrue("even when it is cancelled or fails", show.lastIndexOf("} finally {", release) > publish)
        val shortcuts = source("NotificationConversationShortcuts.kt")
        val prune = shortcuts.functionBody("pruneConversationShortcutsBeforePublish")
        assertTrue(
            "pruning spares live and held shortcuts",
            "liveCardShortcutIds() + InFlightConversationShortcuts.ids()" in prune,
        )
        val reuse = shortcuts.functionBody("reuseCachedShortcut")
        assertTrue("a cached snapshot needs the live shortcut", "shortcutId in dynamicShortcutIds()" in reuse)
        val share = File(notificationsRoot().parentFile, "share/ShareShortcutPublisher.kt").readText()
        assertTrue(
            "a Direct Share refresh keeps a held shortcut",
            "InFlightConversationShortcuts.ids()" in share.functionBody("cardBackedShortcuts"),
        )
    }

    /** Late pictures also support a pre-upgrade text card until its next message replaces the old template. */
    @Test
    fun enrichmentRetainsCompatibilityWithPreUpgradeTextCards() {
        val body = source("NotificationCardContent.kt").functionBody("enrichedIdentityNotification")
        assertTrue("conversation template", "enrichedMessagingStyle(active, sender)" in body)
        assertTrue("pre-upgrade long-message text block", "EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER" in body)
        assertTrue("the same snapshot", "setConversationIdentityImage(identity)" in body)
        assertTrue("one silent rewrite", "setOnlyAlertOnce(true)" in body)
    }

    /** Every final write applies the current private contact choice to a direct chat's picture. */
    @Test
    fun finalWriteRefreshesTheDirectChatPictureFromTheContactChoice() {
        val presenter = source("LocalNotificationPresenter.kt")
        val post = presenter.functionBody("postNotificationSafely")
        assertTrue("the final write uses the combined refresh", "withCurrentContactPictures(notification)" in post)
        val refresh = source("NotificationCardContent.kt").functionBody("withCurrentContactPictures")
        assertTrue("the identity picture follows the contact choice", "withCurrentIdentityContact(" in refresh)
        assertTrue("redacted cards are never refreshed", "EXTRA_CONTENT_REDACTED" in refresh)
    }

    /** A late picture or contact refresh rewrites the live card, so no rewrite may drop or replace the identity. */
    @Test
    fun liveCardRewritesNeverDropTheIdentityPicture() {
        val presenter = source("LocalNotificationPresenter.kt")
        assertFalse("no path clears the picture", "setLargeIcon(null" in presenter)
        assertFalse(
            "no content rewrite clears the picture",
            "setLargeIcon(null" in source("NotificationCardContent.kt"),
        )
        val refresh = source("NotificationIdentityImage.kt").functionBody("withCurrentIdentityContact")
        assertTrue("only a direct chat's own contact changes its picture", "IDENTITY_CONTACT" in refresh)
        assertTrue("a cleared choice falls back to the monogram", "notificationMonogramBitmap(title, seed)" in refresh)
    }

    /** Hidden-preview and lock-screen versions are rebuilt from scratch without any identity picture or key. */
    @Test
    fun genericVersionsCarryNoIdentity() {
        val privacy = source("NotificationPreviewPrivacy.kt")
        listOf("notificationWithoutPreview", "genericPublicVersion", "previewRoutingExtras").forEach { name ->
            val body = privacy.functionBody(name)
            assertFalse("$name must not set a picture", "LargeIcon" in body || "IdentityImage" in body)
            assertFalse("$name must not copy the identity extras", "EXTRA_IDENTITY_IMAGE" in body)
        }
        val redacted = source("LocalNotificationPresenter.kt").functionBody("redactedPublicVersion")
        assertFalse("the lock-screen version stays generic", "LargeIcon" in redacted || "IdentityImage" in redacted)
    }

    /**
     * Local removal drops the presenter's snapshot of the removed conversation shortcut on both removal paths, off
     * the main thread and in the same publication-lock section as the platform removal, so no card publishes between
     * the two and a conversation joined again under the same group id publishes its shortcut before its first card.
     */
    @Test
    fun localRemovalForgetsTheConversationShortcutSnapshot() {
        val lifecycle = File(notificationsRoot().parentFile, "state/PinnedShortcutLifecycle.kt").readText()
        mapOf(
            "removePinnedConversationShortcuts" to "removeGroup(",
            "removeRevokedPinnedConversationShortcuts" to "removeRevokedGroup(",
        ).forEach { (name, removal) ->
            val body = lifecycle.extensionBody(name)
            val io = body.indexOf("withContext(Dispatchers.IO)")
            val lock = body.indexOf("synchronized(UserEventNotificationGroup.mutationLock)")
            assertTrue("$name holds the lock off the main thread", io in 0 until lock)
            val section = body.substring(lock)
            val forget = section.indexOf("forgetNotificationConversationShortcut(")
            assertTrue("$name forgets inside the removal's lock", section.indexOf(removal) in 0 until forget)
        }
        val presenter = source("LocalNotificationPresenter.kt")
        assertTrue(
            "local removal reaches the shortcut owner",
            "conversationShortcuts.forgetConversationShortcut(" in presenter,
        )
        val forget = source("NotificationConversationShortcuts.kt").functionBody("forgetConversationShortcut")
        assertTrue("the snapshot goes", "forgetShortcutSnapshot(" in forget)
        assertTrue("and so does its recency", "shortcutLastUsed.remove(" in forget)
    }

    /**
     * A group's Marmot encrypted picture has no URL, so the late refresh loads it through the group avatar's own MDK
     * download and decoded cache, only for a group, never while the app lock shows, and within the same time bound
     * as a URL picture.
     */
    @Test
    fun lateGroupIdentityLoadsTheEncryptedGroupPicture() {
        val appState = File(notificationsRoot().parentFile, "state/AppState.kt").readText()
        val prepare = appState.functionBody("prepareLateNotificationIdentity")
        assertTrue("only a group card asks for the loader", "!update.isDm" in prepare)
        assertTrue("the late sources carry it", "conversationLoader = encryptedGroupIdentityLoader(update)" in prepare)
        val loader = appState.functionBody("encryptedGroupIdentityLoader")
        assertTrue("through the group avatar cache", "GroupAvatarImageLoader" in loader)
        assertTrue("and MDK's encrypted download", "downloadGroupBlossomImage(" in loader)
        assertTrue("nothing decrypts behind the lock", loader.split("appLockScreenVisible").size - 1 >= 2)
        val presenter = source("LocalNotificationPresenter.kt")
        val start = presenter.indexOf("private suspend fun resolveAvatars(")
        val resolve = presenter.substring(start, presenter.indexOf("\n\n", start))
        assertTrue("the presenter falls back to the loader", "conversationLoader" in resolve)
        assertTrue("within its own late-refresh bound", "GROUP_IDENTITY_LOAD_TIMEOUT_MS" in resolve)
    }

    /** An unchanged snapshot is re-read under the publication lock before only reporting use of the shortcut. */
    @Test
    fun unchangedSnapshotIsReReadUnderTheLockBeforeOnlyReportingUse() {
        val shortcuts = source("NotificationConversationShortcuts.kt")
        val publish = shortcuts.functionBody("publishConversationShortcut")
        assertTrue("an unchanged snapshot goes through the locked reuse", "reuseCachedShortcut(" in publish)
        // The helper is expression-bodied, so read its whole declaration, lock call included.
        val start = shortcuts.indexOf("private fun reuseCachedShortcut(")
        val body = shortcuts.functionBody("reuseCachedShortcut")
        val reuse = shortcuts.substring(start, shortcuts.indexOf(body, start) + body.length)
        val report = reuse.indexOf("reportShortcutUsed(")
        val lock = reuse.lastIndexOf("synchronized(UserEventNotificationGroup.mutationLock)", report)
        val reRead = reuse.lastIndexOf("shortcutSnapshots[shortcutId] == snapshot", report)
        assertTrue("the use report re-reads the snapshot inside the lock", lock in 0 until reRead)
    }

    /** Every rich-group rewrite normalizes sender pixels after private overrides while retaining native style. */
    @Test
    fun groupSenderPicturesAreSuppressedAtTheFinalWriteBoundary() {
        val write = source("LocalNotificationPresenter.kt").functionBody("postNotificationSafely")
        val normalize = write.indexOf("withoutGroupSenderPictures(")
        assertTrue("normalization is in the final write", normalize >= 0)
        assertTrue("after private picture reconciliation", write.indexOf("withCurrentContactPictures(") < normalize)
        val policy = source("NotificationGroupSenderImages.kt")
        assertTrue("real group conversation style retained", "setGroupConversation(true)" in policy)
        assertTrue("all historic senders normalized", "existing.historicMessages.forEach" in policy)
        assertTrue("self normalized too", "groupNotificationPerson(existing.user)" in policy)
        assertTrue("native action objects retained", "actions = notification.actions" in policy)
        assertFalse("no replacement custom card", "RemoteViews" in policy)
    }

    /** Body of an extension function such as `fun Receiver.name(...)`, which [functionBody] does not match. */
    private fun String.extensionBody(name: String): String {
        val start =
            Regex("""\bfun\s+[\w.]+\.${Regex.escape(name)}\s*\(""").find(this)?.range?.last
                ?: error("Missing extension function $name")
        val parameters = kotlinBlockFrom(start, "parameters for $name", opening = '(', closing = ')')
        return kotlinBlockFrom(start + parameters.length, "function $name", searchForOpening = true)
    }

    /** Reads one notifications source by file name. */
    private fun source(name: String): String = File(notificationsRoot(), name).readText()

    /** The notifications package, whether Gradle runs from the module or the repository. */
    private fun notificationsRoot(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/notifications"),
            File("app/src/main/java/dev/ipf/whitenoise/android/notifications"),
        ).firstOrNull { it.exists() } ?: error("Missing notifications source root")
}
