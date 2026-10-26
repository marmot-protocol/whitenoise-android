package dev.ipf.whitenoise.android.ui.profile

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ProfileGroupPickerLoadState
import dev.ipf.whitenoise.android.state.ProfileGroupPickerState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.testGroupMutationResult
import dev.ipf.whitenoise.android.ui.navigation.ProfileGroupForegroundCoordinator
import dev.ipf.whitenoise.android.ui.navigation.ProfileGroupForegroundState
import dev.ipf.whitenoise.android.ui.share.chatRow
import dev.ipf.whitenoise.android.ui.share.member
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ProfileAddToGroupsFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Add to groups and back reuse one modal host. */
    @Test
    fun addToGroupsAndBackReuseOneModalHost() {
        renderProfile()
        val hostDialog = latestComponentDialog()

        composeRule
            .onNodeWithText(app.getString(R.string.person_add_to_group))
            .performScrollTo()
            .performClick()
        finishContentTransition()

        assertSame(hostDialog, latestComponentDialog())
        composeRule.onNodeWithTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG).assertExists()

        composeRule.runOnUiThread {
            hostDialog.onBackPressedDispatcher.onBackPressed()
        }
        finishContentTransition()

        assertSame(hostDialog, latestComponentDialog())
        composeRule.onNodeWithTag(PROFILE_SHEET_CONTENT_TAG).assertExists()
        composeRule.onNodeWithTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG).assertDoesNotExist()
    }

    /** Keeps the Add-to-groups entry reachable when no shared groups are listed. */
    @Test
    fun profileWithoutSharedGroupsKeepsAddEntryReachable() {
        renderProfile()
        composeRule
            .onNodeWithText(app.getString(R.string.person_groups_in_common))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        finishContentTransition()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/profile_empty_shared_groups.png")
        composeRule
            .onNodeWithText(app.getString(R.string.person_add_to_group))
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        finishContentTransition()
        composeRule.onNodeWithTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG).assertExists()
    }

    /** Shows loading while group eligibility is unresolved rather than declaring an empty result. */
    @Test
    fun unresolvedGroupsShowLoadingInsteadOfTerminalEmptyCopy() {
        val appState = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileAddToGroupsContent(
                    appState = appState,
                    target = RecipientSearch.Candidate(TARGET_HEX, "Alice", TARGET_NPROFILE),
                    state =
                        ProfileGroupPickerState(
                            groups = emptyList(),
                            pendingGroupIds = setOf("pending"),
                            loadState = ProfileGroupPickerLoadState.LOADING,
                        ),
                    busy = false,
                    onClose = {},
                    onRetry = {},
                    onAdd = {},
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.profile_addable_groups_loading)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.profile_no_addable_groups)).assertDoesNotExist()
    }

    /** Offers Retry when checking eligible shared groups fails. */
    @Test
    fun failedGroupCheckShowsRetryAction() {
        val appState = testAppState()
        var retryCount = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileAddToGroupsContent(
                    appState = appState,
                    target = RecipientSearch.Candidate(TARGET_HEX, "Alice", TARGET_NPROFILE),
                    state =
                        ProfileGroupPickerState(
                            groups = emptyList(),
                            pendingGroupIds = setOf("failed"),
                            loadState = ProfileGroupPickerLoadState.FAILED,
                        ),
                    busy = false,
                    onClose = {},
                    onRetry = { retryCount += 1 },
                    onAdd = {},
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.profile_addable_groups_failed)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.retry)).performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }
    }

    /** Captures eligible group choices with their ready confirmation action. */
    @Test
    fun addToGroupsPickerReadyStateScreenshot() {
        val appState = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ProfileAddToGroupsContent(
                        appState = appState,
                        target = RecipientSearch.Candidate(TARGET_HEX, "Alice", TARGET_NPROFILE),
                        state =
                            ProfileGroupPickerState(
                                groups = listOf(groupItem("friends", "Friends"), groupItem("work", "Work")),
                                pendingGroupIds = emptySet(),
                                loadState = ProfileGroupPickerLoadState.READY,
                            ),
                        busy = false,
                        onClose = {},
                        onRetry = {},
                        onAdd = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Friends").performClick()
        composeRule
            .onRoot()
            .captureRoboImage("src/test/snapshots/profile_add_to_groups_ready_dark.png")
    }

    /** Returns the chosen shared group from the Make-admin picker. */
    @Test
    @OptIn(ExperimentalMaterial3Api::class)
    fun makeAdminPickerReturnsTheSelectedSharedGroup() {
        val appState = testAppState()
        val group = groupItem("friends", "Friends")
        var promotedGroupId: String? = null
        composeRule.setContent {
            // Disable transient touch ripples for deterministic layout snapshots.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                WhiteNoiseTheme(darkTheme = true) {
                    Surface {
                        ProfileMakeAdminContent(
                            appState = appState,
                            targetName = "Alice",
                            target = RecipientSearch.Candidate("b".repeat(64), "Alice", "npub1alice"),
                            state =
                                ProfileGroupPickerState(
                                    groups = listOf(group),
                                    pendingGroupIds = emptySet(),
                                    loadState = ProfileGroupPickerLoadState.READY,
                                ),
                            busy = false,
                            onClose = {},
                            onRetry = {},
                            onPromote = { promotedGroupId = it.group.groupIdHex },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Friends").performClick()
        composeRule
            .onRoot()
            .captureRoboImage("src/test/snapshots/profile_make_admin_ready_dark.png")
        composeRule.onNode(hasText(app.getString(R.string.make_admin)) and hasClickAction()).performClick()
        composeRule.runOnIdle { assertEquals(null, promotedGroupId) }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("group.members.review.destination").assertIsDisplayed()
        composeRule
            .onNodeWithTag("group.members.review.dialog")
            .captureRoboImage("src/test/snapshots/profile_make_admin_review_dark.png")
        composeRule.onNodeWithTag("selected_members_confirm").performClick()
        composeRule.runOnIdle { assertEquals("friends", promotedGroupId) }
    }

    /** A failed invitation returns to its picker and opens a fresh, cancellable review. */
    @Test
    fun inviteFailureCanReopenCancelAndRetryFreshReview() = failedMutationCanReopenReview(promoting = false)

    /** A failed cross-group promotion does not retain a consumed review ticket. */
    @Test
    fun promotionFailureCanReopenCancelAndRetryFreshReview() = failedMutationCanReopenReview(promoting = true)

    private fun failedMutationCanReopenReview(promoting: Boolean) {
        val appState = testAppState()
        val busy = mutableStateOf(false)
        var submissions = 0
        val target = RecipientSearch.Candidate(TARGET_HEX, "Alice", TARGET_NPROFILE)
        val picker =
            ProfileGroupPickerState(
                groups = listOf(groupItem("friends", "Friends")),
                pendingGroupIds = emptySet(),
                loadState = ProfileGroupPickerLoadState.READY,
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                if (promoting) {
                    ProfileMakeAdminContent(
                        appState,
                        "Alice",
                        target,
                        picker,
                        busy.value,
                        {},
                        {},
                        onPromote = {
                            submissions++
                            busy.value = true
                        },
                    )
                } else {
                    ProfileAddToGroupsContent(
                        appState,
                        target,
                        picker,
                        busy.value,
                        {},
                        {},
                        onAdd = {
                            submissions++
                            busy.value = true
                        },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Friends").performClick()
        val action = app.getString(if (promoting) R.string.make_admin else R.string.profile_add_to_groups_confirm_label)
        composeRule.onNode(hasText(action) and hasClickAction()).performClick()
        composeRule.onNodeWithTag("selected_members_confirm").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("group.members.review.dialog").assertDoesNotExist()
        assertEquals(1, submissions)
        // The native operation failed without changing the selected group; the caller releases busy.
        composeRule.runOnIdle { busy.value = false }
        composeRule.onNode(hasText(action) and hasClickAction()).performClick()
        composeRule.onNodeWithText(app.getString(R.string.cancel)).performClick()
        assertEquals(1, submissions)
        composeRule.onNode(hasText(action) and hasClickAction()).performClick()
        composeRule.onNodeWithTag("selected_members_confirm").performClick()
        assertEquals(2, submissions)
    }

    /** Keeps the bottom action fixed while scrolling to the last eligible group. */
    @Test
    fun overflowingListKeepsActionAtBottomAndLastGroupReachable() {
        val state = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileAddToGroupsScreen(
                    appState = state,
                    target = RecipientSearch.Candidate(TARGET_HEX, "Alice", TARGET_NPROFILE),
                    state =
                        ProfileGroupPickerState(
                            groups = (1..30).map { groupItem("group-$it", "Group $it") },
                            pendingGroupIds = emptySet(),
                            loadState = ProfileGroupPickerLoadState.READY,
                        ),
                    busy = false,
                    onClose = {},
                    onRetry = {},
                    onAdd = {},
                )
            }
        }
        val action = composeRule.onNodeWithTag("profile.add_groups.action")
        val initial = action.fetchSemanticsNode().boundsInRoot
        assertTrue(initial.top > 600f)
        composeRule.onNodeWithTag("profile.add_groups.list").performScrollToNode(hasText("Group 30"))
        composeRule.onNodeWithText("Group 30").assertIsDisplayed().performClick()
        action.assertIsDisplayed()
        assertEquals(initial, action.fetchSemanticsNode().boundsInRoot)
    }

    /** Reserves keyboard space once for the full-screen Add-to-groups route. */
    @Test
    fun fullRouteConsumesKeyboardSpaceOnce() {
        val state = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileAddToGroupsScreen(
                    appState = state,
                    target = RecipientSearch.Candidate(TARGET_HEX, "Alice", TARGET_NPROFILE),
                    state =
                        ProfileGroupPickerState(
                            groups = (1..30).map { groupItem("group-$it", "Group $it") },
                            pendingGroupIds = emptySet(),
                            loadState = ProfileGroupPickerLoadState.READY,
                        ),
                    busy = false,
                    onClose = {},
                    onRetry = {},
                    onAdd = {},
                    keyboardInsets = WindowInsets(bottom = 210.dp),
                )
            }
        }
        val action = composeRule.onNodeWithTag("profile.add_groups.action")
        action.assertIsDisplayed()
        val bottom = action.fetchSemanticsNode().boundsInRoot.bottom
        // 780dp window - 210dp keyboard - 12dp footer margin; a second inset creates a 210dp gap.
        assertTrue("Action bottom $bottom should sit above the keyboard", bottom in 550f..558f)
        composeRule.onNodeWithTag("profile.add_groups.list").performScrollToNode(hasText("Group 30"))
        composeRule.onNodeWithText("Group 30").assertIsDisplayed()
    }

    /** Clears selected groups and open confirmation when the profile recipient changes. */
    @Test
    fun changingRecipientClearsSelectionAndOpenReview() {
        val state = testAppState()
        val recipient = mutableStateOf(RecipientSearch.Candidate(TARGET_HEX, "Alice", TARGET_NPROFILE))
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileAddToGroupsScreen(
                    appState = state,
                    target = recipient.value,
                    state =
                        ProfileGroupPickerState(
                            listOf(groupItem("garden", "Garden club")),
                            emptySet(),
                            ProfileGroupPickerLoadState.READY,
                        ),
                    busy = false,
                    onClose = {},
                    onRetry = {},
                    onAdd = {},
                )
            }
        }
        composeRule.onNodeWithText("Garden club").performClick()
        composeRule.onNodeWithTag("profile.add_groups.action").performClick()
        composeRule.onNodeWithTag("group.members.review.dialog").assertExists()
        composeRule.runOnIdle {
            recipient.value = RecipientSearch.Candidate("c".repeat(64), "Bob", "npub1bob")
        }
        composeRule.onNodeWithTag("group.members.review.dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("profile.add_groups.action").assertIsNotEnabled()
    }

    /** Checks that the production screen keeps its list bounded and its action reachable. */
    @Test
    fun screenCallerRetainsBoundedListAndReachableAction() {
        val state = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileAddToGroupsScreen(
                    appState = state,
                    target = RecipientSearch.Candidate(TARGET_HEX, "Alice", TARGET_NPROFILE),
                    state =
                        ProfileGroupPickerState(
                            groups = (1..30).map { groupItem("group-$it", "Group $it") },
                            pendingGroupIds = emptySet(),
                            loadState = ProfileGroupPickerLoadState.READY,
                        ),
                    busy = false,
                    onClose = {},
                    onRetry = {},
                    onAdd = {},
                )
            }
        }
        composeRule.onNodeWithTag("profile.add_groups.action").assertIsDisplayed()
        composeRule.onNodeWithTag("profile.add_groups.list").performScrollToNode(hasText("Group 30"))
        composeRule.onNodeWithText("Group 30").assertIsDisplayed()
        composeRule.onNodeWithTag("profile.add_groups.action").assertIsDisplayed()
    }

    /** Both dismissal controls return the nested picker to its captured Groups parent. */
    @Test
    fun nestedPickerBackAndCloseReturnToGroups() {
        renderProfile()
        val host = latestComponentDialog()
        openNestedPicker()
        composeRule.runOnUiThread { host.onBackPressedDispatcher.onBackPressed() }
        finishContentTransition()
        composeRule.onNodeWithTag("groups_in_common.list").assertExists()
        composeRule.onNodeWithTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG).assertDoesNotExist()
        assertSame(host, latestComponentDialog())

        composeRule.onNodeWithText(app.getString(R.string.person_add_to_group)).performClick()
        finishContentTransition()
        composeRule.onNodeWithText(app.getString(R.string.close)).performClick()
        finishContentTransition()
        composeRule.onNodeWithTag("groups_in_common.list").assertExists()
        composeRule.onNodeWithTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG).assertDoesNotExist()
        assertSame(host, latestComponentDialog())
    }

    /** Native invite acceptance uses the same parent return as Back, without setting route state in the test. */
    @Test
    @Suppress("LongMethod") // One isolated native invite fixture and its real nested modal completion.
    fun acceptedNestedInviteReturnsToGroups() {
        val group = groupItem(ConversationTimelineTestIds.GROUP_ID, "Invite destination").group
        val fixture =
            NestedInviteRuntimeFixture(
                group,
                ACTIVE_ACCOUNT_REF,
                ACTIVE_ACCOUNT_HEX,
                TARGET_HEX,
                THIRD_ACCOUNT_HEX,
                TARGET_NPROFILE,
            )
        val invited = fixture.invited
        val unexpected = fixture.unexpected
        val runtime = fixture.runtime
        val state =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(EmptyDraftPersistence()),
                accountIdHexResolver = { ref -> if (ref == TARGET_NPROFILE) TARGET_HEX else null },
                accounts = listOf(activeAccount()),
                activeAccountRef = ACTIVE_ACCOUNT_REF,
                initialMarmotRuntime = AppMarmotRuntime("profile-parent-test", runtime),
                profileReader = { null },
            )
        state.liveSubscriptionOverrides.chatList =
            dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("No live window in retained-profile fixture") },
                openChats = { _, _ -> error("No live feed in retained-profile fixture") },
                presentedChatList = { _, _ -> emptyList() },
            )
        val chats = ChatsController(state, ACTIVE_ACCOUNT_REF) { _, _ -> emptyList() }
        try {
            state.attachChatsController(chats)
            chats.setChatListVisible(false)
            chats.applyChatListRow(
                chatRow(group.groupIdHex).copy(
                    title = group.name,
                    groupName = group.name,
                    conversationKind = ChatConversationKindFfi.GROUP,
                ),
            )
            chats.applyLocalGroupDetails(
                group,
                listOf(member(ACTIVE_ACCOUNT_HEX, true), member(THIRD_ACCOUNT_HEX, false)),
            )
            renderProfile(state)
            openNestedPicker()
            composeRule.onNodeWithText(group.name).performClick()
            composeRule.onNodeWithTag("profile.add_groups.action").performClick()
            composeRule.onNodeWithTag("selected_members_confirm").performClick()
            composeRule.waitUntil(5_000) {
                assertEquals(
                    "Every native method must have an explicit fixture",
                    emptyList<String>(),
                    unexpected.toList(),
                )
                invited.size == 1
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("groups_in_common.list").fetchSemanticsNodes().size == 1
            }
            assertEquals(listOf(group.groupIdHex), invited.toList())
            assertEquals("Every native method must have an explicit fixture", emptyList<String>(), unexpected.toList())
            composeRule.onNodeWithTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG).assertDoesNotExist()
        } finally {
            composeRule.runOnIdle {
                state.attachChatsController(null)
                chats.onCleared()
            }
        }
    }

    /** Opens both production route callbacks; no test-only page setter participates. */
    private fun openNestedPicker() {
        composeRule.onNodeWithText(app.getString(R.string.person_groups_in_common)).performScrollTo().performClick()
        finishContentTransition()
        composeRule.onNodeWithText(app.getString(R.string.person_add_to_group)).performClick()
        finishContentTransition()
        composeRule.onNodeWithTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG).assertExists()
    }

    private fun renderProfile(appState: WhiteNoiseAppState = testAppState()) {
        appState.presentProfile(TARGET_NPROFILE)
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileGroupForegroundCoordinator(
                    appState = appState,
                    conversationController = null,
                    profileGroupForegroundState = ProfileGroupForegroundState(),
                    secureWindowEnabled = null,
                    profileSecurePolicy = SecureFlagPolicy.Inherit,
                    onOpenConversation = { _, _ -> },
                    onDismissProfile = appState::clearPresentedProfile,
                    onClosePicker = {},
                ) {
                    Text("Chat list shell")
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun testAppState() =
        WhiteNoiseAppState(
            context = app,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { reference -> reference.takeIf { it == TARGET_NPROFILE }?.let { TARGET_HEX } },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACTIVE_ACCOUNT_REF,
        )

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACTIVE_ACCOUNT_REF,
            accountIdHex = ACTIVE_ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun groupItem(
        groupIdHex: String,
        name: String,
    ) = ChatListItem(
        group =
            AppGroupRecordFfi(
                selfMembership = SelfMembershipFfi.MEMBER,
                groupIdHex = groupIdHex,
                protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
                profilePresent = false,
                endpoint = "endpoint",
                name = name,
                description = "",
                admins = listOf(ACTIVE_ACCOUNT_HEX),
                relays = listOf("wss://relay.example"),
                nostrGroupIdHex = "nostr-$groupIdHex",
                avatarUrl = null,
                avatarDim = null,
                avatarThumbhash = null,
                imageHashHex = null,
                encryptedMedia =
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
                    ),
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
            ),
        latest = null,
        otherMemberAccount = null,
        memberCount = 2,
        memberSnapshot = null,
    )

    private fun latestComponentDialog(): ComponentDialog {
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("Profile flow must own a ComponentDialog", dialog is ComponentDialog)
        return dialog as ComponentDialog
    }

    private fun finishContentTransition() {
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        val THIRD_ACCOUNT_HEX = "c".repeat(64)
        const val ACTIVE_ACCOUNT_REF = "active"
        const val ACTIVE_ACCOUNT_HEX =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val TARGET_HEX =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TARGET_NPROFILE = "nprofile-test-alice"
    }
}

