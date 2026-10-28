package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.advanceLocalDeleteTestRuntime
import dev.ipf.whitenoise.android.ui.conversation.LocalConversationMotionEnabled
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationSurfaceState
import dev.ipf.whitenoise.android.ui.profile.PROFILE_ADD_TO_GROUPS_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
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

    /** Verify both visual states even when the first golden needs an intentional update. */
    @get:Rule
    val visualErrors = ErrorCollector()

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
        val members = listOf(member(SELF_HEX, local = true)) + (1 until 150).map { member("member-$it") }
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

    /** Auto-open uses only the parent route; later manual entries keep their own movement and Back owner. */
    @Test
    fun autoOpenedAddMembersStartsSettledAndManualReentryStillMoves() {
        val fixture = controller(group(), verifiedRoster = true)
        val autoOpen = mutableStateOf(true)
        var exits = 0
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalConversationMotionEnabled provides true) {
                    WhiteNoiseTheme {
                        GroupDetailsScreen(
                            appState = fixture.appState,
                            controller = fixture.controller,
                            onBack = { exits++ },
                            onLeft = {},
                            autoOpenAddMember = autoOpen.value,
                            onAutoOpenAddMemberConsumed = { autoOpen.value = false },
                        )
                    }
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeByFrame()
            val autoFirst = addMembersTitleLeft()
            composeRule.mainClock.advanceTimeBy(240)
            assertEquals("Auto-opened picker must start at its settled position", autoFirst, addMembersTitleLeft(), .5f)
            assertFalse(autoOpen.value)

            // A real role update retires the automatic picker without disposing this details owner.
            composeRule.runOnIdle { fixture.controller.applyGroupStateForTest(group(admin = false)) }
            composeRule.mainClock.advanceTimeBy(240)
            composeRule.onNodeWithText(context.getString(R.string.add_member)).assertDoesNotExist()
            composeRule.onNodeWithTag("chat_info.add_people").assertDoesNotExist()
            composeRule.runOnIdle { fixture.controller.applyGroupStateForTest(group()) }
            composeRule.mainClock.advanceTimeBy(240)

            repeat(2) { assertManualAddMembersEntryMovesAndBackReturnsToDetails() }
            assertEquals("Manual Back must keep the details route mounted", 0, exits)
        } finally {
            composeRule.mainClock.autoAdvance = true
            fixture.controller.onCleared()
        }
    }

    private fun addMembersTitleLeft(): Float =
        composeRule
            .onNodeWithText(context.getString(R.string.add_member))
            .fetchSemanticsNode()
            .boundsInRoot.left

    /** Reads the real picker layer rather than a separate direction or suppression calculation. */
    private fun assertManualAddMembersEntryMovesAndBackReturnsToDetails() {
        composeRule.onNodeWithTag("chat_info.add_people").performScrollTo().performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        val first = addMembersTitleLeft()
        composeRule.mainClock.advanceTimeBy(240)
        val settled = addMembersTitleLeft()
        assertTrue("Manual picker should move in from the forward edge", first > settled + 1f)
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.onNode(hasText(GROUP_NAME) and hasClickAction()).assertIsDisplayed()
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

    /** Group details keep history reachable after people and administration, with developer tools last. */
    @Test
    fun overviewSectionsFollowThePrimaryActionOrder() {
        render(controller(group(), verifiedRoster = true))

        composeRule.onRoot().captureRoboImage("src/test/snapshots/group_details_primary_order_light.png")
        composeRule.onNodeWithTag("chat_info.shared_media").assertExists()

        val sectionTops =
            listOf(
                "chat_info.members",
                "chat_info.management",
                "chat_info.shared_media",
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
        composeRule.onNodeWithTag("chat_info.shared_media").assertExists()
        val sectionTops =
            listOf(
                "chat_info.members",
                "chat_info.shared_media",
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
        composeRule.onNodeWithTag("profile.add_groups.list").assertHeightIsAtLeast(400.dp)
        // This fixture has no peer profile; its cold identity intentionally renders Unknown.
        visualErrors.checkSucceeds {
            composeRule.onRoot().captureRoboImage("src/test/snapshots/dm_add_groups_route_light.png")
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNodeWithTag("chat_info.shared_media").assertExists()
        val sectionTops =
            listOf(
                "chat_info.group_actions",
                "chat_info.shared_media",
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

    /** Seeds native-style feedback through the actual mutation-presentation boundary. */
    private fun presentMutationFailure(controller: ConversationController) {
        controller.recordMutationFailure(
            R.string.toast_couldnt_add_members,
            "TEST",
            IllegalStateException("fixture failure"),
            detail = AppText.Plain("Member update failed"),
        )
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

    /** The Group relays destination reads the current native group and preserves overview restoration. */
    @Test
    fun relaysDestinationShowsNativeEmptyStateAndReturnsToItsOverview() {
        render(controller(group()))
        composeRule.onNodeWithTag("chat_info.relays").performScrollTo()
        composeRule.onNodeWithText(context.getString(R.string.group_relays)).assertIsDisplayed()
        val offset = overviewScrollOffset()
        composeRule.onNodeWithTag("chat_info.relays").performClick()
        composeRule.onNodeWithTag(GROUP_RELAYS_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(GROUP_RELAYS_ADMIN_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/chat_relays_route_empty_light.png")
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals(offset, overviewScrollOffset(), 1f)
        composeRule.onNodeWithTag("chat_info.relays").assertIsDisplayed()
    }

    /** A member opens the configured route from the record, de-duplicated, with the read-only explanation. */
    @Test
    fun memberRelaysDestinationListsTheRecordRoute() {
        render(controller(group(admin = false, relays = listOf(RELAY_A, "$RELAY_A/", RELAY_B))))
        composeRule
            .onNodeWithTag("chat_info.relays")
            .performScrollTo()
            .assert(hasText(context.resources.getQuantityString(R.plurals.chat_relay_count, 2, 2)))
            .performClick()
        composeRule.onNodeWithText(RELAY_A).assertIsDisplayed()
        composeRule.onNodeWithText(RELAY_B).assertIsDisplayed()
        composeRule.onNodeWithTag(GROUP_RELAYS_MEMBER_TAG).performScrollTo().assertIsDisplayed()
    }

    /** Developer Tools keeps only identifiers, so Group relays is the single place relays are listed. */
    @Test
    fun developerToolsNoLongerListsRelays() {
        render(controller(group(relays = listOf(RELAY_A))))
        composeRule.onNodeWithTag("chat_info.developer_tools").performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.mls_group_id)).assertIsDisplayed()
        composeRule.onNodeWithText(RELAY_A).assertDoesNotExist()
    }

    /** An open Group relays destination closes when another group's controller replaces the current one. */
    @Test
    fun switchingGroupsClosesTheRelaysDestination() {
        val current = mutableStateOf(controller(group(relays = listOf(RELAY_A))))
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
        composeRule.onNodeWithTag("chat_info.relays").performScrollTo().performClick()
        composeRule.onNodeWithText(RELAY_A).assertIsDisplayed()
        composeRule.runOnIdle {
            current.value = controller(group(groupId = "group-b", name = "Second group", relays = listOf(RELAY_B)))
        }
        composeRule.onNodeWithTag(GROUP_RELAYS_SCREEN_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(RELAY_A).assertDoesNotExist()
        composeRule.onNode(hasText("Second group") and hasClickAction()).assertIsDisplayed()
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

    /** Picking a successor opens review; cancel does not enter the native mutation path. */
    @Test
    fun soleAdminLeaveReviewsChosenSuccessorBeforeMutation() {
        val mutations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val nativeCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val promotionTargets = java.util.concurrent.CopyOnWriteArrayList<String>()
        val testController =
            controller(
                group(),
                verifiedRoster = true,
                identity =
                    GroupDetailsIdentityFixture(
                        memberNames = mapOf("member-b" to "Bob", "member-c" to "Carol"),
                        marmotRuntime = rejectingGroupAdminRuntime(mutations, nativeCalls, promotionTargets),
                    ),
            )
        val visibleController = androidx.compose.runtime.mutableStateOf(testController.controller)
        render(testController, controllerState = visibleController)
        composeRule.onNodeWithText(context.getString(R.string.leave_group)).performScrollTo().performClick()
        val successor = testController.controller.transferAdminCandidates().first()
        val successorName = testController.controller.memberDisplayName(successor)
        pickLeaveSuccessor(successorName)
        composeRule.onNodeWithTag("confirmation.confirm").assertIsDisplayed()
        val oldConfirmation =
            composeRule
                .onNodeWithTag("confirmation.confirm")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        assertSuccessorReviewIdentity(successorName)
        assertEquals(emptyList<String>(), mutations)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/group_leave_successor_review.png")
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithTag("confirmation.confirm").assertDoesNotExist()
        composeRule.runOnIdle { invokeDetachedReviewClick(oldConfirmation) }
        assertEquals(emptyList<String>(), mutations)
        composeRule.onNodeWithText(context.getString(R.string.leave_group)).performScrollTo().performClick()
        pickLeaveSuccessor(successorName)
        composeRule.runOnIdle { invokeDetachedReviewClick(oldConfirmation) }
        assertEquals(emptyList<String>(), mutations)
        val currentConfirmation =
            composeRule
                .onNodeWithTag("confirmation.confirm")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.onNodeWithTag("confirmation.confirm").assertIsEnabled().performClick()
        awaitPromotion(testController, nativeCalls, mutations)
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse(testController.controller.mutationInFlight) }
        // The fixture rejects promotion: confirm reaches that boundary, but never
        // demotes or leaves after rejection. Successful transfer/leave needs device evidence.
        assertEquals(listOf("promoteAdminDetailed"), mutations)
        assertEquals(listOf("member-b"), promotionTargets)
        composeRule.runOnIdle {
            invokeDetachedReviewClick(currentConfirmation)
            invokeDetachedReviewClick(oldConfirmation)
        }
        composeRule.waitForIdle()
        assertEquals(listOf("promoteAdminDetailed"), mutations)
        verifyReplacementReview(testController, visibleController, mutations, promotionTargets)
    }

    private fun assertSuccessorReviewIdentity(successorName: String) {
        composeRule
            .onNode(
                hasText(successorName) and hasAnyAncestor(hasTestTag("group.members.review.member")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        composeRule
            .onNode(
                hasText(GROUP_NAME) and hasAnyAncestor(hasTestTag("group.members.review.destination")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    private fun awaitPromotion(
        testController: GroupDetailsTestController,
        nativeCalls: List<String>,
        mutations: List<String>,
    ) {
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) { mutations.isNotEmpty() }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(
                "Native calls: $nativeCalls; " +
                    "roster=${testController.controller.memberRosterState}; " +
                    "error=${testController.controller.lastMutationError}",
                failure,
            )
        }
    }

    private fun verifyReplacementReview(
        testController: GroupDetailsTestController,
        visibleController: androidx.compose.runtime.MutableState<ConversationController>,
        mutations: java.util.concurrent.CopyOnWriteArrayList<String>,
        promotionTargets: java.util.concurrent.CopyOnWriteArrayList<String>,
    ) {
        composeRule.runOnIdle {
            mutations.clear()
            promotionTargets.clear()
            testController.appState.advanceLocalDeleteTestRuntime()
        }
        // MainShell replaces its runtime-keyed controller; the old controller remains invalid.
        val replacement = controller(group(), verifiedRoster = true, appStateOverride = testController.appState)
        composeRule.runOnIdle { visibleController.value = replacement.controller }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.leave_group)).performScrollTo().performClick()
        val replacementSuccessor = replacement.controller.transferAdminCandidates().first()
        pickLeaveSuccessor(replacement.controller.memberDisplayName(replacementSuccessor))
        composeRule.onNodeWithTag("confirmation.confirm").assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { mutations.isNotEmpty() }
        assertEquals(listOf("promoteAdminDetailed"), mutations)
        assertEquals(listOf("member-b"), promotionTargets)
    }

    private fun pickLeaveSuccessor(name: String) {
        val matcher = hasText(name) and hasClickAction()
        // Leave resolves the live roster on IO before opening the picker.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().size == 1
        }
        composeRule.onNode(matcher).performClick()
    }

    private fun render(
        testController: GroupDetailsTestController,
        onOpenSearch: (() -> Unit)? = null,
        onStartGroupWithPeer: (RecipientSearch.Candidate) -> Unit = {},
        controllerState: androidx.compose.runtime.State<ConversationController>? = null,
        darkTheme: Boolean = false,
        amoled: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                GroupDetailsScreen(
                    appState = testController.appState,
                    controller = controllerState?.value ?: testController.controller,
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
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupDetailsConfirmationNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

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

    /** An old confirmation callback cannot act under a replacement runtime of the same account. */
    @Test
    fun retainedTransferConfirmationRejectsSameAccountRuntimeReplacement() {
        val mutations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val nativeCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val fixture =
            controller(
                group(),
                verifiedRoster = true,
                identity =
                    GroupDetailsIdentityFixture(
                        memberNames = mapOf("member-b" to "Bob", "member-c" to "Carol"),
                        marmotRuntime =
                            rejectingGroupAdminRuntime(
                                mutations,
                                nativeCalls,
                                java.util.concurrent.CopyOnWriteArrayList(),
                            ),
                    ),
            )
        render(fixture)
        composeRule.onNodeWithText(context.getString(R.string.leave_group)).performScrollTo().performClick()
        pickLeaveSuccessor(fixture.controller.memberDisplayName(fixture.controller.transferAdminCandidates().first()))
        val retained =
            composeRule
                .onNodeWithTag("confirmation.confirm")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val callsBefore = nativeCalls.filterNot { it == "npub" }
        composeRule.runOnIdle {
            fixture.appState.advanceLocalDeleteTestRuntime()
            retained()
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), mutations)
        assertEquals(callsBefore, nativeCalls.filterNot { it == "npub" })
    }

    /** Equal async Leave results replace the ticket without stranding the visible review. */
    @Test
    fun repeatedLeaveRequestKeepsEqualVisibleReviewUsable() {
        val mutations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val nativeCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val promotions = java.util.concurrent.CopyOnWriteArrayList<String>()
        val fixture =
            controller(
                group(),
                verifiedRoster = true,
                membersOverride = listOf(member(SELF_HEX, local = true), member("member-b")),
                identity =
                    GroupDetailsIdentityFixture(
                        memberNames = mapOf("member-b" to "Bob"),
                        marmotRuntime = rejectingGroupAdminRuntime(mutations, nativeCalls, promotions),
                    ),
            )
        render(fixture)
        val leave = composeRule.onNodeWithText(context.getString(R.string.leave_group)).performScrollTo()
        val requestAgain = leave.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        leave.performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("confirmation.confirm")).fetchSemanticsNodes().size == 1
        }
        val callsBefore = nativeCalls.size
        composeRule.runOnIdle { requestAgain() }
        composeRule.waitUntil(timeoutMillis = 5_000) { nativeCalls.size > callsBefore }
        composeRule.waitForIdle()
        // The node is still attached; verify its newly rendered action remains usable.
        assertEquals(emptyList<String>(), mutations)
        composeRule.onNodeWithTag("confirmation.confirm").assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { mutations.isNotEmpty() }
        composeRule.waitForIdle()
        assertEquals(listOf("promoteAdminDetailed"), mutations)
        assertEquals(listOf("member-b"), promotions)
    }

    private fun pickLeaveSuccessor(name: String) {
        val matcher = hasText(name) and hasClickAction()
        // Leave resolves the live roster on IO before opening the picker.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().size == 1
        }
        composeRule.onNode(matcher).performClick()
    }

    private fun render(
        testController: GroupDetailsTestController,
        onOpenSearch: (() -> Unit)? = null,
        onStartGroupWithPeer: (RecipientSearch.Candidate) -> Unit = {},
        controllerState: androidx.compose.runtime.State<ConversationController>? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsScreen(
                    appState = testController.appState,
                    controller = controllerState?.value ?: testController.controller,
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
}

/** Builds the details controller with optional public-identity fixtures. */
private fun controller(
    group: AppGroupRecordFfi,
    extraMembers: Int = 0,
    verifiedRoster: Boolean = false,
    membersOverride: List<AppGroupMemberRecordFfi>? = null,
    identity: GroupDetailsIdentityFixture = GroupDetailsIdentityFixture(),
    appStateOverride: dev.ipf.whitenoise.android.state.WhiteNoiseAppState? = null,
): GroupDetailsTestController {
    val memberNames = identity.memberNames
    val appState = appStateOverride ?: testAppState(memberNames, identity.marmotRuntime)
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
                                displayName = memberNames[member.memberIdHex],
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
    return GroupDetailsTestController(appState, controller)
}

/** Builds a native group record fixture with the given identity, role, lifecycle and configured relays. */
private fun group(
    groupId: String = "group-a",
    name: String = GROUP_NAME,
    admin: Boolean = true,
    pendingConfirmation: Boolean = false,
    disbanded: Boolean = false,
    relays: List<String> = emptyList(),
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
        relays = relays,
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

private fun testAppState(
    memberNames: Map<String, String> = emptyMap(),
    marmotRuntime: dev.ipf.whitenoise.android.state.AppMarmotRuntime? = null,
): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = ApplicationProvider.getApplicationContext(),
        draftStore = DraftStore(GroupDetailsDraftPersistence()),
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
        initialMarmotRuntime = marmotRuntime,
        profileReader = { hex ->
            memberNames[hex]?.let {
                dev.ipf.marmotkit.UserProfileMetadataFfi(it, null, null, null, null, null, null)
            }
        },
    )

internal data class GroupDetailsTestController(
    val appState: WhiteNoiseAppState,
    val controller: ConversationController,
)

private class GroupDetailsDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

private const val ACCOUNT_REF = "account-a"
private const val SELF_HEX = "self-a"
private const val GROUP_NAME = "Weekend hikers"
private const val RELAY_A = "wss://relay.example.com"
private const val RELAY_B = "wss://second.example.org"

/** Optional public-identity and native-runtime fixtures for the real details-screen tests. */
private data class GroupDetailsIdentityFixture(
    val memberNames: Map<String, String> = emptyMap(),
    val marmotRuntime: dev.ipf.whitenoise.android.state.AppMarmotRuntime? = null,
)

/** Rejects the requested promotion so the UI test can prove no demotion or leave follows failure. */
private fun rejectingGroupAdminRuntime(
    mutations: MutableList<String>,
    nativeCalls: MutableList<String>,
    promotionTargets: MutableList<String>,
): dev.ipf.whitenoise.android.state.AppMarmotRuntime {
    val native =
        java.lang.reflect.Proxy.newProxyInstance(
            dev.ipf.marmotkit.MarmotInterface::class.java.classLoader,
            arrayOf(dev.ipf.marmotkit.MarmotInterface::class.java),
        ) { _, method, arguments ->
            nativeCalls += method.name
            when (method.name.substringBefore('-')) {
                "groupMembers" -> listOf(member(SELF_HEX, local = true), member("member-b"), member("member-c"))
                "npub" -> null
                "recordHostTiming" -> dev.ipf.marmotkit.ProductRecordResultFfi.IGNORED_DISABLED
                "promoteAdminDetailed", "selfDemoteAdminDetailed", "leaveGroup" -> {
                    if (method.name.substringBefore('-') == "promoteAdminDetailed") {
                        promotionTargets += arguments?.get(2).toString()
                    }
                    mutations += method.name.substringBefore('-')
                    error("Native mutation deliberately rejected by the test fixture")
                }
                else -> error("Unexpected native call: ${method.name}")
            }
        } as dev.ipf.marmotkit.MarmotInterface
    return dev.ipf.whitenoise.android.state
        .AppMarmotRuntime("test", native)
}

/** A disposed UI node rejects the saved semantics action before invoking application code. */
private fun invokeDetachedReviewClick(action: () -> Boolean) {
    try {
        action()
    } catch (failure: IllegalStateException) {
        if (failure.message != "Cannot read CompositionLocal because the Modifier node is not currently attached.") {
            throw failure
        }
    }
}

/** Reuses the same controller, group and account fixture for independently selected notification tests. */
internal fun notificationControlsFixture(): GroupDetailsTestController = controller(group())
