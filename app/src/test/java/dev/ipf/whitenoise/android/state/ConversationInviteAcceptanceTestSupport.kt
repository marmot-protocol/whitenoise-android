@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/** Shared canonical records for stale-invitation integration coverage. */
internal object InviteAcceptanceTestData {
    const val ACCOUNT = "account-a"
    private const val ACCOUNT_ID = "aa"
    const val OTHER_ACCOUNT = "account-b"
    private const val OTHER_ACCOUNT_ID = "bb"
    private const val PEER_ID = "cc"
    const val GROUP_ID = "group"
    const val OLD_WELCOME = "welcome-old"
    const val NEW_WELCOME = "welcome-new"
    const val LATE_ACCEPTED_NAME = "Late accepted result"

    /** Provides both account labels so account-pinned presentation is testable. */
    fun appState(activeAccountRef: String = ACCOUNT) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(InviteDraftPersistence()),
            accountIdHexResolver = { ref -> if (ref == ACCOUNT) ACCOUNT_ID else OTHER_ACCOUNT_ID },
            accounts =
                listOf(
                    account(ACCOUNT, ACCOUNT_ID),
                    account(OTHER_ACCOUNT, OTHER_ACCOUNT_ID),
                ),
            activeAccountRef = activeAccountRef,
        )

    /** Cached self row used for the invitation's immediate presentation. */
    fun memberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = ACCOUNT_ID,
                    account = ACCOUNT,
                    local = true,
                ),
            ),
        )

    /** Authoritative member roster representing an invite accepted elsewhere. */
    fun memberRoster() =
        roster(
            selfMembership = SelfMembershipFfi.MEMBER,
            member(
                memberIdHex = ACCOUNT_ID,
                account = ACCOUNT,
                local = true,
                isSelf = true,
            ),
        )

    /** Authoritative terminal roster representing removal before Join settled. */
    fun removedRoster() =
        roster(
            selfMembership = SelfMembershipFfi.REMOVED,
            member(memberIdHex = PEER_ID),
        )

    /** Produces the exact canonical group fields relevant to generation fencing. */
    fun group(
        pending: Boolean,
        welcome: String?,
        selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
    ) = AppGroupRecordFfi(
        groupIdHex = GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = true,
        name = "Invite group",
        description = "",
        admins = emptyList(),
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "04".repeat(32),
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia = encryptedMedia(),
        disappearingMessageSecs = 0uL,
        archived = false,
        pendingConfirmation = pending,
        unrecoverable = false,
        selfMembership = selfMembership,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        disbanding = false,
        disbandRequest = null,
        disbanded = false,
        welcomerAccountIdHex = PEER_ID,
        viaWelcomeMessageIdHex = welcome,
    )

    /** Pending row owned by the account that must not receive a pinned mutation. */
    fun chatListRow(pending: Boolean) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = GROUP_ID,
            archived = false,
            pendingConfirmation = pending,
            title = "Other account group",
            groupName = "Other account group",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 0uL,
            activitySortAt = 0uL,
            updatedAt = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.GROUP,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    /** Resolves a production source file from either Gradle's module or repository working directory. */
    fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).first(File::exists).readText()

    /** Minimal account record used by the controller identity boundary. */
    private fun account(
        label: String,
        id: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = id,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    /** Builds a roster whose count and self-membership are internally consistent. */
    private fun roster(
        selfMembership: SelfMembershipFfi,
        vararg members: GroupMemberDetailsFfi,
    ) = GroupRosterFfi(
        groupIdHex = GROUP_ID,
        members = members.toList(),
        epoch = 7uL,
        rosterRevision = 11uL,
        selfMembership = selfMembership,
        memberCount = members.size.toUInt(),
        lifecycleState = GroupLifecycleStateFfi.STABLE,
    )

    /** Builds one authoritative member row. */
    private fun member(
        memberIdHex: String,
        account: String? = null,
        local: Boolean = false,
        isSelf: Boolean = false,
    ) = GroupMemberDetailsFfi(
        memberIdHex = memberIdHex,
        account = account,
        local = local,
        isAdmin = false,
        isSelf = isSelf,
        npub = "npub-$memberIdHex",
        displayName = null,
    )

    /** Encrypted-media capability required by canonical group records. */
    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(
                        locatorKind = "blossom-v1",
                        baseUrl = "https://blossom.example",
                    ),
                ),
        )
}

/** Tracks fixture controllers so each test releases controller-owned jobs. */
internal class InviteAcceptanceOwnerFixtures {
    private val controllers = mutableListOf<ConversationController>()
    private val chatsControllers = mutableListOf<ChatsController>()

    /** Registers either controller type for deterministic teardown. */
    fun <T> track(controller: T): T {
        when (controller) {
            is ConversationController -> controllers += controller
            is ChatsController -> chatsControllers += controller
        }
        return controller
    }

    /** Builds a controller whose invite and roster boundaries are deterministic. */
    fun controller(
        appState: WhiteNoiseAppState,
        accept: InviteAcceptor,
        roster: suspend (String, String) -> GroupRosterFfi = { _, _ -> InviteAcceptanceTestData.memberRoster() },
    ) = track(
        ConversationController(
            appState = appState,
            initialGroup =
                InviteAcceptanceTestData.group(
                    pending = true,
                    welcome = InviteAcceptanceTestData.OLD_WELCOME,
                ),
            initialMemberSnapshot = InviteAcceptanceTestData.memberSnapshot(),
            inviteAcceptor = accept,
            groupRosterReader = roster,
        ),
    )