/** Read-only identities and authoritative roster belong to this one native-invite fixture. */
private class NestedInviteRuntimeFixture(
    private val group: AppGroupRecordFfi,
    private val selfRef: String,
    private val selfHex: String,
    private val targetHex: String,
    private val thirdHex: String,
    targetProfile: String,
) {
    val invited = CopyOnWriteArrayList<String>()
    val unexpected = CopyOnWriteArrayList<String>()
    private val npubs =
        mapOf(
            targetHex to "npub1424242424242424242424242424242424242424242424242424qamrcaj",
            selfHex to "npub1hwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwasxw04hu",
            thirdHex to "npub1enxvenxvenxvenxvenxvenxvenxvenxvenxvenxvenxvenxvenxqn2pktz",
        )
    private val references =
        npubs.keys.associateWith { it } + npubs.entries.associate { it.value to it.key } +
            mapOf(selfRef to selfHex, targetProfile to targetHex)
    private val names = mapOf(selfHex to "Local profile", targetHex to "Alice", thirdHex to "Other member")
    val runtime =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { _, method, arguments ->
            val name = method.name.substringBefore('-')
            val args = fixtureArguments(name, method.parameterTypes, arguments.orEmpty().toList())
            when (name) {
                "recordHostTiming", "recordHostPerformance" -> ProductRecordResultFfi.IGNORED_DISABLED
                "accountIdHex" -> readIdentity(name, args)
                "npub" -> npubs.getValue(readIdentity(name, args))
                "displayName" -> names.getValue(readIdentity(name, args))
                "isUserBlocked", "isFollowing" -> relationship(name, args)
                "groupCommitDelivery" -> {
                    expect(name, args, listOf(selfRef, group.groupIdHex))
                    null
                }
                "groupConversationSnapshot" -> snapshot(name, args)
                "inviteMembersDetailed" -> invite(name, args)
                else -> reject("Unexpected native method $name")
            }
        } as MarmotInterface

    /** Java proxies receive the compiler's continuation after the native command arguments. */
    private fun fixtureArguments(
        name: String,
        parameterTypes: Array<Class<*>>,
        arguments: List<Any?>,
    ): List<Any?> {
        val semanticTypes =
            when (name) {
                "inviteMembersDetailed" -> listOf(String::class.java, String::class.java, List::class.java)
                "groupCommitDelivery", "groupConversationSnapshot" -> listOf(String::class.java, String::class.java)
                else -> return arguments
            }
        val expectedTypes = semanticTypes + Continuation::class.java
        if (parameterTypes.toList() != expectedTypes ||
            arguments.size != expectedTypes.size ||
            arguments.lastOrNull() !is Continuation<*>
        ) {
            reject("Unexpected suspend bridge for $name")
        }
        return arguments.dropLast(1)
    }

    private fun readIdentity(
        name: String,
        args: List<Any?>,
    ): String {
        if (args.size != 1) reject("Unexpected $name arguments: $args")
        return identity(args[0])
    }

    private fun identity(value: Any?): String {
        val ref = value as? String ?: reject("Unexpected identity $value")
        return references[ref] ?: reject("Unexpected identity $value")
    }

    private fun relationship(
        name: String,
        args: List<Any?>,
    ): Boolean {
        expect(name, args.take(1), listOf(selfRef))
        if (args.size != 2 || identity(args[1]) != targetHex) reject("Unexpected $name arguments: $args")
        return false
    }

    private fun snapshot(
        name: String,
        args: List<Any?>,
    ): dev.ipf.marmotkit.GroupConversationSnapshotFfi {
        expect(name, args, listOf(selfRef, group.groupIdHex))
        val members = if (invited.isEmpty()) listOf(selfHex, thirdHex) else listOf(selfHex, targetHex, thirdHex)
        val result = mutation(members)
        return dev.ipf.marmotkit.GroupConversationSnapshotFfi(result.details, result.managementState)
    }

    private fun invite(
        name: String,
        args: List<Any?>,
    ): dev.ipf.marmotkit.GroupMutationResultFfi {
        expect(name, args, listOf(selfRef, group.groupIdHex, listOf(targetHex)))
        invited += group.groupIdHex
        return mutation(listOf(selfHex, targetHex, thirdHex))
    }

    private fun mutation(members: List<String>) =
        testGroupMutationResult(
            members,
            listOf(selfHex),
            self = selfHex,
            selfRef = selfRef,
        ).let { it.copy(details = it.details.copy(group = group)) }

    private fun expect(
        name: String,
        actual: List<Any?>,
        expected: List<Any?>,
    ) {
        if (actual != expected) reject("Unexpected $name arguments: $actual")
    }

    private fun reject(message: String): Nothing {
        unexpected += message
        error(message)
    }
}
