package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate for #3311, #3505 and #3635: every rich message card uses the native conversation template and
 * carries one shared conversation identity snapshot through first post, enrichment, private contact pictures and
 * every rewrite of a live card. Its identity shortcut is published before the single first post, so even a
 * brand-new conversation's first card is a conversation, while redacted and hidden-preview cards stay generic. The
 * identity is the picture the chat list draws, each group row shows its member's own picture, and a reaction card
 * shows who reacted.
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
        val payload = source("NotificationCardPublisher.kt").functionBody("previewPayload")
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
        val post = source("NotificationCardPublisher.kt").functionBody("postNotificationSafely")
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
        val appState = stateSource("AppState.kt")
        val prepare = appState.functionBody("prepareLateNotificationIdentity")
        assertTrue("only a group card asks for the loader", "!update.isDm" in prepare)
        assertTrue("the late sources carry it", "conversationLoader = if (encryptedGroupPicture)" in prepare)
        assertTrue("from the encrypted-picture loader", "encryptedGroupIdentityLoader(update)" in prepare)
        val loader = appState.functionBody("encryptedGroupIdentityLoader")
        assertTrue("through the group avatar cache", "GroupAvatarImageLoader" in loader)
        assertTrue("and MDK's encrypted download", "downloadGroupBlossomImage(" in loader)
        assertTrue("nothing decrypts behind the lock", loader.split("appLockScreenVisible").size - 1 >= 2)
        val presenter = source("LocalNotificationPresenter.kt")
        val resolve = presenter.declaration("private suspend fun resolveAvatars(")
        assertTrue(
            "the presenter falls back to the loader",
            "sources.conversationLoader?.let { boundedIdentityLoad(it) }" in resolve,
        )
        val bounded = presenter.declaration("private suspend fun boundedIdentityLoad(")
        assertTrue("within its own late-refresh bound", "GROUP_IDENTITY_LOAD_TIMEOUT_MS" in bounded)
    }

    /**
     * #3635: a card's conversation picture is the one the chat list draws. The first post peeks the pixels the chat
     * list decoded for the row's stored selected avatar, and the late refresh reads MDK's stored copy first, through
     * the chat list's own adoption rule, never acquiring a missing copy and never behind the app lock.
     */
    @Test
    fun conversationPictureComesFromTheChatListsStoredAvatarFirst() {
        val appState = stateSource("AppState.kt")
        val ready = appState.functionBody("readyNotificationAvatars")
        assertTrue("the first post reads the row's stored picture", "readyStoredAvatarBitmap(" in ready)
        assertTrue("before the URL and encrypted caches", "stored ?: ready.groupAvatarBitmap" in ready)
        val avatars = stateSource("PreWarmedNotificationAvatars.kt")
        val peek = avatars.declaration("internal fun ChatListItem.readyStoredAvatarBitmap(")
        assertTrue("of the row's selected asset", "selectedAvatarAsset" in peek)
        assertTrue("from the pixels the chat list decoded", "AvatarImageLoader::cachedImage" in peek)
        val prepare = appState.functionBody("prepareLateNotificationIdentity")
        assertTrue(
            "the late refresh carries the stored copy",
            "storedConversationLoader = storedConversationIdentityLoader(update)" in prepare,
        )
        val stored = appState.functionBody("storedConversationIdentityLoader")
        assertTrue("from the chat list's own row", "presentedChatListRow(" in stored)
        assertTrue("through its adoption rule", "storedNotificationAvatar(" in stored)
        assertTrue(
            "which is the chat list's projection",
            "chatListItemFromProjection(" in avatars.extensionBody("storedNotificationAvatar"),
        )
        assertTrue("never acquiring a missing copy", "acquireMissing = false" in stored)
        assertTrue("nothing is read behind the lock", stored.split("appLockScreenVisible").size - 1 >= 2)
        val resolve = source("LocalNotificationPresenter.kt").declaration("private suspend fun resolveAvatars(")
        val storedAt = resolve.indexOf("sources.storedConversationLoader")
        val urlAt = resolve.indexOf("sources.conversationUrl")
        assertTrue("the stored copy comes before the URL", storedAt in 0 until urlAt)
        assertTrue("and before the encrypted download", storedAt < resolve.indexOf("sources.conversationLoader"))
    }

    /**
     * #3635: every group row draws its member's own picture. Sender pictures are resolved, warmed and refreshed late
     * for a group as for a direct chat, no write blanks them, and rows an earlier build blanked get their monogram
     * when carried into a new post. Only the group shortcut's sender stays blank, since it stands for the group.
     */
    @Test
    fun groupRowsDrawEachMembersOwnPicture() {
        val rendering = source("NotificationIdentityRendering.kt").declaration("internal fun notificationSenderPerson(")
        assertFalse("no blank picture for a group row", "groupSenderIcon" in rendering)
        val write = source("NotificationCardPublisher.kt").functionBody("postNotificationSafely")
        assertFalse("the final write keeps member pictures", "GroupSenderPictures(" in write)
        val presenter = source("LocalNotificationPresenter.kt")
        val firstPost =
            presenter
                .substringAfter("NotificationStyleChoice.Messaging -> {")
                .substringBefore("messagingPost =")
        assertFalse("a group keeps its sender's picture", "isGroupConversation" in firstPost)
        val late = presenter.functionBody("refreshLateIdentityImage")
        assertFalse("the late refresh keeps the sender slot", "senderUrl = null" in late)
        val carried = source("NotificationCardContent.kt").functionBody("messagingStyle")
        assertTrue("carried blank rows get their monogram", "withoutBlankGroupSenderPicture(" in carried)
        val ready = stateSource("AppState.kt").functionBody("readyNotificationAvatars")
        assertFalse("the first post resolves every sender", "NotificationTriggerFfi.NEW_MESSAGE" in ready)
        val target =
            stateSource("AppStatePresentation.kt").declaration("internal fun notificationAvatarPreWarmTarget(")
        assertFalse("every sender is warmed", "takeIf { update.isDm" in target)
        assertFalse("with their picture URL", "if (update.isDm) ProfileSanitizer" in target)
        val sources =
            stateSource("PreWarmedNotificationAvatars.kt").declaration(
                "internal fun PreWarmedNotificationAvatars.lateIdentitySources(",
            )
        assertTrue("every sender has a late source", "senderUrl = warmed.senderAvatarUrl," in sources)
        val shortcut =
            source("NotificationGroupSenderImages.kt").declaration("internal fun notificationShortcutSender(")
        assertTrue("the group shortcut's sender stays blank", "groupNotificationPerson(sender)" in shortcut)
    }

    /**
     * #3635: a reaction card shows who reacted through the shared identity snapshot, never on a redacted or
     * hidden-preview card. A late picture rewrites only that picture and keeps the reaction artwork views.
     */
    @Test
    fun reactionCardsShowWhoReacted() {
        val presenter = source("LocalNotificationPresenter.kt")
        val plain =
            presenter
                .substringAfter("NotificationStyleChoice.Plain -> {")
                .substringBefore("NotificationStyleChoice.Messaging -> {")
        val reactor = plain.indexOf("reactorImage(")
        assertTrue("the reaction card draws who reacted", reactor >= 0)
        val guard = plain.substring(plain.lastIndexOf("if (", reactor), reactor)
        assertTrue("never on a redacted or hidden-preview card", "!redactContent && previewToken.allowed" in guard)
        assertTrue("through the shared snapshot", "setConversationIdentityImage(" in plain)
        val route = presenter.functionBody("refreshLateIdentityImage")
        assertTrue("a late picture takes the reaction path", "rewriteWithLateReactorPicture(" in route)
        val rewrite = source("NotificationCardContent.kt").declaration("fun reactorPictureNotification(")
        listOf("setCustomContentView", "setCustomBigContentView", "setCustomHeadsUpContentView").forEach {
            assertTrue("the artwork view survives: $it", it in rewrite)
        }
        assertTrue("one silent rewrite", "setOnlyAlertOnce(true)" in rewrite)
        val image = source("NotificationIdentityImage.kt").extensionBody("reactorImage")
        assertTrue("no contact refresh rebuilds the card without its artwork", "contactKey = null" in image)
        val warm = stateSource("AppStatePresentation.kt").declaration("internal fun shouldPreWarmNotificationAvatars(")
        assertFalse("a reaction's picture is warmed", "isReaction" in warm)
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

    /** One declaration from its [header] to the blank line that ends it, for expression-bodied functions. */
    private fun String.declaration(header: String): String {
        val start = indexOf(header)
        require(start >= 0) { "Missing $header" }
        val end = indexOf("\n\n", start).takeIf { it >= 0 } ?: length
        return substring(start, end)
    }

    /** Reads one state source by file name. */
    private fun stateSource(name: String): String = File(notificationsRoot().parentFile, "state/$name").readText()

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
