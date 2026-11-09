package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
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
        val firstPost = presenter.branch("NotificationStyleChoice.Messaging -> {").before("messagingPost =")
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
        assertTrue(
            "the late sources carry it, from the encrypted-picture loader",
            "encryptedGroupLoader = { encryptedGroupIdentityLoader(update) }" in prepare,
        )
        assertTrue(
            "only for a group card without a shown picture or a URL",
            "!isDm && !shown -> encryptedGroupLoader()" in lateLoaders(),
        )
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
     * the chat list's own adoption rule, never acquiring a missing copy and never behind the app lock. Only a card that
     * already shows the stored picture skips it, so one first drawn from elsewhere, such as a direct chat's published
     * sender picture on an account whose chat list was not loaded, still ends on the chat list's picture. The row's
     * peer, whose private picture replaces the stored one by the chat row's own rule, is named but never read here.
     */
    @Test
    fun conversationPictureComesFromTheChatListsStoredAvatarFirst() {
        val appState = stateSource("AppState.kt")
        val ready = appState.functionBody("readyNotificationAvatars")
        assertTrue("the first post reads the row", ".withChatRow(item, update.accountRef)" in ready)
        assertFalse("but never a private picture", "contactAvatarOverride" in ready)
        val avatars = stateSource("PreWarmedNotificationAvatars.kt")
        val row = avatars.declaration("internal fun PreWarmedNotificationAvatars.withChatRow(")
        assertTrue("apart from group caches", "storedConversationBitmap = item?.readyStoredAvatarBitmap(" in row)
        assertTrue("naming the row's peer", "identityContact = item?.privatePictureContact()" in row)
        val rule = avatars.declaration("internal fun ChatListItem.privatePictureContact(")
        assertTrue(
            "the row's peer, as the chat row names it",
            ".avatarAccount(group, presentationOtherMemberAccount, presentationMemberCount)" in rule,
        )
        assertTrue("unless the group owns its picture", "GroupProjector.ownsGroupPicture(this)" in rule)
        assertTrue(
            "Direct Share applies the same rule",
            "avatarOverride = { account, item -> contactPictureOverride(account, item) }" in appState,
        )
        val override = appState.functionBody("contactPictureOverride")
        assertTrue("through the same peer", "item.privatePictureContact()" in override)
        assertTrue(
            "and drawn first, for a direct chat as for a group",
            "return storedConversationBitmap ?: own" in avatars.declaration("fun conversationBitmap("),
        )
        val peek = avatars.declaration("internal fun ChatListItem.readyStoredAvatarBitmap(")
        assertTrue("of the row's selected asset", "selectedAvatarAsset" in peek)
        assertTrue("from the pixels the chat list decoded", "AvatarImageLoader::cachedImage" in peek)
        val prepare = appState.functionBody("prepareLateNotificationIdentity")
        assertTrue("the late refresh reads the row", "row = { storedConversationIdentity(update) }" in prepare)
        assertTrue(
            "unless the card already shows its stored picture",
            "storedShown = firstPost.avatars.storedConversationBitmap != null" in prepare,
        )
        val late = lateLoaders()
        assertTrue("which settles it, as for a reaction", "if (reaction || storedShown)" in late)
        assertTrue("the stored copy is carried", "storedConversationLoader = stored?.storedLoader" in late)
        assertTrue("with the row's peer", "identityContact = stored?.contact" in late)
        val read = appState.functionBody("storedConversationIdentity")
        assertTrue("from the chat list's own row", "presentedChatListRow(" in read)
        assertTrue("as the chat list projects it", "chatListItemFromProjection(" in read)
        assertTrue("read for the row", "lateRowIdentity(account, item)" in read)
        val stored = appState.functionBody("lateRowIdentity")
        assertTrue("through its adoption rule", "storedNotificationAvatar(" in stored)
        assertTrue("naming the row's peer", "item.privatePictureContact()" in stored)
        assertFalse("whose private picture is never read here", "contactAvatarOverride(" in stored)
        assertTrue("never acquiring a missing copy", "acquireMissing = false" in stored)
        assertTrue("nothing starts behind the lock", stored.split("appLockScreenVisible").size - 1 >= 3)
        val presenter = source("LocalNotificationPresenter.kt")
        val resolve = presenter.declaration("private suspend fun resolveAvatars(")
        val storedAt = resolve.indexOf("sources.storedConversationLoader")
        val urlAt = resolve.indexOf("sources.conversationUrl")
        assertTrue("the stored copy comes before the URL", storedAt in 0 until urlAt)
        assertTrue("and before the encrypted download", storedAt < resolve.indexOf("sources.conversationLoader"))
        assertTrue("and replaces the picture the card shows", storedAt < resolve.indexOf("sources.conversationBitmap"))
        assertTrue(
            "a stored copy with the shown pixels changes nothing",
            "sameAs(" in presenter.declaration("private suspend fun arrivedPictures("),
        )
    }

    /**
     * #3635: the late refresh fetches every missing picture through a loader that checks the app lock, and a slot with
     * a loader never falls back to its raw URL, so a loader that stops behind the lock is not followed by a fetch.
     * NotificationLateLoadersTest runs this wiring; this keeps AppState on it.
     */
    @Test
    fun lateRefreshFetchesOnlyThroughLockCheckingLoaders() {
        val appState = stateSource("AppState.kt")
        val prepare = appState.functionBody("prepareLateNotificationIdentity")
        assertTrue("the late sources get their loaders", ".withLateLoaders(" in prepare)
        assertTrue("URL pictures through the lock-checked loader", "urlLoader = ::latePictureLoader" in prepare)
        assertTrue("a refresh needs a missing picture or a peer", "pictures.mayRefresh" in prepare)
        val late = lateLoaders()
        assertTrue("a conversation URL gets a loader", "url != null -> urlLoader(url, conversationDomain)" in late)
        assertTrue("as does a sender URL", "senderLoader = urlLoader(url, conversation.senderDomain)" in late)
        val loader = appState.declaration("private fun latePictureLoader(")
        assertTrue(
            "nothing starts behind the lock",
            "lockCheckedPictureLoader(url, domain, { appLockScreenVisible })" in loader,
        )
        val resolve = source("LocalNotificationPresenter.kt").declaration("private suspend fun resolveAvatars(")
        assertTrue(
            "a conversation slot with a loader skips its URL",
            "takeUnless { sources.hasConversationLoader }" in resolve,
        )
        assertTrue("as does a sender slot", "takeIf { sources.senderLoader == null }" in resolve)
    }

    /**
     * #3635: Android draws a conversation card's picture from its shortcut, so a Direct Share refresh that rebuilds a
     * card's shortcut as a recent chat draws the stored picture the chat row shows. A refresh that still has none for
     * a pictured publication has the card's next post publish the picture again.
     */
    @Test
    fun shareRefreshKeepsTheCardsStoredPicture() {
        val share = File(notificationsRoot().parentFile, "share/ShareShortcutPublisher.kt").readText()
        val select = share.declaration("internal fun selectShareShortcutTargets(")
        assertTrue("a target carries its row's stored picture", "readyStoredAvatarBitmap(accountRef)" in select)
        val targets = share.declaration("private fun targetShortcuts(")
        assertTrue("a bare rebuild is marked", "markIdentityLost(shortcutId, ifPictured = true)" in targets)
        val shortcuts = source("NotificationConversationShortcuts.kt")
        assertTrue("the presenter records its picture", "pictured = ownedConversationAvatar != null" in shortcuts)
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
        val firstPost = presenter.branch("NotificationStyleChoice.Messaging -> {").before("messagingPost =")
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
        assertTrue("a cold sender picture gets a loader", "senderLoader = urlLoader(" in lateLoaders())
        val resolve = presenter.declaration("private suspend fun resolveAvatars(")
        val loaderAt = resolve.indexOf("sources.senderLoader?.let { boundedIdentityLoad(it) }")
        assertTrue("with the late bound, before the short URL fetch", loaderAt in 0 until resolve.indexOf("senderUrl"))
        val enriched = source("NotificationCardContent.kt").functionBody("enrichedMessagingStyle")
        assertTrue("and SystemUI redraws the rows it reaches", "repaint = true" in enriched)
        val shortcut =
            source("NotificationGroupSenderImages.kt").declaration("internal fun notificationShortcutSender(")
        assertTrue("the group shortcut's sender stays blank", "groupNotificationPerson(sender)" in shortcut)
    }

    /**
     * #3635: a reaction card shows who reacted through the shared identity snapshot, never on a redacted or
     * hidden-preview card. A late picture rewrites only that picture and keeps the reaction artwork views. The reactor
     * is the picture's contact, so their private contact picture wins at the final write, which keeps the artwork too.
     */
    @Test
    fun reactionCardsShowWhoReacted() {
        val presenter = source("LocalNotificationPresenter.kt")
        val plain = presenter.branch("NotificationStyleChoice.Plain -> {")
        val reactor = plain.indexOf("reactorImage(")
        assertTrue("the reaction card draws who reacted", reactor >= 0)
        val guard = plain.substring(plain.lastIndexOf("if (", reactor), reactor)
        assertTrue("never on a redacted or hidden-preview card", "!redactContent && previewToken.allowed" in guard)
        assertTrue("through the shared snapshot", "setConversationIdentityImage(" in plain)
        val route = presenter.functionBody("refreshLateIdentityImage")
        assertTrue("a late picture takes the reaction path", "rewriteWithLateReactorPicture(" in route)
        val rewrite = source("NotificationCardContent.kt").declaration("fun reactorPictureNotification(")
        assertTrue("the artwork views survive the rewrite", "keepingCustomViews(active)" in rewrite)
        assertTrue("one silent rewrite", "setOnlyAlertOnce(true)" in rewrite)
        val identity = source("NotificationIdentityImage.kt")
        val keep = identity.extensionBody("keepingCustomViews")
        listOf("setCustomContentView", "setCustomBigContentView", "setCustomHeadsUpContentView").forEach {
            assertTrue("the artwork view survives: $it", it in keep)
        }
        val image = identity.extensionBody("reactorImage")
        assertTrue("the reactor's private picture applies", "contactKey = senderKey.takeIf(" in image)
        val refresh = identity.functionBody("withCurrentIdentityContact")
        assertTrue("and the final write keeps the artwork", "keepingCustomViews(notification)" in refresh)
        val warm = stateSource("AppStatePresentation.kt").declaration("internal fun shouldPreWarmNotificationAvatars(")
        assertFalse("a reaction's picture is warmed", "isReaction" in warm)
    }

    /**
     * #3635: MDK reports every direct chat as a group (mdk#2773), so the chat row's peer travels with each card and
     * shortcut write instead of private pixels. The card records the peer and the final write applies their current
     * choice, the shortcut reads it under the publication lock and is stamped for a later Save or Clear, and a late
     * refresh records the peer on a card posted without them.
     */
    @Test
    fun directChatReportedAsAGroupFollowsItsPeerAtEachWrite() {
        val appState = stateSource("AppState.kt")
        assertEquals(
            "both message posts pass the peer",
            2,
            appState.split("identityContact = firstPost.avatars.identityContact").size - 1,
        )
        val presenter = source("LocalNotificationPresenter.kt")
        val firstPost = presenter.branch("NotificationStyleChoice.Messaging -> {")
        assertTrue("the first post records the peer", "contact = identityContact," in firstPost)
        assertTrue("and hands them to the shortcut", "identityContact = identityContact," in firstPost)
        val enriched = presenter.functionBody("postEnrichedMessagingNotification")
        assertTrue("an enriched rewrite keeps them", "contact = messaging.identityContact," in enriched)
        val late = presenter.functionBody("rewriteWithLatePictures")
        assertTrue("a late rewrite keeps or adds them", "recordedIdentityContact() ?: refresh.identityContact" in late)
        val route = presenter.functionBody("refreshLateIdentityImage")
        assertTrue("an unrecorded peer is enough to rewrite", "unrecordedContactPictures(" in route)
        val image = source("NotificationIdentityImage.kt").extensionBody("identityImage")
        assertTrue("the peer is the card's contact", "contact?.takeIf(String::isNotBlank) ?:" in image)
        val shortcuts = source("NotificationConversationShortcuts.kt")
        val owned = shortcuts.functionBody("ownedShortcutAvatars")
        assertTrue("the shortcut reads the peer's choice", "directContactAvatar(update, identityContact)" in owned)
        val publish = shortcuts.functionBody("publishConversationShortcut")
        assertTrue("again under the lock", "directContactAvatar(update, identityContact)" in publish)
        assertTrue("where it is applied", "withPictureContact(context, contact, update.sender.accountIdHex," in publish)
        val stamp = shortcuts.extensionBody("withPictureContact")
        assertTrue(
            "and is stamped for a later Save or Clear",
            "stampContactPictureShortcut(this, contact ?: sender, conversationIcon = contact != null)" in stamp,
        )
    }

    /**
     * #3635: a cleared private picture shows what the chat row shows, the public picture or the row's stored copy,
     * at every write that reads the choice, and the app lock is checked before either is read.
     */
    @Test
    fun clearedPictureShowsWhatTheChatRowShows() {
        val choice = stateSource("AppState.kt").functionBody("contactAvatarOverride")
        val lock = choice.indexOf("if (appLockScreenVisible) return true to null")
        val fallback = choice.indexOf("?: contactRowPicture(account, contact)")
        assertTrue("the row's stored copy follows the public picture", fallback > choice.indexOf("peekBitmap("))
        assertTrue("and only after the lock check", lock in 0 until fallback)
        val row =
            stateSource("PreWarmedNotificationAvatars.kt")
                .declaration("internal fun List<ChatListItem>.readyContactRowPicture(")
        assertTrue("through the chat row's own peer rule", "privatePictureContact().equals(contact" in row)
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

    /** The `when` branch that [marker] opens, failing when the branch is gone, which `substringAfter` never does. */
    private fun String.branch(marker: String): String {
        val start = indexOf(marker)
        require(start >= 0) { "Missing branch $marker" }
        return kotlinBlockFrom(start + marker.length - 1, "branch $marker")
    }

    /** The text before [marker], failing when the marker is gone, which `substringBefore` never does. */
    private fun String.before(marker: String): String {
        val end = indexOf(marker)
        require(end >= 0) { "Missing $marker" }
        return substring(0, end)
    }

    /** The late refresh's loader wiring, which AppState hands its loaders to. */
    private fun lateLoaders(): String = stateSource("PreWarmedNotificationAvatars.kt").extensionBody("withLateLoaders")

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
