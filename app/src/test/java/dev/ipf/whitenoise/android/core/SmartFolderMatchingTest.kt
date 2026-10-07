package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListDraftPreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.state.allLoadedFolderChats
import dev.ipf.whitenoise.android.state.chatFolderSource
import dev.ipf.whitenoise.android.ui.settings.smartFolderUnresolvedCount
import org.junit.Assert.assertEquals
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class SmartFolderMatchingTest {
    private val agent = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun condition(
        field: FolderField,
        mode: FolderMode = FolderMode.PRESENT,
        values: Set<String> = emptySet(),
        not: Boolean = false,
    ) = SmartFolderFilter.Condition(field, mode, values, not)

    private fun matches(
        filter: SmartFolderFilter,
        row: ChatListItem,
    ) = smartFolderMatches(filter, row) { "Release Team" }

    @Test fun emptyAndIncompleteRulesDoNotReportMissingData() {
        val source = listOf(item("g"))
        assertEquals(0, smartFolderUnresolvedCount(null, source) { "Team" })
        assertEquals(0, smartFolderUnresolvedCount(SmartFolderFilter.Group(), source) { "Team" })
        val unfinished = SmartFolderFilter.Group(children = listOf(SmartFolderFilter.Group()))
        assertEquals(0, smartFolderUnresolvedCount(unfinished, source) { "Team" })
        val unknown =
            SmartFolderFilter.Group(
                children =
                    listOf(
                        condition(
                            FolderField.PARTICIPANTS,
                            values = setOf(agent),
                            mode = FolderMode.ANY_OF,
                        ),
                    ),
            )
        assertEquals(1, smartFolderUnresolvedCount(unknown, source) { "Team" })
        val known = SmartFolderFilter.Group(children = listOf(condition(FolderField.UNREAD)))
        assertEquals(0, smartFolderUnresolvedCount(known, source) { "Team" })
    }

    @Test fun nestedAllAnyAndNotHaveDifferentTruthTables() {
        val allRead = condition(FolderField.UNREAD, FolderMode.NONE)
        val mentions = condition(FolderField.MENTIONS)
        val read = item("read")
        val unread = item("unread", unread = true)
        val root =
            SmartFolderFilter.Group(
                children =
                    listOf(
                        allRead,
                        SmartFolderFilter.Group(
                            all = false,
                            children =
                                listOf(
                                    mentions,
                                    condition(
                                        FolderField.TITLE,
                                        FolderMode.CONTAINS,
                                        setOf("team"),
                                    ),
                                ),
                        ),
                    ),
            )
        assertEquals(FolderTruth.TRUE, matches(root, read))
        assertEquals(FolderTruth.FALSE, matches(root, unread))
        assertEquals(FolderTruth.TRUE, matches(root.copy(not = true), unread))
        assertEquals(FolderTruth.UNKNOWN, matches(root.copy(not = true), read.copy(projection = null)))
    }

    @Test fun noUnreadRejectsManualUnreadAndIndependentMentionsRemainIndependent() {
        val row = item("g").let { it.copy(projection = it.projection!!.copy(manuallyMarkedUnread = true)) }
        assertEquals(FolderTruth.FALSE, matches(condition(FolderField.UNREAD, FolderMode.NONE), row))
        assertEquals(FolderTruth.TRUE, matches(condition(FolderField.MENTIONS, FolderMode.NONE), row))
        assertEquals(
            FolderTruth.TRUE,
            matches(
                condition(FolderField.MENTIONS),
                item(
                    "g",
                    mention = true,
                    unread = false,
                ),
            ),
        )
    }

    @Test fun participantsAnyAllExcludesAndUnknownAreAuthoritative() {
        val present = item("g", members = listOf(agent))
        val absent = item("g", members = listOf(other))
        val pending = item("g", presentationOtherMember = agent)
        val any = condition(FolderField.PARTICIPANTS, FolderMode.ANY_OF, setOf(agent, other))
        val all = condition(FolderField.PARTICIPANTS, FolderMode.ALL_OF, setOf(agent, other))
        val excludes = condition(FolderField.PARTICIPANTS, FolderMode.EXCLUDES, setOf(agent))
        assertEquals(FolderTruth.TRUE, matches(any, present))
        assertEquals(FolderTruth.FALSE, matches(all, present))
        assertEquals(FolderTruth.TRUE, matches(excludes, absent))
        assertEquals(FolderTruth.UNKNOWN, matches(excludes, pending))
        assertEquals(FolderTruth.UNKNOWN, matches(excludes.copy(not = true), pending))
        assertEquals(FolderTruth.UNKNOWN, matches(excludes, item("g", members = emptyList())))
        assertEquals(FolderTruth.TRUE, matches(any, item("g", otherMember = agent)))
    }

    @Test fun selfOnlyDirectRosterCannotProveAnUnresolvedPeerIsAbsent() {
        val row = item("dm", members = listOf(other), dm = true, otherMember = null)
        val any = condition(FolderField.PARTICIPANTS, FolderMode.ANY_OF, setOf(agent))
        val all = condition(FolderField.PARTICIPANTS, FolderMode.ALL_OF, setOf(agent, other))
        val excludes = condition(FolderField.PARTICIPANTS, FolderMode.EXCLUDES, setOf(agent))
        assertEquals(FolderTruth.UNKNOWN, matches(any, row))
        assertEquals(FolderTruth.UNKNOWN, matches(all, row))
        assertEquals(FolderTruth.UNKNOWN, matches(excludes, row))
        assertEquals(FolderTruth.UNKNOWN, matches(excludes.copy(not = true), row))
        assertEquals(FolderTruth.TRUE, matches(all, row.copy(otherMemberAccount = agent)))
        assertEquals(FolderTruth.TRUE, matches(excludes, row.copy(otherMemberAccount = other)))
    }

    @Test fun manualPickerUnionRetainsActiveRowsEvenForArchivedRules() {
        val active = item("ACTIVE")
        val archived = item("archived", archived = true)
        val rule = ChatFolderRule(archivedOnly = true)
        assertEquals(listOf(archived), chatFolderSource(rule, listOf(active), listOf(archived)))
        assertEquals(
            listOf("ACTIVE", "archived"),
            allLoadedFolderChats(listOf(active), listOf(active, archived)).map { it.id },
        )
    }

    @Test fun unknownPropagatesUnlessAnotherBranchDeterminesTheResult() {
        val unknown = condition(FolderField.PENDING_SEND)
        val yes = condition(FolderField.TITLE, FolderMode.CONTAINS, setOf("team"))
        val no = condition(FolderField.UNREAD)
        assertEquals(
            FolderTruth.TRUE,
            matches(
                SmartFolderFilter.Group(
                    all = false,
                    children =
                        listOf(
                            yes,
                            unknown,
                        ),
                ),
                item("g"),
            ),
        )
        assertEquals(
            FolderTruth.FALSE,
            matches(
                SmartFolderFilter.Group(
                    children =
                        listOf(
                            no,
                            unknown,
                        ),
                ),
                item("g"),
            ),
        )
        assertEquals(
            FolderTruth.UNKNOWN,
            matches(
                SmartFolderFilter.Group(
                    children =
                        listOf(
                            yes,
                            unknown,
                        ),
                ),
                item("g"),
            ),
        )
        assertEquals(FolderTruth.UNKNOWN, matches(SmartFolderFilter.Group(not = true), item("g")))
    }

    @Test fun activeAcceptedExcludesUnacceptedLeavingAndTerminalChats() {
        val row = item("g")
        val accepted = condition(FolderField.ACCEPTED)
        assertEquals(FolderTruth.TRUE, matches(accepted, row))
        listOf(
            row.projection!!.copy(pendingConfirmation = true),
            row.projection!!.copy(leaveRequestPending = true),
            row.projection!!.copy(selfMembership = SelfMembershipFfi.LEFT),
            row.projection!!.copy(lifecycleState = GroupLifecycleStateFfi.DISBANDED),
        ).forEach {
            assertEquals(FolderTruth.FALSE, matches(accepted, row.copy(projection = it)))
        }
        assertEquals(FolderTruth.UNKNOWN, matches(accepted, row.copy(inviteConfirmationUnresolved = true)))
    }

    @Test fun draftAttachmentAndMissingPreviewAreDistinguished() {
        val row = item("g")
        val draft =
            row.copy(
                selectedPreview =
                    SelectedChatPreviewFfi.Draft(
                        ChatListDraftPreviewFfi(
                            "",
                            false,
                            1uL,
                            null,
                        ),
                    ),
            )
        assertEquals(FolderTruth.TRUE, matches(condition(FolderField.DRAFT), draft))
        assertEquals(FolderTruth.FALSE, matches(condition(FolderField.DRAFT, FolderMode.NONE), draft))
        assertEquals(FolderTruth.UNKNOWN, matches(condition(FolderField.DRAFT, FolderMode.NONE), row))
    }

    @Test fun invalidTreeCannotBeRescuedByAnyOrNotAndManualIncludesRemain() {
        val good = condition(FolderField.TITLE, FolderMode.CONTAINS, setOf("team"))
        val bad = condition(FolderField.PARTICIPANTS, FolderMode.EXCLUDES, setOf("bad"))
        val rule =
            ChatFolderRule(
                smartFilter =
                    SmartFolderCodec.encode(
                        SmartFolderFilter.Group(
                            all = false,
                            children =
                                listOf(
                                    good,
                                    bad,
                                ),
                            not = true,
                        ),
                    ),
            )
        assertEquals(
            setOf("manual"),
            chatFolderChatIds(
                listOf(item("g")),
                setOf("manual"),
                rule,
                null,
                {
                    false
                },
                {
                    "Team"
                },
            ),
        )
        assertEquals(
            setOf("manual"),
            chatFolderChatIds(
                listOf(item("g")),
                setOf("manual"),
                rule.copy(smartFilter = "{broken"),
                null,
                {
                    false
                },
                {
                    "Team"
                },
            ),
        )
    }

    @Test fun exactExclusionsOverrideValidAutomaticAndManualMatches() {
        val title = condition(FolderField.TITLE, FolderMode.CONTAINS, setOf("team"))
        val root = SmartFolderFilter.Group(children = listOf(title))
        val rows = listOf(item("automatic"), item("included"))
        val rule = ChatFolderRule(smartFilter = SmartFolderCodec.encode(root))
        assertEquals(
            setOf("included"),
            chatFolderChatIds(rows, setOf("manual"), rule, null, { false }, { "Team" }, setOf("automatic", "manual")),
        )
    }

    @Test fun sourceUnionAndManualMembershipUseTheSameCanonicalIdentity() {
        val active = item("G", archived = false)
        val archived = item("other", archived = true)
        val root = SmartFolderFilter.Group(children = listOf(condition(FolderField.ARCHIVED)))
        val rule = ChatFolderRule(smartFilter = SmartFolderCodec.encode(root))
        val source = chatFolderSource(rule, listOf(active), listOf(active, archived))
        assertEquals(listOf("G", "other"), source.map { it.id })
        assertEquals(
            setOf(
                "g",
                "other",
            ),
            chatFolderChatIds(
                source,
                setOf("g"),
                rule,
                null,
                {
                    false
                },
                {
                    it.id
                },
            ),
        )
        assertEquals(listOf(active), chatFolderSource(null, listOf(active), listOf(archived)))
    }

    @Test
    fun agentAllReadTracksManualReminderMuteAndParticipantChanges() {
        val root =
            SmartFolderFilter.Group(
                children =
                    listOf(
                        condition(FolderField.PARTICIPANTS, FolderMode.ANY_OF, setOf(agent)),
                        condition(FolderField.UNREAD, FolderMode.NONE),
                        condition(FolderField.ARCHIVED, FolderMode.NONE),
                        condition(FolderField.ACCEPTED),
                    ),
            )
        val initial = item("g", members = listOf(agent))
        val muted = initial.copy(projection = initial.projection!!.copy(muted = true))
        assertEquals(FolderTruth.TRUE, matches(root, muted))
        assertEquals(
            FolderTruth.FALSE,
            matches(
                root,
                muted.copy(projection = muted.projection!!.copy(manuallyMarkedUnread = true)),
            ),
        )
        assertEquals(FolderTruth.FALSE, matches(root, item("g", members = listOf(other))))
        assertEquals(FolderTruth.FALSE, matches(root, initial.copy(removed = true)))
        assertEquals(FolderTruth.FALSE, matches(root, item("g", members = listOf(agent), archived = true)))
        assertEquals(FolderTruth.TRUE, matches(root, initial))
    }

    @Test
    fun nativeTypePinAndDraftEvidenceDoNotUsePresentationFallbacks() {
        val row = item("g", dm = true, pinned = true).copy(selectedPreview = SelectedChatPreviewFfi.Empty)
        assertEquals(FolderTruth.TRUE, matches(condition(FolderField.TYPE, FolderMode.DIRECT), row))
        assertEquals(FolderTruth.FALSE, matches(condition(FolderField.TYPE, FolderMode.GROUP), row))
        assertEquals(FolderTruth.TRUE, matches(condition(FolderField.PINNED), row))
        assertEquals(FolderTruth.TRUE, matches(condition(FolderField.DRAFT, FolderMode.NONE), row))
        val unknown = row.copy(projection = row.projection!!.copy(conversationKind = ChatConversationKindFfi.UNKNOWN))
        assertEquals(
            FolderTruth.UNKNOWN,
            matches(
                condition(
                    FolderField.TYPE,
                    FolderMode.DIRECT,
                    not = true,
                ),
                unknown,
            ),
        )
        assertEquals(
            FolderTruth.UNKNOWN,
            matches(
                condition(
                    FolderField.PINNED,
                    FolderMode.NONE,
                ),
                row.copy(projection = null),
            ),
        )
    }

    @Test
    fun latestPendingProvesPresenceButDeliveredOrMissingLatestDoesNotProveAbsence() {
        val row = item("g")
        val preview =
            dev.ipf.marmotkit.ChatListMessagePreviewFfi(
                groupSystem = null,
                messageIdHex = "message",
                sender = "sender",
                senderDisplayName = null,
                plaintext = "",
                contentTokens = EMPTY_MARKDOWN_DOCUMENT,
                kind = 1uL,
                timelineAt = 1uL,
                retentionSeconds = null,
                retentionExpiresAt = null,
                deleted = false,
                deletionSource = dev.ipf.marmotkit.DeletionSourceFfi.UNKNOWN,
                attachmentKind = null,
                attachmentCount = 0u,
                deliveryState = dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi.PENDING,
            )
        val pending = row.copy(projection = row.projection!!.copy(lastMessage = preview))
        assertEquals(FolderTruth.TRUE, matches(condition(FolderField.PENDING_SEND), pending))
        assertEquals(
            FolderTruth.FALSE,
            matches(
                condition(
                    FolderField.PENDING_SEND,
                    FolderMode.NONE,
                ),
                pending,
            ),
        )
        val delivered =
            pending.copy(
                projection =
                    pending.projection!!.copy(
                        lastMessage =
                            preview.copy(
                                deliveryState = dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi.DELIVERED,
                            ),
                    ),
            )
        assertEquals(
            FolderTruth.UNKNOWN,
            matches(
                condition(
                    FolderField.PENDING_SEND,
                    FolderMode.NONE,
                ),
                delivered,
            ),
        )
        assertEquals(FolderTruth.UNKNOWN, matches(condition(FolderField.PENDING_SEND, FolderMode.NONE), row))
    }

    private fun item(
        groupIdHex: String,
        members: List<String>? = null,
        otherMember: String? = null,
        presentationOtherMember: String? = otherMember,
        unread: Boolean = false,
        description: String = "",
        dm: Boolean = false,
        archived: Boolean = false,
        mention: Boolean = false,
        pinned: Boolean = false,
    ): ChatListItem =
        ChatListItem(
            group = group(groupIdHex, description, archived),
            latest = null,
            otherMemberAccount = otherMember,
            memberCount = members?.size ?: 0,
            memberSnapshot =
                members?.let { roster ->
                    GroupMemberSnapshot(
                        roster.map {
                            AppGroupMemberRecordFfi(
                                memberIdHex = it,
                                account = null,
                                local = false,
                            )
                        },
                    )
                },
            presentationOtherMemberAccount = presentationOtherMember,
            projection =
                row(groupIdHex, unread, dm, archived).copy(
                    unreadMention = mention,
                    unreadMentionCount = if (mention) 1uL else 0uL,
                    pinned = pinned,
                ),
        )

    private fun row(
        groupIdHex: String,
        unread: Boolean,
        dm: Boolean = false,
        rowArchived: Boolean = false,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupIdHex,
        archived = rowArchived,
        pendingConfirmation = false,
        title = "Group $groupIdHex",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = if (unread) 1uL else 0uL,
        hasUnread = unread,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 0uL,
        activitySortAt = 0uL,
        updatedAt = 1uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = if (dm) ChatConversationKindFfi.DIRECT else ChatConversationKindFfi.GROUP,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

    private fun group(
        id: String,
        description: String,
        archived: Boolean = false,
    ) = AppGroupRecordFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = id,
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint-$id",
        name = "",
        description = description,
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$id",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia = encryptedMedia(),
        archived = archived,
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
                    AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net"),
                ),
        )
}
