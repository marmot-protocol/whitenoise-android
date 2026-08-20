package dev.ipf.whitenoise.android.state

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.ui.chats.chatListItemAvatarAccount
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@Suppress("LargeClass") // Roster-hydration scenarios share one expensive controller fixture.
class ChatsControllerDmMemberHydrationTest {
    @get:Rule val composeRule = createComposeRule()
    private val boundControllers = mutableListOf<ChatsController>()

    @After
    fun tearDown() {
        boundControllers.forEach(ChatsController::onCleared)
        boundControllers.clear()
    }

    private fun bindDmController(
        selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
        conversationKind: ChatConversationKindFfi = ChatConversationKindFfi.UNKNOWN,
        retryDelayMillis: (Int) -> Long = ::memberSnapshotRetryDelayMillis,
        loader: suspend (String, String) -> List<AppGroupMemberRecordFfi>,
    ): ChatsController {
        val appState = testAppState()
        val controller = ChatsController(appState, ACCOUNT_REF, retryDelayMillis, loader)
        boundControllers += controller
        composeRule.setContent {}
        composeRule.runOnIdle {
            controller.setChatListVisible(false)
            controller.applyChatListRow(dmRow(selfMembership, conversationKind))
            controller.applyLocalGroupUpdate(unnamedDmGroup(selfMembership))
            controller.setChatListVisible(true)
        }
        composeRule.waitForIdle()
        return controller
    }

    @Test
    fun cachedMemberSnapshotKeepsResolvedTitleWhileRosterHydrates() {
        val copy =
            GroupTitleCopy(
                inviteFromFormat = "Invite from %1\$s",
                groupOfPeopleFormat = "Group of %1\$d people",
                unknownTitle = "Unknown",
            )
        val appState = testAppState()
        // A previous session's group-details/conversation read left the roster
        // in the account-scoped snapshot cache. A fresh bind must use it as
        // the last-known presentation instead of flashing Unknown while the
        // live roster loader is still in flight.
        appState.cacheGroupMemberSnapshot(
            ACCOUNT_REF,
            DM_GROUP,
            listOf(member(ME, local = true), member(PEER, local = false)),
        )
        var releaseRoster = false
        val controller =
            ChatsController(appState, ACCOUNT_REF, ::memberSnapshotRetryDelayMillis) { _, _ ->
                while (coroutineContext.isActive && !releaseRoster) {
                    delay(10)
                }
                listOf(member(ME, local = true), member(PEER, local = false))
            }
        boundControllers += controller
        composeRule.setContent {}
        composeRule.runOnIdle {
            controller.setChatListVisible(false)
            controller.applyChatListRow(dmRow(SelfMembershipFfi.MEMBER, ChatConversationKindFfi.UNKNOWN))
            controller.applyLocalGroupUpdate(unnamedDmGroup(SelfMembershipFfi.MEMBER))
            controller.setChatListVisible(true)
        }
        awaitCondition { controller.items.isNotEmpty() }

        val item = controller.items.single()
        assertEquals(
            "Peer Name",
            GroupProjector.displayTitle(
                group = item.group,
                otherMemberAccount = item.presentationOtherMemberAccount,
                memberCount = item.presentationMemberCount,
                memberTitle = { "Peer Name" },
                copy = copy,
            ),
        )
        releaseRoster = true
    }

