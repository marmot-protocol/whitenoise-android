package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import dev.ipf.whitenoise.android.notificationDeliveryControllerSource
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins shortcut and notification-channel privacy cleanup at account and conversation lifecycle boundaries. */
class ConversationShortcutLifecycleCoverageTest {
    @Test
    fun accountSwitchHidesDirectShareBeforeChangingActiveAccount() {
        val body = appStateSection("suspend fun setActiveAccount", "private fun clearInMemoryMediaCaches")
        val clearIndex = body.indexOf("hideConversationShortcutsFromDirectShare()")
        val switchIndex = body.indexOf("activeAccountRef = label")

        assertTrue(
            "account switches must hide prior-account Direct Share targets before changing the active account",
            clearIndex >= 0 && switchIndex > clearIndex,
        )
    }

    /** Sign-out clears shortcut surfaces for the signed-out account only. */
    @Test
    fun nonDestructiveSignOutClearsOnlyTheSignedOutAccountsShortcutSurfaces() {
        val body = appStateSection("suspend fun signOutActiveAccount", "suspend fun signOutAndWipeActiveAccount")

        assertTrue(
            "sign-out must clear dynamic and long-lived shortcuts for the signed-out account",
            "accountRef = account" in body && "includeUnscopedLegacy = accounts.none" in body,
        )
    }

    /** Last-account wipe clears the wiped account's shortcuts only after local cleanup completes. */
    @Test
    fun destructiveLastAccountWipeClearsOnlyTheWipedAccountsShortcutSurfaces() {
        val body =
            appStateSection(
                "suspend fun signOutAndWipeActiveAccount",
                "private fun persistHostSetting(",
            )
        val wipeIndex = body.indexOf("wipeAccountAndPurgeVoiceDrafts(wipedRef)")
        val completedCleanupIndex = body.indexOf("if (!outcome.localCleanup.completed)")
        val clearIndex = body.indexOf("accountRef = wipedRef")

        assertTrue(
            "destructive wipe must clear only the wiped account's shortcuts after cleanup completes",
            wipeIndex >= 0 && completedCleanupIndex > wipeIndex && clearIndex > completedCleanupIndex,
        )
    }

    /** Causal facade/coordinator tests execute these entrypoints; the registered gate prevents bypassing them. */
    @Test
    fun lifetimeAndNativeFalseRemainAtTheActualOwnershipMutationBoundaries() {
        val notificationDeliverySource = notificationDeliveryControllerSource()
        val bootstrap =
            appStateSection(
                "private suspend fun startBootstrapRuntime()",
                "private suspend fun startBootstrapRuntimeLocked",
            )
        assertTrue("bootstrap open/start/failure close must share lifetime", "withAccountLifetime" in bootstrap)
        val signOut =
            appStateSection(
                "private suspend fun admittedNativeSignOutLocked",
                "private suspend fun finishOwnedSignOutLocked",
            )
        val falseCall = signOut.indexOf("setNativePushEnabled(account, false)")
        val nativeCall = signOut.indexOf("runtime.marmot.signOut(account")
        assertTrue("native false must precede native signout", falseCall >= 0 && nativeCall > falseCall)
        val route = appStateSection("private suspend fun routePendingAccountSetup", "suspend fun setActiveAccount")
        assertTrue(
            "pending route null publication must retain original selection identity",
            "accountSwitchHandoff.isCurrent(selectionGeneration)" in route,
        )
        assertTrue(
            "explicit settings must durably supersede cleanup before positive write",
            "pushTokenStore.clearPendingDisable(account)" in notificationDeliverySource &&
                "setNativePushEnabledLocked" in notificationDeliverySource,
        )
    }

