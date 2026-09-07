package dev.ipf.whitenoise.android.state

import android.content.Context
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
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorStringStore
import dev.ipf.whitenoise.android.media.editor.MessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/** Verifies retained composer geometry follows real leave and local-delete commit boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ComposerExpansionDestructiveLifecycleTest {
    @Test
    fun successfulChatListLeaveClearsOnlyTheRemovedConversationGeometry() =
        runBlocking {
            val fixture = fixture()
            val retained = retainExpansion(fixture.appState, GROUP_ID)
            val other = retainExpansion(fixture.appState, OTHER_GROUP)
            val controller = fixture.seededChatsController()
            try {
                assertEquals(
                    retained,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
                )
                assertTrue(controller.leaveGroup(GROUP_ID))

                assertEquals(1, fixture.calls.leave.get())
                assertNull(fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
                assertEquals(
                    other,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, OTHER_GROUP),
                )
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun failedChatListLeaveRetainsTheConversationGeometry() =
        runBlocking {
            val fixture = fixture(failLeave = true)
            val retained = retainExpansion(fixture.appState, GROUP_ID)
            val controller = fixture.seededChatsController()
            try {
                assertFalse(controller.leaveGroup(GROUP_ID))

                assertEquals(1, fixture.calls.leave.get())
                assertEquals(
                    retained,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
                )
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun successfulLocalDeleteClearsOnlyTheRemovedConversationGeometry() =
        runBlocking {
            val fixture = fixture()
            val retained = retainExpansion(fixture.appState, GROUP_ID)
            val other = retainExpansion(fixture.appState, OTHER_GROUP)
            val controller = fixture.seededChatsController()
            try {
                assertEquals(
                    retained,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
                )
                assertTrue(controller.deleteGroupLocalFromChatList(GROUP_ID, notify = false))

                assertEquals(1, fixture.calls.delete.get())
                assertNull(fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
                assertEquals(
                    other,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, OTHER_GROUP),
                )
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun failedLocalDeleteRetainsTheConversationGeometry() =
        runBlocking {
            val fixture = fixture(failDelete = true)
            val retained = retainExpansion(fixture.appState, GROUP_ID)
            val controller = fixture.seededChatsController()
            try {
                assertFalse(controller.deleteGroupLocalFromChatList(GROUP_ID, notify = false))

                assertEquals(1, fixture.calls.delete.get())
                assertEquals(
                    retained,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
                )
            } finally {
                controller.onCleared()
            }
        }

    /** Creates one isolated app/runtime pair with controllable native leave and delete commits. */
    private fun fixture(
        failLeave: Boolean = false,
        failDelete: Boolean = false,
    ): LifecycleFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(LifecycleDraftPersistence),
                accountIdHexResolver = { ACCOUNT_ID },
                accounts = listOf(account()),
                activeAccountRef = ACCOUNT_REF,
                messageDraftRepository = draftRepository(),
            )
        val calls = LifecycleCalls()
        val marmot = lifecycleMarmot(failLeave, failDelete, calls)
        WhiteNoiseAppState::class.java
            .getDeclaredField("marmotRuntime")
            .apply { isAccessible = true }
            .set(appState, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        return LifecycleFixture(appState, calls)
    }

    /** Retains one manual expansion and returns the exact value expected after a failed commit. */
    private fun retainExpansion(
        appState: WhiteNoiseAppState,
        groupIdHex: String = GROUP_ID,
    ): RetainedComposerExpansion =
        RetainedComposerExpansion(RetainedComposerExpansionMode.Manual, 240f).also { preference ->
            appState.composerExpansionStateRetention.update(
                accountRef = ACCOUNT_REF,
                groupIdHex = groupIdHex,
                preference = preference,
                draftGeneration = 1L,
            )
        }

    /** Implements only the native lifecycle calls exercised by these production controller paths. */
    @Suppress("UNCHECKED_CAST")
    private fun lifecycleMarmot(
        failLeave: Boolean,
        failDelete: Boolean,
        calls: LifecycleCalls,
    ): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            /** Completes one reflected suspend call with the requested native failure. */
            fun suspendFailure(failure: Throwable): Any {
                (arguments!!.last() as Continuation<Any?>).resumeWithException(failure)
                return COROUTINE_SUSPENDED
            }

            when (method.name.substringBefore('-')) {
                "groupMembers" -> members()
                "listMedia" -> emptyList<Any>()
                "leaveGroup" -> {
                    calls.leave.incrementAndGet()
                    if (failLeave) {
                        suspendFailure(IllegalStateException("leave rejected"))
                    } else {
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf("leave-commit"),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    }
                }
                "deleteGroupLocal" -> {
                    calls.delete.incrementAndGet()
                    if (failDelete) {
                        suspendFailure(IllegalStateException("delete rejected"))
                    } else {
                        true
                    }
                }
                "toString" -> "ComposerExpansionLifecycleMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else ->
                    if (arguments?.lastOrNull() is Continuation<*>) {
                        suspendFailure(UnsupportedOperationException("Unexpected Marmot call: ${method.name}"))
                    } else {
                        throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
                    }
            }
        } as MarmotInterface

    /** Creates a signed-in local account matching the self member returned by the native fixture. */
    private fun account() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_ID,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** Supplies a non-sole-member roster so leave uses the real remote commit path. */
    private fun members() =
        listOf(
            AppGroupMemberRecordFfi(memberIdHex = ACCOUNT_ID, account = ACCOUNT_REF, local = true),
            AppGroupMemberRecordFfi(memberIdHex = PEER_ID, account = null, local = false),
        )

    /** Wraps an injected repository whose successful draft delete cannot invoke Marmot. */
    private fun draftRepository() =
        MessageDraftRepository(
            gateway = EmptyDraftGateway,
            editorSessions = EditorSessionStore(LifecycleEditorStrings),
            ioDispatcher = Dispatchers.Unconfined,
        )

    /** Holds the production state and seeds one real chat-list controller route. */
    private class LifecycleFixture(
        val appState: WhiteNoiseAppState,
        val calls: LifecycleCalls,
    ) {
        /** Seeds the actual chat-list projection required by its leave and delete actions. */
        fun seededChatsController(): ChatsController =
            ChatsController(
                appState = appState,
                initialAccountRef = ACCOUNT_REF,
                memberSnapshotLoader = { _, _ -> emptyList() },
            ).also { controller ->
                controller.setChatListVisible(false)
                controller.applyChatListRow(groupRow())
                controller.applyLocalGroupUpdate(group())
                controller.setChatListVisible(true)
            }
    }

    /** Counts authoritative native mutations so false results cannot pass via an earlier guard. */
    private class LifecycleCalls {
        val leave = AtomicInteger()
        val delete = AtomicInteger()
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        val ACCOUNT_ID = "a1".repeat(32)
        val PEER_ID = "b2".repeat(32)
        const val GROUP_ID = "group-a"
        const val OTHER_GROUP = "group-b"

        /** Builds the one stable group used by both leave and delete controller routes. */
        fun group() =
            AppGroupRecordFfi(
                selfMembership = SelfMembershipFfi.MEMBER,
                groupIdHex = GROUP_ID,
                protocolProfile = AppProtocolProfileFfi.LEGACY,
                profilePresent = false,
                endpoint = "endpoint",
                name = "Group",
                description = "",
                admins = listOf(PEER_ID),
                relays = emptyList(),
                nostrGroupIdHex = "nostr-group",
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

        /** Mirrors the stable row consumed by the production chat-list mutation methods. */
        fun groupRow() =
            ChatListRowFfi(
                selfMembership = SelfMembershipFfi.MEMBER,
                unreadMentionCount = 0uL,
                unreadMention = false,
                groupIdHex = GROUP_ID,
                archived = false,
                pendingConfirmation = false,
                title = "Group",
                groupName = "Group",
                avatarUrl = null,
                avatar = null,
                lastMessage = null,
                unreadCount = 0uL,
                hasUnread = false,
                firstUnreadMessageIdHex = null,
                lastReadMessageIdHex = null,
                lastReadTimelineAt = null,
                conversationCreatedAt = 1uL,
                activitySortAt = 1uL,
                updatedAt = 1uL,
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

        /** Supplies the encrypted-media component required by current group records. */
        fun encryptedMedia() =
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
}

/** Empty draft gateway keeps lifecycle tests focused on group commit ordering. */
private object EmptyDraftGateway : MessageDraftGateway {
    /** Reports no MDK draft before group removal. */
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = null

    /** Echoes a requested draft for interface completeness; lifecycle tests never call this path. */
    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ) = MessageDraftFfi(
        groupIdHex = groupIdHex,
        content = content,
        replyToMessageIdHex = replyToMessageIdHex,
        mediaAttachments = mediaAttachments,
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

    /** Accepts the production pre-removal draft cleanup. */
    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) = Unit

    /** Reports no draft summaries outside the removed conversation. */
    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}

/** Empty in-memory persistence avoids test coupling to encrypted editor storage. */
private object LifecycleEditorStrings : EditorStringStore {
    /** Starts without any retained editor sessions. */
    override fun readAll(): Map<String, String> = emptyMap()

    /** Accepts the empty replacement set used during lifecycle cleanup. */
    override fun replaceAll(values: Map<String, String>): Boolean = true

    /** Clears the already-empty fixture store. */
    override fun clear() = Unit
}

/** Empty legacy persistence keeps this lifecycle suite independent of disk state. */
private object LifecycleDraftPersistence : DraftPersistence {
    /** Starts without legacy lifecycle drafts. */
    override fun read(): Map<String, String> = emptyMap()

    /** Ignores legacy writes while the in-memory draft store remains observable. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
