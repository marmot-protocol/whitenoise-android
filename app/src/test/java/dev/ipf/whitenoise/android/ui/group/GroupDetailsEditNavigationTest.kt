package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationSurfaceState
import dev.ipf.whitenoise.android.ui.profile.PROFILE_ADD_TO_GROUPS_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupDetailsEditNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun nameTapOpensEditorAndBackReturnsToDetails() {
        render(controller(group()))

        composeRule.onNode(hasText(GROUP_NAME) and hasClickAction()).performClick()
        assertEditorIsOpen()

        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNode(hasText(GROUP_NAME) and hasClickAction()).assertIsDisplayed()
    }

    /** Unavailable call actions do not occupy the primary action row. */
    @Test
    fun unavailableCallActionsDoNotOccupyThePrimaryActionRow() {
        render(controller(group()), onOpenSearch = {})

        composeRule.onNodeWithText(context.getString(R.string.quick_action_audio)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.quick_action_video)).assertDoesNotExist()
        val mute = composeRule.onNodeWithContentDescription(context.getString(R.string.mute))
        val timer = composeRule.onNodeWithContentDescription(context.getString(R.string.disappearing))
        val search = composeRule.onNodeWithContentDescription(context.getString(R.string.quick_action_search))
        listOf(mute, timer, search).forEach { it.assertIsDisplayed().assertHeightIsAtLeast(56.dp) }
        val bounds = listOf(mute, timer, search).map { it.fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].width, bounds[1].width, 1f)
        assertEquals(bounds[1].width, bounds[2].width, 1f)
        assertTrue(bounds[0].right <= bounds[1].left && bounds[1].right <= bounds[2].left)
        composeRule.onNodeWithTag("chat_info.add_people").performScrollTo().assertIsDisplayed()
    }

    /** The real details and add-members destinations survive Activity saved-state restoration together. */
    @Test
    fun addMembersRouteRestoresThroughActualDetailsScreen() {
        val testGroup = group()
        val members = listOf(member(SELF_HEX, local = true)) + (1 until 50).map { member("member-$it") }
        val fixture =
            controller(
                group = testGroup,
                verifiedRoster = true,
                membersOverride = members,
            )
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            val surfaceState =
                rememberConversationSurfaceState(
                    controllerIdentity = fixture.controller,
                    accountRef = ACCOUNT_REF,
                    chatId = testGroup.groupIdHex,
                    runtimeGeneration = fixture.appState.runtimeGeneration,
                )
            WhiteNoiseTheme {
                if (surfaceState.showDetails.value) {
                    GroupDetailsScreen(
                        appState = fixture.appState,
                        controller = fixture.controller,
                        onBack = { surfaceState.showDetails.value = false },
                        onLeft = {},
                    )
                } else {
                    Button(onClick = { surfaceState.showDetails.value = true }) {
                        Text("Open details")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Open details").performClick()
        composeRule.onNodeWithTag("chat_info.add_people").performScrollTo().performClick()
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("chat_info.add_people").assertDoesNotExist()
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.add_member)).assertIsDisplayed()
    }

    /** Overview search keeps the conversation callback and the native mute picker is directly reachable. */
    @Test
    fun overviewActionsReachNativeSearchAndMutePicker() {
        var searchRequests = 0
        render(controller(group()), onOpenSearch = { searchRequests++ })
        composeRule.onNodeWithContentDescription(context.getString(R.string.quick_action_search)).performClick()
        assertEquals(1, searchRequests)
        composeRule.onNodeWithContentDescription(context.getString(R.string.mute)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.mute_for)).assertIsDisplayed()
    }

    /** Empty group details prioritize people and administration while developer tools remain last. */
    @Test
    fun overviewSectionsFollowThePrimaryActionOrder() {
        render(controller(group(), verifiedRoster = true))

        composeRule.onRoot().captureRoboImage("src/test/snapshots/group_details_primary_order_light.png")
        composeRule.onNodeWithTag("chat_info.shared_media").assertDoesNotExist()

        val sectionTops =
            listOf(
                "chat_info.members",
                "chat_info.management",
                "chat_info.actions",
                "chat_info.technical",
                "chat_info.lifecycle",
                "chat_info.developer",
            ).map(::contentTop)
        assertTrue(
            "Expected group-detail sections in primary-action order, got $sectionTops",
            sectionTops.zipWithNext().all { (first, second) -> first < second },
        )
    }

    /** Non-admin groups keep the same priority order while omitting member-management actions. */
    @Test
    fun nonAdminOverviewOmitsManagementWithoutReorderingSections() {
        render(controller(group(admin = false), verifiedRoster = true))

        composeRule.onNodeWithTag("chat_info.management").assertDoesNotExist()
        composeRule.onNodeWithTag("chat_info.shared_media").assertDoesNotExist()
        val sectionTops =
            listOf(
                "chat_info.members",
                "chat_info.actions",
                "chat_info.technical",
                "chat_info.lifecycle",
                "chat_info.developer",
            ).map(::contentTop)
        assertTrue(sectionTops.zipWithNext().all { (first, second) -> first < second })
    }

    /** Direct-message details put the two peer group actions together before shared content. */
    @Test
    fun directMessageOverviewKeepsPeerGroupActionsTogether() {
        var startedPeerId: String? = null
        render(
            controller(
                group(name = ""),
                verifiedRoster = true,
                membersOverride = listOf(member(SELF_HEX, local = true), member("member-b")),
            ),
            onStartGroupWithPeer = { startedPeerId = it.accountIdHex },
        )

        composeRule.onNodeWithTag("chat_info.members").assertDoesNotExist()
        composeRule.onNodeWithTag("chat_info.management").assertDoesNotExist()
        composeRule.onNodeWithTag("chat_info.developer").assertDoesNotExist()
        composeRule.onNodeWithTag("chat_info.start_group").assertIsDisplayed().performClick()
        assertEquals("member-b", startedPeerId)
        composeRule.onNodeWithTag("chat_info.add_to_group").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNodeWithTag("chat_info.shared_media").assertDoesNotExist()
        val sectionTops =
            listOf(
                "chat_info.group_actions",
                "chat_info.actions",
                "chat_info.technical",
            ).map(::contentTop)
        assertTrue(sectionTops.zipWithNext().all { (first, second) -> first < second })
    }

    /** A native technical-info round trip retains the actual overview viewport and scrolled identity. */
    @Test
    fun detailsRoundTripRetainsScrolledIdentityAndViewport() {
        render(controller(group()))
        composeRule.onNodeWithTag("chat_info.header_identity").assertDoesNotExist()
        composeRule.onNodeWithTag("chat_info.list").performTouchInput { swipeUp(durationMillis = 1_000) }
        composeRule.onNodeWithTag("chat_info.header_name").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_info.developer_tools").performScrollTo()
        val offset = overviewScrollOffset()
        assertTrue(offset > 0f)
        composeRule.onNodeWithTag("chat_info.developer_tools").performClick()
        composeRule.onNodeWithText(context.getString(R.string.mls_group_id)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNodeWithTag("chat_info.header_name").assertIsDisplayed()
        assertEquals(offset, overviewScrollOffset(), 1f)
        composeRule.onNodeWithTag("chat_info.name").performScrollTo()
        composeRule.onNodeWithTag("chat_info.header_identity").assertDoesNotExist()
    }

    /** A new controller cannot inherit the preceding group's overview viewport. */
    @Test
    fun changingGroupsStartsTheNewOverviewAtItsIdentity() {
        val current = mutableStateOf(controller(group()))
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsScreen(
                    appState = current.value.appState,
                    controller = current.value.controller,
                    onBack = {},
                    onLeft = {},
                )
            }
        }
        composeRule.onNodeWithTag("chat_info.list").performTouchInput { swipeUp(durationMillis = 1_000) }
        assertTrue(overviewScrollOffset() > 0f)
        composeRule.onNodeWithTag("chat_info.header_name").assertIsDisplayed()
        composeRule.runOnIdle {
            current.value = controller(group(groupId = "group-b", name = "Second group"))
        }
        assertEquals(0f, overviewScrollOffset(), 0f)
        composeRule.onNodeWithTag("chat_info.header_identity").assertDoesNotExist()
        composeRule.onNode(hasText("Second group") and hasClickAction()).assertIsDisplayed()
    }

    /** A mute picker opened for one controller must close before another group's overview is shown. */
    @Test
    fun changingGroupsClosesThePreviousMutePicker() {
        val current = mutableStateOf(controller(group()))
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsScreen(
                    appState = current.value.appState,
                    controller = current.value.controller,
                    onBack = {},
                    onLeft = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.mute)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.mute_for)).assertIsDisplayed()
        composeRule.runOnIdle {
            current.value = controller(group(groupId = "group-b", name = "Second group"))
        }
        composeRule.onNodeWithText(context.getString(R.string.mute_for)).assertDoesNotExist()
        composeRule.onNode(hasText("Second group") and hasClickAction()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.mute)).assertIsDisplayed()
    }

    /** See all opens the full native roster and Back returns to the same overview position. */
    @Test
    fun membersDestinationRetainsOverviewPositionAndShowsTheFullRoster() {
        render(controller(group(), extraMembers = 5, verifiedRoster = true))
        composeRule.onNodeWithTag("chat_info.member.member-h").assertDoesNotExist()
        composeRule.onNodeWithTag("chat_info.all_members").performScrollTo()
        val offset = overviewScrollOffset()
        composeRule.onNodeWithTag("chat_info.all_members").performClick()
        composeRule.onNodeWithTag("chat_info.members_screen").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/group_members_route_light.png")
        composeRule.onNodeWithTag("chat_info.member.member-h").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNodeWithTag("chat_info.members_screen").assertDoesNotExist()
        assertEquals(offset, overviewScrollOffset(), 1f)
    }

    /**
     * The full roster is a reading surface: adding members stays on the group info screen, and a row whose
     * identity does not resolve to a profile neither opens one nor advertises that it would.
     */
    @Test
    fun fullMembersOffersNoAddActionAndLeavesUnresolvableRowsInert() {
        render(controller(group(), extraMembers = 5, verifiedRoster = true))

        composeRule.onNodeWithTag("chat_info.all_members").performScrollTo().performClick()
        composeRule.onNodeWithTag("chat_info.members_screen").assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.add_member)).assertDoesNotExist()
        val memberTag = "chat_info.member.member-h"
        composeRule.onNodeWithTag(memberTag).performScrollTo().assert(hasClickAction().not())
        composeRule
            .onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(memberTag)), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    /** Native mutation feedback remains visible and dismissible even deep in the full roster. */
    @Test
    fun fullMembersKeepsNativeMutationFailureVisible() {
        val fixture = controller(group(), extraMembers = 5, verifiedRoster = true)
        render(fixture)
        composeRule.onNodeWithTag("chat_info.all_members").performScrollTo().performClick()
        composeRule.onNodeWithTag("chat_info.member.member-h").performScrollTo()
        composeRule.runOnIdle { presentMutationFailure(fixture.controller) }
        composeRule
            .onNodeWithText(context.getString(R.string.latest_group_error, "Member update failed"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("chat_info.members_screen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dismiss)).performClick()
        composeRule.runOnIdle { assertEquals(null, fixture.controller.lastMutationError) }
        composeRule
            .onNodeWithText(context.getString(R.string.latest_group_error, "Member update failed"))
            .assertDoesNotExist()
        composeRule.onNodeWithTag("chat_info.members_screen").assertIsDisplayed()
    }

    /** Seeds the existing observable controller error, matching native administration fixture conventions. */
    @Suppress("UNCHECKED_CAST")
    private fun presentMutationFailure(controller: ConversationController) {
        val field = ConversationController::class.java.getDeclaredField("lastMutationError\$delegate")
        field.isAccessible = true
        (field.get(controller) as MutableState<ErrorPresentation?>).value =
            ErrorPresentation(AppText.Plain("Member update failed"), "operation=TEST")
    }

    /** Full-member route state cannot carry into another group's controller. */
    @Test
    fun switchingGroupsClosesThePreviousMembersDestination() {
        val current = mutableStateOf(controller(group(), extraMembers = 5, verifiedRoster = true))
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsScreen(
                    appState = current.value.appState,
                    controller = current.value.controller,
                    onBack = {},
                    onLeft = {},
                )
            }
        }
        composeRule.onNodeWithTag("chat_info.all_members").performScrollTo().performClick()
        composeRule.onNodeWithTag("chat_info.members_screen").assertIsDisplayed()
        composeRule.runOnIdle {
            current.value = controller(group(groupId = "group-b", name = "Second group"))
        }
        composeRule.onNodeWithTag("chat_info.members_screen").assertDoesNotExist()
        composeRule.onNode(hasText("Second group") and hasClickAction()).assertIsDisplayed()
        assertEquals(0f, overviewScrollOffset(), 0f)
    }

    /** The concrete relay destination reads the current native group and preserves overview restoration. */
    @Test
    fun relaysDestinationShowsNativeEmptyStateAndReturnsToItsOverview() {
        render(controller(group()))
        composeRule.onNodeWithTag("chat_info.relays").performScrollTo()
        val offset = overviewScrollOffset()
        composeRule.onNodeWithTag("chat_info.relays").performClick()
        composeRule.onNodeWithText(context.getString(R.string.no_relays)).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/chat_relays_route_empty_light.png")
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals(offset, overviewScrollOffset(), 1f)
        composeRule.onNodeWithTag("chat_info.relays").assertIsDisplayed()
    }

    /** Reads the native scrolling container's accessibility range without exposing a production test hook. */
    private fun overviewScrollOffset(): Float =
        composeRule
            .onNodeWithTag("chat_info.list")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    /** Returns a section's stable position in scroll-content coordinates. */
    private fun contentTop(tag: String): Float {
        val node = composeRule.onNodeWithTag(tag).performScrollTo()
        return overviewScrollOffset() + node.fetchSemanticsNode().boundsInRoot.top
    }

    /** Management edit row and add description open the same editor. */
    @Test
    fun managementEditRowAndAddDescriptionOpenTheSameEditor() {
        render(controller(group()))

        composeRule
            .onNodeWithText(context.getString(R.string.edit_group_info_title))
            .performScrollTo()
            .performClick()
        assertEditorIsOpen()

        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.add_group_description))
            .performScrollTo()
            .performClick()
        assertEditorIsOpen()
    }

    @Test
    fun nameIsReadOnlyWithoutCurrentAdminPermission() {
        val current = mutableStateOf(controller(group(admin = false)))
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsScreen(
                    appState = current.value.appState,
                    controller = current.value.controller,
                    onBack = {},
                    onLeft = {},
                )
            }
        }

        listOf(
            controller(group(groupId = "group-pending", pendingConfirmation = true)),
            controller(group(groupId = "group-terminal", disbanded = true)),
        ).forEach { next ->
            composeRule.onNodeWithText(GROUP_NAME).assert(hasClickAction().not())
            composeRule.runOnIdle { current.value = next }
        }
        composeRule.onNodeWithText(GROUP_NAME).assert(hasClickAction().not())
    }

    @Test
    fun groupSwitchClosesEditorAndDoesNotCarryItsDraft() {
        val current = mutableStateOf(controller(group()))
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsScreen(
                    appState = current.value.appState,
                    controller = current.value.controller,
                    onBack = {},
                    onLeft = {},
                )
            }
        }

        composeRule.onNode(hasText(GROUP_NAME) and hasClickAction()).performClick()
        assertEditorIsOpen()

        composeRule.runOnIdle {
            current.value = controller(group(groupId = "group-b", name = "Second group"))
        }

        composeRule.onNode(hasText("Second group") and hasClickAction()).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.group_name)).assertDoesNotExist()
    }

    private fun render(
        testController: TestController,
        onOpenSearch: (() -> Unit)? = null,
        onStartGroupWithPeer: (RecipientSearch.Candidate) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsScreen(
                    appState = testController.appState,
                    controller = testController.controller,
                    onBack = {},
                    onLeft = {},
                    onOpenSearch = onOpenSearch,
                    onStartGroupWithPeer = onStartGroupWithPeer,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** Asserts editor is open. */
    private fun assertEditorIsOpen() {
        composeRule.onNodeWithText(context.getString(R.string.group_name)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.group_description)).assertIsDisplayed()
    }

    /** Controller. */
    private fun controller(
        group: AppGroupRecordFfi,
        extraMembers: Int = 0,
        verifiedRoster: Boolean = false,
        membersOverride: List<AppGroupMemberRecordFfi>? = null,
    ): TestController {
        val appState = testAppState()
        val members =
            membersOverride
                ?: (
                    listOf(member(SELF_HEX, local = true), member("member-b"), member("member-c")) +
                        ('d'..'z').take(extraMembers).map { member("member-$it") }
                )
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = GroupMemberSnapshot(members),
                groupRosterReader = { account, groupId ->
                    assertEquals(ACCOUNT_REF, account)
                    assertEquals(group.groupIdHex, groupId)
                    GroupRosterFfi(
                        groupIdHex = groupId,
                        members =
                            members.map { member ->
                                GroupMemberDetailsFfi(
                                    memberIdHex = member.memberIdHex,
                                    account = member.account,
                                    local = member.local,
                                    isAdmin = member.memberIdHex in group.admins,
                                    isSelf = member.memberIdHex == SELF_HEX,
                                    npub = "npub-${member.memberIdHex}",
                                    displayName = null,
                                )
                            },
                        epoch = 1uL,
                        rosterRevision = 1uL,
                        selfMembership = group.selfMembership,
                        memberCount = members.size.toUInt(),
                        lifecycleState = GroupLifecycleStateFfi.STABLE,
                    )
                },
            )
        if (verifiedRoster) {
            // A cached opening snapshot does not grant authoritative roster access.
            runBlocking { controller.retryMembers() }
            assertEquals(GroupRosterLoadState.READY, controller.memberRosterState)
        }
        return TestController(appState, controller)
    }

    private fun group(
        groupId: String = "group-a",
        name: String = GROUP_NAME,
        admin: Boolean = true,
        pendingConfirmation: Boolean = false,
        disbanded: Boolean = false,
    ): AppGroupRecordFfi =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = groupId,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = name,
            description = "",
            admins = if (admin) listOf(SELF_HEX) else listOf("member-b"),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$groupId",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = pendingConfirmation,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = disbanded,
            disbandRequest = null,
        )

    private fun encryptedMedia(): AppGroupEncryptedMediaComponentFfi =
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
                        baseUrl = "https://blossom.primal.net",
                    ),
                ),
        )

    private fun member(
        memberId: String,
        local: Boolean = false,
    ): AppGroupMemberRecordFfi =
        AppGroupMemberRecordFfi(
            memberIdHex = memberId,
            account = if (local) ACCOUNT_REF else null,
            local = local,
        )

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { SELF_HEX },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = SELF_HEX,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
            profileReader = { null },
        )

    private data class TestController(
        val appState: WhiteNoiseAppState,
        val controller: ConversationController,
    )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val SELF_HEX = "self-a"
        const val GROUP_NAME = "Weekend hikers"
    }
}