    /** Account sign-out and wipe must reach destructive deletion of owned profile and `:conv:` channels */
    @Test
    fun accountCleanupDeletesOwnedProfileAndConversationNotificationChannels() {
        val appStateCleanup = appStateSource().readText().functionBody("clearRevokedConversationShortcutsForAccount")
        val presenterCleanup =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/notifications/LocalNotificationPresenter.kt")
                .functionBody("clearRevokedConversationShortcutsForAccount")
        val shortcutsHelper =
            appModuleSource(
                "src/main/java/dev/ipf/whitenoise/android/notifications/NotificationConversationShortcuts.kt",
            ).functionBody("clearRevokedConversationShortcutsForAccount")
        val platformCleanup =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/notifications/ConversationShortcuts.kt")
                .functionBody("clearRevokedConversationShortcutsForAccount")
        val cleanupSource =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/notifications/NotificationChannelCleanup.kt")
        val channelCleanup = cleanupSource.functionBody("deleteAccountNotificationChannels")

        assertTrue(
            "Account cleanup must cancel each known conversation's cards, which outlive deleted channels",
            "retirement.dismissConversationMessages(accountRef, groupIdHex" in presenterCleanup &&
                "knownGroupIds.forEach" in presenterCleanup,
        )
        assertTrue(
            "NotificationConversationShortcuts must pass cached account shortcut ids to platform cleanup",
            "additionalShortcutIds = snapshotShortcutIds" in shortcutsHelper,
        )
        assertTrue(
            "ConversationShortcuts cleanup must delete channels under mutationLock",
            "UserEventNotificationGroup.mutationLock" in platformCleanup &&
                "deleteAccountNotificationChannels(" in platformCleanup,
        )
        assertTrue(
            "Account channel cleanup must delete both profile and :conv: channels via isolated helper",
            "profileNotificationChannelAccountPrefix(accountRef)" in channelCleanup &&
                "conversationChannelShortcutId(channel)" in channelCleanup &&
                "deleteMatchingNotificationChannels(" in channelCleanup,
        )
        assertAppStateForwardsKnownGroupIds()
        assertForwardsKnownGroupIds(
            appStateCleanup,
            "localNotificationPresenter.clearRevokedConversationShortcutsForAccount",
        )
        assertForwardsKnownGroupIds(
            presenterCleanup,
            "conversationShortcuts.clearRevokedConversationShortcutsForAccount",
        )
        assertForwardsKnownGroupIds(shortcutsHelper, "clearRevokedConversationShortcutsForAccount")
        assertForwardsKnownGroupIds(platformCleanup, "deleteAccountNotificationChannels")
        assertTrue(
            "deleteAccountNotificationChannels must target the captured group ids",
            "groupChannelShortcutIds(accountRef, knownGroupIds" in channelCleanup,
        )
    }

    /** Asserts every AppState hop from sign-out or wipe capture to account cleanup forwards the captured group ids. */
    private fun assertAppStateForwardsKnownGroupIds() {
        val appState = appStateSource().readText()
        assertForwardsKnownGroupIds(
            appState.functionBody("signOutAndWipeActiveAccountLocked"),
            "finishRevokedAccountWipe",
        )
        assertForwardsKnownGroupIds(
            appState.functionBody("revokeAndFinishSignOutLocked"),
            "finishRevokedAccountSignOut",
        )
        assertForwardsKnownGroupIds(appState.functionBody("finishRevokedAccountSignOut"), "finishOwnedSignOutLocked")
        assertForwardsKnownGroupIds(
            appState.functionBody("finishOwnedSignOutLocked"),
            "clearRevokedConversationShortcutsForAccount",
        )
        assertForwardsKnownGroupIds(
            appState.functionBody("finishRevokedAccountWipe"),
            "clearRevokedConversationShortcutsForAccount",
        )
    }

    /** Asserts [body] passes `knownGroupIds` as an argument of its call to [callee]. */
    private fun assertForwardsKnownGroupIds(
        body: String,
        callee: String,
    ) {
        val argumentLists =
            Regex("""(?<![\w.])${Regex.escape(callee)}\(""")
                .findAll(body)
                .map { match ->
                    body.kotlinBlockFrom(match.range.last, "$callee arguments", opening = '(', closing = ')')
                }.toList()
        assertTrue("expected a call to $callee", argumentLists.isNotEmpty())
        val forwardsIds = Regex("""(^|[\s,(=])knownGroupIds\s*(,|\)|$)""")
        assertTrue(
            "$callee must receive the captured knownGroupIds",
            argumentLists.any { forwardsIds.containsMatchIn(it) },
        )
    }

    /** Channels are redacted and deleted before shortcut removal, so Android cannot soft-delete a named one first. */
    @Test
    fun channelCleanupRunsBeforeShortcutRemoval() {
        val platformCleanup =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/notifications/ConversationShortcuts.kt")
                .functionBody("clearRevokedConversationShortcutsForAccount")
        val pinnedLifecycle =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/PinnedShortcutLifecycle.kt")
        assertChannelsFirst(platformCleanup, "deleteAccountNotificationChannels(", "disableShortcuts(")
        assertChannelsFirst(
            pinnedLifecycle.functionBody("removePinnedConversationShortcuts"),
            "deleteConversationNotificationChannels(",
            ".removeGroup(",
        )
    }

