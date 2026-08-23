package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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

    @Test
    fun unavailableCallActionsDoNotOccupyThePrimaryActionRow() {
        render(controller(group()), onOpenSearch = {})

        composeRule.onNodeWithText(context.getString(R.string.quick_action_audio)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.quick_action_video)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.quick_action_add)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.quick_action_search)).assertIsDisplayed()

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val add =
            composeRule
                .onNodeWithText(context.getString(R.string.quick_action_add))
                .fetchSemanticsNode()
                .boundsInRoot
        val search =
            composeRule
                .onNodeWithText(context.getString(R.string.quick_action_search))
                .fetchSemanticsNode()
                .boundsInRoot
        val actionGap = search.center.x - add.center.x
        val actionMidpoint = (add.center.x + search.center.x) / 2f

        assertTrue("Primary actions must remain a compact cluster", actionGap < root.width * 0.35f)
        assertTrue("Primary actions must remain centered", kotlin.math.abs(actionMidpoint - root.center.x) < 1f)
    }

    @Test
    fun overflowEditAndAddDescriptionOpenTheSameEditor() {
        render(controller(group()))

        composeRule.onNodeWithContentDescription(context.getString(R.string.actions)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.edit)).performClick()
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
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsScreen(
                    appState = testController.appState,
                    controller = testController.controller,
                    onBack = {},
                    onLeft = {},
                    onOpenSearch = onOpenSearch,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertEditorIsOpen() {
        composeRule.onNodeWithText(context.getString(R.string.group_name)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.description)).assertIsDisplayed()
    }

    private fun controller(group: AppGroupRecordFfi): TestController {
        val appState = testAppState()
        return TestController(
            appState = appState,
            controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group,
                    initialMemberSnapshot =
                        GroupMemberSnapshot(
                            listOf(
                                member(SELF_HEX, local = true),
                                member("member-b"),
                                member("member-c"),
                            ),
                        ),
                ),
        )
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