    /** Attaches a same-account row so late accepted projection writes are observable. */
    fun attachedChatList(appState: WhiteNoiseAppState): ChatsController {
        val controller =
            track(
                ChatsController(
                    appState = appState,
                    initialAccountRef = InviteAcceptanceTestData.ACCOUNT,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                ),
            )
        appState.attachChatsController(controller)
        controller.setChatListVisible(false)
        controller.applyChatListRow(InviteAcceptanceTestData.chatListRow(pending = true))
        controller.setChatListVisible(true)
        return controller
    }

    /** Releases all tracked owners, including a controller a test already disposed. */
    fun release() {
        controllers.forEach { it.onCleared() }
        chatsControllers.forEach { it.onCleared() }
        controllers.clear()
        chatsControllers.clear()
    }
}

/** Proves disposal rejects a queued Retry before it can start another roster read. */
internal suspend fun TestScope.assertQueuedAuthorityRetryDoesNoWork(
    owners: InviteAcceptanceOwnerFixtures,
    dispose: suspend (ConversationController) -> Unit,
) {
    var rosterReads = 0
    val controller =
        owners.controller(
            appState = InviteAcceptanceTestData.appState(),
            accept = { _, _ -> throw MarmotKitException.GroupInviteNotPending() },
            roster = { _, _ ->
                rosterReads += 1
                error("authority unavailable")
            },
        )
    assertFalse(controller.acceptInvite(notify = false))
    assertEquals(1, rosterReads)

    val retry = async { controller.retryInviteAcceptanceAuthority() }
    dispose(controller)
    retry.await()

    assertEquals(1, rosterReads)
    assertTrue(controller.inviteAcceptanceResolutionPending)
}

/** Proves every native retry revalidates the generation immediately before invocation. */
internal suspend fun TestScope.assertBusyRetryDoesNotCrossAuthoritativeUpdate(
    owners: InviteAcceptanceOwnerFixtures,
    update: AppGroupRecordFfi,
) {
    var nativeAttempts = 0
    val appState = InviteAcceptanceTestData.appState()
    val controller =
        owners.controller(
            appState = appState,
            accept = { _, _ ->
                nativeAttempts += 1
                throw MarmotKitException.RuntimeBusy()
            },
        )
    val acceptance = async { controller.acceptInvite(notify = false) }
    runCurrent()
    assertEquals(1, nativeAttempts)

    controller.applyGroupStateForTest(update)
    advanceTimeBy(IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS)
    runCurrent()

    assertFalse(acceptance.await())
    assertEquals(1, nativeAttempts)
    assertEquals(update.viaWelcomeMessageIdHex, controller.group.viaWelcomeMessageIdHex)
    assertEquals(update.selfMembership, controller.group.selfMembership)
    assertEquals(update.pendingConfirmation, controller.group.pendingConfirmation)
    assertNull(appState.transientNotice)
}

/** Proves disposal rejects an authority result that was already awaiting native I/O. */
internal suspend fun TestScope.assertHeldAuthorityCompletionDoesNotPublish(
    owners: InviteAcceptanceOwnerFixtures,
    dispose: suspend (ConversationController) -> Unit,
) {
    val readStarted = CompletableDeferred<Unit>()
    val releaseRead = CompletableDeferred<Unit>()
    var rosterReads = 0
    val appState = InviteAcceptanceTestData.appState()
    val chatsController = owners.attachedChatList(appState)
    runCurrent()
    val controller =
        owners.controller(
            appState = appState,
            accept = { _, _ -> throw MarmotKitException.GroupInviteNotPending() },
            roster = { _, _ ->
                rosterReads += 1
                if (rosterReads == 1) error("authority unavailable")
                readStarted.complete(Unit)
                releaseRead.await()
                InviteAcceptanceTestData.removedRoster()
            },
        )
    assertFalse(controller.acceptInvite(notify = false))
    val retry = async { controller.retryInviteAcceptanceAuthority() }
    readStarted.await()

    dispose(controller)
    releaseRead.complete(Unit)
    retry.await()
    runCurrent()

    assertEquals(2, rosterReads)
    assertEquals(SelfMembershipFfi.MEMBER, controller.group.selfMembership)
    assertEquals(
        SelfMembershipFfi.MEMBER,
        chatsController.items
            .single()
            .group.selfMembership,
    )
    assertTrue(controller.inviteAcceptanceResolutionPending)
}

/** In-memory persistence keeps the fixture independent from process-level preferences. */
private class InviteDraftPersistence : DraftPersistence {
    private val drafts = mutableMapOf<String, String>()

    /** Returns an isolated draft snapshot so fixture mutations cannot alias storage. */
    override fun read(): Map<String, String> = drafts.toMap()

    /** Mirrors production's null-as-delete contract for the in-memory fixture. */
    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) drafts.remove(key) else drafts[key] = value
    }
}
