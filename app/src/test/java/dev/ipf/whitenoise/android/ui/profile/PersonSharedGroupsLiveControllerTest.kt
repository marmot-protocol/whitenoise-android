package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real hidden-list controller projection; no mocked list substitutes for native roster state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonSharedGroupsLiveControllerTest {
    @get:Rule val composeRule = createComposeRule()
    private val self = "11".repeat(32)
    private val target = "aa".repeat(32)
    private val third = "bb".repeat(32)

    /** A profile above a conversation sees native roster hydration and removal while visible items stay frozen. */
    @Test fun hiddenChatListHydrationAndRemovalChangeTheLiveProfileGroups() =
        withController { app, controller ->
            val known = personTestGroup("group", "Group", members = listOf(self, target, third))
            controller.applyChatListRow(row("group"))
            controller.applyLocalGroupUpdate(known.group)
            val frozen = controller.items
            assertEquals(setOf("group"), app.personSharedGroupsForProfile(target).unresolvedGroupIds)

            controller.applyLocalGroupDetails(known.group, checkNotNull(known.memberSnapshot).members)
            assertEquals(frozen, controller.items)
            assertEquals(listOf("group"), app.personSharedGroupsForProfile(target).groups.map { it.group.groupIdHex })
            assertTrue(app.personSharedGroupsForProfile(target).unresolvedGroupIds.isEmpty())
            assertTrue(app.profileGroupPickerRevision > 0L)

            val withoutTarget = personTestGroup("group", "Group", members = listOf(self, third))
            controller.applyLocalGroupDetails(withoutTarget.group, checkNotNull(withoutTarget.memberSnapshot).members)
            assertEquals(frozen, controller.items)
            assertTrue(app.personSharedGroupsForProfile(target).groups.isEmpty())
        }

    /** Archived shared groups are part of the live common-group projection, while DMs stay excluded. */
    @Test fun archivedSharedGroupIsIncludedWithoutInventingADirectConversationGroup() =
        withController { app, controller ->
            val archived = personTestGroup("archived", "Archived friends", members = listOf(self, target, third))
            controller.applyChatListRow(row("archived").copy(archived = true))
            controller.applyLocalGroupDetails(
                archived.group.copy(archived = true),
                checkNotNull(archived.memberSnapshot).members,
            )
            val direct = personTestGroup("direct", "", members = listOf(self, target))
            controller.applyChatListRow(
                row("direct").copy(
                    groupName = "",
                    title = "",
                    conversationKind = ChatConversationKindFfi.DIRECT,
                ),
            )
            controller.applyLocalGroupDetails(direct.group, checkNotNull(direct.memberSnapshot).members)
            assertFalse(controller.items.any { it.group.groupIdHex == "archived" })
            assertEquals(
                listOf("archived"),
                app.personSharedGroupsForProfile(target).groups.map { it.group.groupIdHex },
            )
        }

    /** Uses the same actual account-bound controller and public local-detail application as production consumers. */
    private fun withController(check: (WhiteNoiseAppState, ChatsController) -> Unit) {
        val app =
            WhiteNoiseAppState(
                ApplicationProvider.getApplicationContext<Context>(),
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
                { null },
                listOf(AccountSummaryFfi("alice", self, true, false, false, true)),
                "alice",
            )
        val controller = ChatsController(app, "alice") { _, _ -> emptyList() }
        composeRule.setContent {}
        try {
            composeRule.runOnIdle {
                app.attachChatsController(controller)
                controller.setChatListVisible(false)
                check(app, controller)
            }
        } finally {
            composeRule.runOnIdle {
                app.attachChatsController(null)
                controller.onCleared()
            }
        }
    }

    /** Native projected-row fixture with a confirmed group and no fabricated roster. */
    private fun row(id: String) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = id,
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
            conversationKind = ChatConversationKindFfi.UNKNOWN,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )
}