    @Test
    fun emptyInitialMemberIdPageDoesNotOverwriteAResolvedRoster() {
        val controller =
            bindDmController { _, _ ->
                listOf(member(ME, local = true), member(PEER, local = false))
            }
        awaitCondition { controller.items.singleOrNull()?.otherMemberAccount == PEER }

        // An initial member-id page carrying an empty roster is a transient
        // catch-up result. Caching it would pin the Unknown fallback forever —
        // a cached key is never retried.
        ChatsController::class.java
            .getDeclaredMethod(
                "applyInitialMemberIdProjections",
                List::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            .invoke(
                controller,
                listOf(AppGroupMemberIdsFfi(DM_GROUP, emptyList(), emptyList())),
                ME,
                false,
            )

        // A self-only roster for a direct conversation is equally transient —
        // the streaming path grace-retries it, so the page path must too.
        ChatsController::class.java
            .getDeclaredMethod(
                "applyInitialMemberIdProjections",
                List::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            .invoke(
                controller,
                listOf(AppGroupMemberIdsFfi(DM_GROUP, listOf(ME), emptyList())),
                ME,
                false,
            )

        @Suppress("UNCHECKED_CAST")
        val cache =
            ChatsController::class.java
                .getDeclaredField("memberCacheByGroup")
                .apply { isAccessible = true }
                .get(controller) as Map<String, List<AppGroupMemberRecordFfi>>
        assertEquals(2, cache.getValue(DM_GROUP).size)
    }

    @Test
    fun unnamedDmRecomputesPeerWhenMemberSnapshotLoaderCompletes() {
        var releaseRoster = false
        val controller =
            bindDmController { _, _ ->
                while (coroutineContext.isActive && !releaseRoster) {
                    delay(10)
                }
                listOf(member(ME, local = true), member(PEER, local = false))
            }

        awaitCondition { controller.items.isNotEmpty() }
        assertNull(controller.items.single().otherMemberAccount)

        releaseRoster = true

        awaitCondition {
            controller.items
                .single()
                .otherMemberAccount
                ?.equals(PEER, ignoreCase = true) == true
        }
        assertEquals(PEER, controller.items.single().otherMemberAccount)
    }

    @Test
    fun failedMemberFetchRetriesAndResolvesPeer() {
        var attempts = 0
        var allowSuccess = false
        val controller =
            bindDmController { _, _ ->
                attempts += 1
                if (!allowSuccess) error("transient roster read failure")
                listOf(member(ME, local = true), member(PEER, local = false))
            }

        awaitCondition { controller.items.isNotEmpty() }
        awaitCondition { attempts >= 1 }
        assertNull(controller.items.single().otherMemberAccount)

        allowSuccess = true

        awaitCondition(timeoutMs = 10_000) { attempts >= 2 }
        awaitCondition(timeoutMs = 10_000) {
            controller.items
                .single()
                .otherMemberAccount
                ?.equals(PEER, ignoreCase = true) == true
        }
        assertTrue(attempts >= 2)
        assertEquals(PEER, controller.items.single().otherMemberAccount)
    }

    @Test
    fun ordinaryRecomputeDoesNotBypassScheduledMemberRetry() {
        var attempts = 0
        val controller =
            bindDmController(retryDelayMillis = { 60_000L }) { _, _ ->
                attempts += 1
                error("persistent roster read failure")
            }

        awaitCondition { attempts >= 1 }
        composeRule.runOnIdle {
            controller.applyChatListRow(
                dmRow(SelfMembershipFfi.MEMBER, ChatConversationKindFfi.UNKNOWN).copy(updatedAt = 2uL),
            )
        }
        drainMainLooperFor(100)

        assertEquals(1, attempts)
    }

    @Test
    fun authoritativeGroupUpdateWakesScheduledMemberRetry() {
        var attempts = 0
        val controller =
            bindDmController(retryDelayMillis = { 60_000L }) { _, _ ->
                attempts += 1
                if (attempts == 1) error("roster not ready")
                listOf(member(ME, local = true), member(PEER, local = false))
            }

        awaitCondition { attempts >= 1 }
        composeRule.runOnIdle {
            controller.applyLocalGroupUpdate(
                unnamedDmGroup(SelfMembershipFfi.MEMBER).copy(description = "updated"),
            )
        }

        awaitCondition { attempts >= 2 }
        awaitCondition { controller.items.single().otherMemberAccount == PEER }
    }

    @Test
    fun establishedDmKeepsPeerPresentationWithoutExposingStaleMembership() {
        var attempts = 0
        var releaseRefresh = false
        val controller =
            bindDmController(retryDelayMillis = { 60_000L }) { _, _ ->
                attempts += 1
                if (attempts == 1) {
                    return@bindDmController listOf(member(ME, local = true), member(PEER, local = false))
                }
                while (coroutineContext.isActive && !releaseRefresh) {
                    delay(10)
                }
                listOf(member(ME, local = true))
            }

        awaitCondition { controller.items.singleOrNull()?.otherMemberAccount == PEER }
        assertEquals(DM_GROUP, controller.existingDirectChat(PEER)?.id)

        composeRule.runOnIdle {
            controller.applyLocalGroupUpdate(
                unnamedDmGroup(SelfMembershipFfi.MEMBER).copy(description = "updated"),
            )
        }
        awaitCondition { attempts >= 2 }
        val refreshing = controller.items.single()
        assertNull(refreshing.memberSnapshot)
        assertNull(refreshing.otherMemberAccount)
        assertEquals(PEER, refreshing.presentationOtherMemberAccount)
        assertEquals(2, refreshing.presentationMemberCount)
        assertEquals(
            PEER,
            chatListItemAvatarAccount(refreshing),
        )
        assertNull(controller.existingDirectChat(PEER))

        releaseRefresh = true
        drainMainLooperFor(100)

        assertEquals(2, attempts)
        val transientSelfOnly = controller.items.single()
        assertNull(transientSelfOnly.memberSnapshot)
        assertEquals(PEER, transientSelfOnly.presentationOtherMemberAccount)
        assertNull(controller.existingDirectChat(PEER))
    }

    @Test
    fun persistentSelfOnlyDirectRosterBecomesAuthoritativeAfterOneGraceRetry() {
        var attempts = 0
        val controller =
            bindDmController(
                conversationKind = ChatConversationKindFfi.DIRECT,
                retryDelayMillis = { 0L },
            ) { _, _ ->
                attempts += 1
                if (attempts == 1) {
                    listOf(member(ME, local = true), member(PEER, local = false))
                } else {
                    listOf(member(ME, local = true))
                }
            }

        awaitCondition { controller.items.singleOrNull()?.otherMemberAccount == PEER }
        composeRule.runOnIdle {
            controller.applyLocalGroupUpdate(
                unnamedDmGroup(SelfMembershipFfi.MEMBER).copy(description = "updated"),
            )
        }

        awaitCondition { attempts >= 3 }
        awaitCondition {
            controller.items.single().let { item ->
                item.memberSnapshot?.memberCount == 1 && item.presentationActiveAccountIsSoleMember
            }
        }

        val selfOnly = controller.items.single()
        assertEquals(3, attempts)
        assertEquals(
            "Just you",
            GroupProjector.displayTitle(
                group = selfOnly.group,
                otherMemberAccount = selfOnly.presentationOtherMemberAccount,
                memberCount = selfOnly.presentationMemberCount,
                memberTitle = { it },
                copy = GroupTitleCopy.Default,
                conversationKind = selfOnly.projection?.conversationKind,
                soleSelfMember = selfOnly.presentationActiveAccountIsSoleMember,
            ),
        )
        assertNull(controller.existingDirectChat(PEER))
    }

    @Test
    fun authoritativeSelfOnlyDmKeepsJustYouPresentationDuringNextRefresh() {
        var attempts = 0
        var releaseSecondRefresh = false
        val controller =
            bindDmController(
                conversationKind = ChatConversationKindFfi.DIRECT,
                retryDelayMillis = { 0L },
            ) { _, _ ->
                attempts += 1
                when {
                    attempts == 1 -> listOf(member(ME, local = true), member(PEER, local = false))
                    attempts <= 3 -> listOf(member(ME, local = true))
                    else -> {
                        while (coroutineContext.isActive && !releaseSecondRefresh) {
                            delay(10)
                        }
                        listOf(member(ME, local = true))
                    }
                }
            }

        awaitCondition { controller.items.singleOrNull()?.otherMemberAccount == PEER }
        composeRule.runOnIdle {
            controller.applyLocalGroupUpdate(
                unnamedDmGroup(SelfMembershipFfi.MEMBER).copy(description = "first update"),
            )
        }
        awaitCondition { attempts >= 3 }
        awaitCondition {
            controller.items
                .single()
                .memberSnapshot
                ?.memberCount == 1
        }

        composeRule.runOnIdle {
            controller.applyLocalGroupUpdate(
                unnamedDmGroup(SelfMembershipFfi.MEMBER).copy(description = "second update"),
            )
        }
        awaitCondition { attempts >= 4 }

        val refreshing = controller.items.single()
        assertNull(refreshing.memberSnapshot)
        assertTrue(refreshing.presentationActiveAccountIsSoleMember)
        assertEquals(
            "Just you",
            GroupProjector.displayTitle(
                group = refreshing.group,
                otherMemberAccount = refreshing.presentationOtherMemberAccount,
                memberCount = refreshing.presentationMemberCount,
                memberTitle = { it },
                copy = GroupTitleCopy.Default,
                conversationKind = refreshing.projection?.conversationKind,
                soleSelfMember = refreshing.presentationActiveAccountIsSoleMember,
            ),
        )
        releaseSecondRefresh = true
    }

    @Test
    fun failedRefreshDoesNotConsumeSelfOnlyDirectGraceRetry() {
        var attempts = 0
        var releaseFourthAttempt = false
        val controller =
            bindDmController(
                conversationKind = ChatConversationKindFfi.DIRECT,
                retryDelayMillis = { 0L },
            ) { _, _ ->
                attempts += 1
                when (attempts) {
                    1 -> listOf(member(ME, local = true), member(PEER, local = false))
                    2 -> error("transient roster read failure")
                    3 -> listOf(member(ME, local = true))
                    else -> {
                        while (coroutineContext.isActive && !releaseFourthAttempt) {
                            delay(10)
                        }
                        listOf(member(ME, local = true), member(PEER, local = false))
                    }
                }
            }

        awaitCondition { controller.items.singleOrNull()?.otherMemberAccount == PEER }
        composeRule.runOnIdle {
            controller.applyLocalGroupUpdate(
                unnamedDmGroup(SelfMembershipFfi.MEMBER).copy(description = "updated"),
            )
        }
        awaitCondition { attempts >= 4 }

        val awaitingGraceRetry = controller.items.single()
        assertNull(awaitingGraceRetry.memberSnapshot)
        assertEquals(PEER, awaitingGraceRetry.presentationOtherMemberAccount)
        releaseFourthAttempt = true
    }

    @Test
    fun transientEmptyRosterDoesNotPinUnknownTitle() {
        var attempts = 0
        val controller =
            bindDmController { _, _ ->
                attempts += 1
                when (attempts) {
                    1 -> emptyList()
                    else -> listOf(member(ME, local = true), member(PEER, local = false))
                }
            }

        awaitCondition { attempts >= 1 }
        awaitCondition(timeoutMs = 10_000) { attempts >= 2 }
        awaitCondition(timeoutMs = 10_000) {
            controller.items
                .single()
                .otherMemberAccount
                ?.equals(PEER, ignoreCase = true) == true
        }
    }

    @Test
    fun transientSelfOnlyDirectRosterDoesNotPinUnknownTitle() {
        var attempts = 0
        val controller =
            bindDmController(conversationKind = ChatConversationKindFfi.DIRECT) { _, _ ->
                attempts += 1
                when (attempts) {
                    1 -> listOf(member(ME, local = true))
                    else -> listOf(member(ME, local = true), member(PEER, local = false))
                }
            }

        awaitCondition(timeoutMs = 10_000) { attempts >= 2 }
        awaitCondition(timeoutMs = 10_000) {
            controller.items.single().otherMemberAccount == PEER
        }
    }

    @Test
    fun transientSelfOnlyUnknownRosterDoesNotPinUnknownTitle() {
        var attempts = 0
        val controller =
            bindDmController { _, _ ->
                attempts += 1
                when (attempts) {
                    1 -> listOf(member(ME, local = true))
                    else -> listOf(member(ME, local = true), member(PEER, local = false))
                }
            }

        awaitCondition(timeoutMs = 10_000) { attempts >= 2 }
        awaitCondition(timeoutMs = 10_000) {
            controller.items.single().otherMemberAccount == PEER
        }
    }

    @Test
    fun terminalRemovedEmptyRosterDoesNotRetryForever() {
        var attempts = 0
        bindDmController(selfMembership = SelfMembershipFfi.REMOVED) { _, _ ->
            attempts += 1
            emptyList()
        }

        awaitCondition { attempts >= 1 }
        drainMainLooperFor(1_000)

        assertEquals(1, attempts)
    }

    @Test
    fun displayTitleLeavesUnknownUntilPeerSnapshotThenResolves() {
        val copy =
            GroupTitleCopy(
                inviteFromFormat = "Invite from %1\$s",
                groupOfPeopleFormat = "Group of %1\$d people",
                unknownTitle = "Unknown",
            )
        var releaseRoster = false
        val controller =
            bindDmController { _, _ ->
                while (coroutineContext.isActive && !releaseRoster) {
                    delay(10)
                }
                listOf(member(ME, local = true), member(PEER, local = false))
            }
        awaitCondition { controller.items.isNotEmpty() }

        val item = controller.items.single()
        assertEquals(
            "Unknown",
            GroupProjector.displayTitle(
                group = item.group,
                otherMemberAccount = item.otherMemberAccount,
                memberCount = item.memberCount,
                memberTitle = { "Peer Name" },
                copy = copy,
            ),
        )

        releaseRoster = true
        awaitCondition { controller.items.single().otherMemberAccount == PEER }

        val resolved = controller.items.single()
        assertEquals(
            "Peer Name",
            GroupProjector.displayTitle(
                group = resolved.group,
                otherMemberAccount = resolved.otherMemberAccount,
                memberCount = resolved.memberCount,
                memberTitle = { "Peer Name" },
                copy = copy,
            ),
        )
    }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(HydrationTestDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ME,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun unnamedDmGroup(selfMembership: SelfMembershipFfi) =
        AppGroupRecordFfi(
            selfMembership = selfMembership,
            groupIdHex = DM_GROUP,
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-dm",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    private fun dmRow(
        selfMembership: SelfMembershipFfi,
        conversationKind: ChatConversationKindFfi,
    ) = ChatListRowFfi(
        selfMembership = selfMembership,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = DM_GROUP,
        archived = false,
        pendingConfirmation = false,
        title = DM_GROUP,
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage =
            ChatListMessagePreviewFfi(
                messageIdHex = "msg-1",
                sender = PEER,
                senderDisplayName = null,
                plaintext = "hi",
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks = emptyList(),
                        blankLinesBefore = ByteArray(0),
                    ),
                kind = 9uL,
                timelineAt = 1uL,
                deleted = false,
                attachmentKind = null,
                attachmentCount = 0u,
                deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
            ),
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 0uL,
        activitySortAt = 1uL,
        updatedAt = 1uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = conversationKind,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

    private fun member(
        accountIdHex: String,
        local: Boolean,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = accountIdHex,
        account = if (local) accountIdHex else null,
        local = local,
    )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(
                        locatorKind = "blossom-v1",
                        baseUrl = "https://blossom.primal.net",
                    ),
                ),
        )

    private fun drainMainLooperFor(durationMs: Long) {
        composeRule.waitForIdle()
        ShadowLooper.idleMainLooper(durationMs, TimeUnit.MILLISECONDS)
        composeRule.waitForIdle()
    }

    private fun awaitCondition(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        var elapsedMs = 0L
        while (elapsedMs <= timeoutMs) {
            composeRule.waitForIdle()
            ShadowLooper.idleMainLooper()
            if (condition()) return
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            elapsedMs += 20
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    private class HydrationTestDraftPersistence : DraftPersistence {
        private val values = mutableMapOf<String, String>()

        override fun read(): Map<String, String> = values.toMap()

        override fun write(
            key: String,
            value: String?,
        ) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        const val ME = "alice-id"
        const val PEER = "peer-id"
        const val DM_GROUP = "dm-group-id"
    }
}