    /** Asserts [body] calls [channelCall] before [shortcutCall]. */
    private fun assertChannelsFirst(
        body: String,
        channelCall: String,
        shortcutCall: String,
    ) {
        val channelAt = body.indexOf(channelCall)
        val shortcutAt = body.indexOf(shortcutCall)
        assertTrue("expected $channelCall and $shortcutCall", channelAt >= 0 && shortcutAt >= 0)
        assertTrue("$channelCall must run before $shortcutCall", channelAt < shortcutAt)
    }

    /** Sign-out capture includes pending local-delete journal groups, whose replay stops once the account leaves. */
    @Test
    fun signOutCaptureIncludesPendingLocalDeleteJournalGroups() {
        val capture =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/PinnedShortcutLifecycle.kt")
                .functionBody("readAccountKnownGroupIds")

        assertTrue(
            "captureAccountKnownGroupIds must read the departing account's pending local-delete journal groups",
            "localGroupDeleteCleanupJournal.groupIdsForAccount(accountRef)" in capture,
        )
        assertTrue(
            "An unreadable journal must leave the inventory incomplete, so the unaccounted-channel check runs",
            "journaled.isSuccess" in capture && "inventoryComplete =" in capture,
        )
    }

    /** Both shortcut publishers in a post honour the cleanup epoch, so a stale post cannot republish a cleared one. */
    @Test
    fun postShortcutPublicationHonoursTheCleanupEpoch() {
        val show =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/notifications/LocalNotificationPresenter.kt")
                .functionBody("show")
        val publish = show.substringAfter("publishShortcutBeforePost(").substringBefore("} ?:")
        assertTrue("the message shortcut must use the channel write check", "channelWriteAllowed()" in publish)
        assertTrue(
            "the reaction shortcut must receive the cleanup epoch",
            "publishReactionShortcutBeforePost(update, notificationContent, channelWriteEpoch)" in show,
        )
        val reaction =
            appModuleSource(
                "src/main/java/dev/ipf/whitenoise/android/notifications/LocalNotificationPresenterShortcuts.kt",
            ).functionBody("publishReactionShortcutBeforePost")
        assertTrue(
            "the reaction shortcut must check the cleanup epoch against its own id",
            "NotificationChannelWriteGuard.isCurrent(channelWriteEpoch, update.accountRef, id)" in reaction,
        )
    }

    /**
     * The capture is saved to the owner store before native sign-out or wipe commits, so a process death
     * before Android cleanup still leaves account-refresh retention a record of the departed channels.
     */
    @Test
    fun signOutCaptureIsSavedBeforeNativeRevocation() {
        val capture =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/PinnedShortcutLifecycle.kt")
                .functionBody("captureAccountKnownGroupIds")
        val appState = appStateSource().readText()

        val captureArgs = "appContext, accountRef, groupIds, includeUnscopedLegacy"
        assertTrue(
            "captureAccountKnownGroupIds must save the departing account's channel owners",
            "recordDepartingConversationChannelOwners($captureArgs)" in capture,
        )
        assertTrue(
            "captureAccountKnownGroupIds must guard incomplete multi-account reads against unowned :conv: channels",
            "hasUnaccountedConversationNotificationChannels(appContext, accountRef, groupIds)" in capture,
        )
        val signOutBody = appState.functionBody("revokeAndFinishSignOutLocked")
        val wipeBody = appState.functionBody("signOutAndWipeActiveAccountLocked")
        assertCapturedBefore(signOutBody, "withRevokedPinnedTarget(")
        assertCapturedBefore(wipeBody, "withRevokedPinnedTarget(")
        assertTrue(
            "revokeAndFinishSignOutLocked must abort before native work when channel capture fails",
            "?: return OwnedSignOutCompletion(SignOutCompletion.AccountCleanupIncomplete)" in signOutBody,
        )
        assertTrue(
            "signOutAndWipeActiveAccountLocked must abort before revocation when channel capture fails",
            "captureAccountKnownGroupIds(wipedRef) ?: return null" in wipeBody,
        )
    }

