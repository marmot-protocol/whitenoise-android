package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1200dp-mdpi")
class GroupEditNameEmojiPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun actionIsLeadingAccessibleAndInsertsAtTheRestoredSelection() {
        val restorationTester = StateRestorationTester(composeRule)
        val appState = appState()
        val controller = controller(appState = appState, admin = true)
        restorationTester.setContent {
            WhiteNoiseTheme {
                GroupEditScreen(appState = appState, controller = controller, onBack = {})
            }
        }

        val fieldMatcher = hasSetTextAction() and hasText("Marmot team")
        val field = composeRule.onNode(fieldMatcher)
        val action = composeRule.onNodeWithContentDescription(string(R.string.open_emoji_picker))
        field.assertIsDisplayed()
        action.assertIsDisplayed().assertHasClickAction()
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        val actionBounds = action.getUnclippedBoundsInRoot()
        val fieldBounds = field.getUnclippedBoundsInRoot()
        assertTrue(
            "Emoji action must use the leading half of the field",
            actionBounds.left + actionBounds.right < fieldBounds.left + fieldBounds.right,
        )
        assertTrue("Emoji action width must be at least 48dp", actionBounds.right - actionBounds.left >= 48.dp)
        assertTrue("Emoji action height must be at least 48dp", actionBounds.bottom - actionBounds.top >= 48.dp)

        field.performTextInputSelection(TextRange(0, 6))
        restorationTester.emulateSavedInstanceStateRestore()
        val restored = composeRule.onNode(hasSetTextAction() and hasText("Marmot team"))
        assertEquals(TextRange(0, 6), restored.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])

        val family = "👨‍👩‍👧"
        restored.performTextReplacement("A${family}B")
        composeRule
            .onNode(hasSetTextAction() and hasText("A${family}B"))
            .performTextInputSelection(TextRange(4))
        composeRule.onNodeWithContentDescription(string(R.string.open_emoji_picker)).performClick()
        waitForEmoji("😀")
        composeRule.onAllNodesWithText("😀")[0].performClick()

        val expected = "A$family😀B"
        val updated = composeRule.onNode(hasSetTextAction() and hasText(expected))
        assertEquals(
            TextRange("A$family😀".length),
            updated.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
        )
    }

    @Test
    fun disabledEditorCannotOpenThePicker() {
        render(admin = false)

        composeRule
            .onNodeWithContentDescription(string(R.string.open_emoji_picker))
            .assertIsNotEnabled()
            .performClick()
        composeRule.onNodeWithContentDescription(string(R.string.emoji_search_hint)).assertDoesNotExist()
    }

    @Test
    fun savingAndMutationGatesDisableTheEmojiInput() {
        assertTrue(groupNameEmojiEditable(canEdit = true, saving = false, mutationInFlight = false))
        assertFalse(groupNameEmojiEditable(canEdit = false, saving = false, mutationInFlight = false))
        assertFalse(groupNameEmojiEditable(canEdit = true, saving = true, mutationInFlight = false))
        assertFalse(groupNameEmojiEditable(canEdit = true, saving = false, mutationInFlight = true))
    }

    private fun render(admin: Boolean) {
        val appState = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupEditScreen(
                    appState = appState,
                    controller = controller(appState, admin),
                    onBack = {},
                )
            }
        }
    }

    private fun waitForEmoji(emoji: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(emoji).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun string(res: Int): String = context.getString(res)

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun controller(
        appState: WhiteNoiseAppState,
        admin: Boolean,
    ) = ConversationController(
        appState = appState,
        initialGroup = group(admin),
        initialMemberSnapshot =
            GroupMemberSnapshot(
                listOf(
                    AppGroupMemberRecordFfi(
                        memberIdHex = ACCOUNT_ID,
                        account = ACCOUNT_REF,
                        local = true,
                    ),
                ),
            ),
    )

    private fun group(admin: Boolean) =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Marmot team",
            description = "Group description",
            admins = if (admin) listOf(ACCOUNT_ID) else emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
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
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
    }
}