    /** Asserts [body] captures the departing account's groups before [nativeCall]. */
    private fun assertCapturedBefore(
        body: String,
        nativeCall: String,
    ) {
        val captureAt = body.indexOf("captureAccountKnownGroupIds(")
        val nativeAt = body.indexOf(nativeCall)
        assertTrue("expected the capture and $nativeCall", captureAt >= 0 && nativeAt >= 0)
        assertTrue("the capture must be saved before $nativeCall", captureAt < nativeAt)
    }

    /** Local conversation deletion must delete the conversation's `:conv:` notification channels */
    @Test
    fun localConversationRemovalDeletesOwnedConversationNotificationChannels() {
        val pinnedLifecycle =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/PinnedShortcutLifecycle.kt")
        val removePinned = pinnedLifecycle.functionBody("removePinnedConversationShortcuts")
        val channelCleanup =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/notifications/NotificationChannelCleanup.kt")
                .functionBody("deleteConversationNotificationChannels")

        assertTrue(
            "removePinnedConversationShortcuts must delete conversation channels under mutationLock",
            "UserEventNotificationGroup.mutationLock" in removePinned &&
                "deleteConversationNotificationChannels(appContext, accountRef, groupIdHex)" in removePinned,
        )
        assertTrue(
            "deleteConversationNotificationChannels must delete matched channels under mutationLock",
            "UserEventNotificationGroup.mutationLock" in channelCleanup &&
                "deleteMatchingNotificationChannels(" in channelCleanup,
        )
    }

    /** Channel creation records ownership under mutationLock and account retention prunes stale owner keys */
    @Test
    fun channelCreationAndAccountRetentionMaintainConversationChannelOwnership() {
        val channelsSource =
            appModuleSource(
                "src/main/java/dev/ipf/whitenoise/android/notifications/ConversationNotificationChannels.kt",
            )
        val ensureBody = channelsSource.functionBody("ensureConversationChannel")
        val routingSource =
            appModuleSource(
                "src/main/java/dev/ipf/whitenoise/android/notifications/ConversationNotificationRouting.kt",
            )
        val resolveBody = routingSource.functionBody("resolveForPost")
        val mutesSource =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/AppStateNotificationMutes.kt")
        val retainBody = mutesSource.functionBody("retainProfileAlertsForAccounts")

        assertTrue(
            "ensureConversationChannel must record ownership under mutationLock",
            "UserEventNotificationGroup.mutationLock" in ensureBody &&
                "recordConversationChannelOwner(context, accountRef, conversationShortcutId)" in ensureBody,
        )
        assertTrue(
            "resolveForPost must forward accountRef when creating :conv: channels",
            "accountRef = accountRef" in resolveBody,
        )
        assertTrue(
            "retainProfileAlertsForAccounts must prune stale conversation-channel owner records",
            "retainConversationChannelOwners(" in retainBody && "canRetain = canRetain" in retainBody,
        )
        assertTrue(
            "retention must only sweep unowned conversation channels while no signing account remains",
            "signingAccountRemains = accounts.any { it.isSignedInSigningAccount() }" in retainBody,
        )
    }

    /** Conversation notification settings must capture its write guard before suspending on routing.settings. */
    @Test
    fun conversationNotificationSettingsCapturesWriteGuardBeforeSuspending() {
        val screenSource =
            appModuleSource(
                "src/main/java/dev/ipf/whitenoise/android/ui/group/ConversationNotificationSettingsScreen.kt",
            )
        val guardAt = screenSource.indexOf("captureConversationChannelWriteGuard(")
        val settingsAt = screenSource.indexOf("routing.settings(descriptor)")

        assertTrue(
            "expected captureConversationChannelWriteGuard and routing.settings",
            guardAt >= 0 && settingsAt >= 0,
        )
        assertTrue(
            "ConversationNotificationSettingsScreen must capture the write guard before suspending on routing.settings",
            guardAt < settingsAt,
        )
    }

    private fun appStateSection(
        startMarker: String,
        endMarker: String,
    ): String {
        val source = appStateSource().readText()
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, startIndex = start + startMarker.length)
        check(start >= 0 && end > start) { "Missing AppState section $startMarker .. $endMarker" }
        return source.substring(start, end)
    }

    private fun appStateSource(): File {
        val candidates =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            )
        return candidates.firstOrNull(File::isFile) ?: error("Missing AppState.kt source file")
    }
}
