package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isMemberMutedInGroup
import dev.ipf.whitenoise.android.state.memberMutePreferences
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Entry points for the per-member group mute row (#2782).
 *
 * The row belongs to a non-self member reached from inside a group conversation, and nowhere else:
 * not on a DM, not on the tester's own profile, and not on a profile opened away from a group.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ProfileSheetMemberMuteTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Starts each case with no stored member mutes so a leftover entry cannot decide the row's copy. */
    @Before
    fun clearStoredMutes() {
        showAppState().memberMutePreferences.retainAccounts(emptyList())
    }

    /** A group member's profile offers the mute row, and taking it stores the account/group/member entry. */
    @Test
    fun groupMemberProfileMutesThatMemberInThatGroupAlone() {
        val app = show(fromGroup = true)

        composeRule.onNodeWithTag(PROFILE_MEMBER_MUTE_ACTION_TAG).performScrollTo().assertExists()
        composeRule.onNodeWithText(context.getString(R.string.profile_mute_in_group)).assertExists()
        composeRule.onNodeWithTag(PROFILE_MEMBER_MUTE_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        assertTrue(app.isMemberMutedInGroup(ACCOUNT_REF, GROUP_ID, TARGET_HEX))
        assertFalse(
            "another group must stay audible",
            app.isMemberMutedInGroup(ACCOUNT_REF, OTHER_GROUP_ID, TARGET_HEX),
        )
    }

    /** After a successful mute the row reads as its own inverse and a second tap restores the member. */
    @Test
    fun mutedMemberRowOffersTheInverseActionAndReverses() {
        val app = show(fromGroup = true)

        composeRule.onNodeWithTag(PROFILE_MEMBER_MUTE_ACTION_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.profile_unmute_in_group)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.profile_mute_in_group)).assertDoesNotExist()

        composeRule.onNodeWithTag(PROFILE_MEMBER_MUTE_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        assertFalse(app.isMemberMutedInGroup(ACCOUNT_REF, GROUP_ID, TARGET_HEX))
        composeRule.onNodeWithText(context.getString(R.string.profile_mute_in_group)).assertExists()
    }

    /** A profile opened away from a group has no group to scope the preference to, so no row appears. */
    @Test
    fun profileOpenedOutsideAGroupHasNoMuteRow() {
        show(fromGroup = false)

        composeRule.onNodeWithTag(PROFILE_MEMBER_MUTE_ACTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.profile_mute_in_group)).assertDoesNotExist()
    }

    /** A direct message has no per-member dimension, so its peer's profile offers no mute row. */
    @Test
    fun directMessagePeerProfileHasNoMuteRow() {
        show(fromGroup = true, directMessage = true)

        composeRule.onNodeWithTag(PROFILE_MEMBER_MUTE_ACTION_TAG).assertDoesNotExist()
    }

    /** The tester's own profile inside a group never offers to mute them. */
    @Test
    fun selfProfileInsideAGroupHasNoMuteRow() {
        show(fromGroup = true, targetIsSelf = true)

        composeRule.onNodeWithTag(PROFILE_MEMBER_MUTE_ACTION_TAG).assertDoesNotExist()
    }

    /** Composes the sheet for the requested entry point and returns its app state. */
    private fun show(
        fromGroup: Boolean,
        directMessage: Boolean = false,
        targetIsSelf: Boolean = false,
    ): WhiteNoiseAppState {
        val app = showAppState(targetIsSelf)
        app.presentDiscoveredProfile(PUBLIC_KEY, profile)
        val controller =
            if (fromGroup) {
                ConversationController(
                    appState = app,
                    initialGroup = group(named = !directMessage),
                    initialIsDm = directMessage,
                )
            } else {
                null
            }
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileSheet(
                    appState = app,
                    npub = PUBLIC_KEY,
                    onOpenGroup = { _, _ -> },
                    onStartGroup = {},
                    onDismiss = {},
                    adminController = controller,
                )
            }
        }
        composeRule.waitForIdle()
        return app
    }

    /** App state viewing the profile as a different member, or as the profile's own account for the self case. */
    private fun showAppState(targetIsSelf: Boolean = false): WhiteNoiseAppState {
        val activeAccountIdHex = if (targetIsSelf) TARGET_HEX else SELF_HEX
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { TARGET_HEX },
            accounts = listOf(AccountSummaryFfi(ACCOUNT_REF, activeAccountIdHex, true, false, false, true)),
            activeAccountRef = ACCOUNT_REF,
        )
    }

    /** A named group, or an unnamed one when the fixture stands in for a direct message. */
    private fun group(named: Boolean): AppGroupRecordFfi =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = if (named) "Design" else "",
            description = "",
            admins = listOf(SELF_HEX),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$GROUP_ID",
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

    /** The encrypted-media component every group record carries. */
    private fun encryptedMedia(): AppGroupEncryptedMediaComponentFfi =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi("blossom-v1", "https://blossom.example")),
        )

    private object EmptyDraftPersistence : DraftPersistence {
        /** No persisted drafts back this fixture. */
        override fun read(): Map<String, String> = emptyMap()

        /** Draft writes are discarded by this fixture. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        const val GROUP_ID = "aa11bb22"
        const val OTHER_GROUP_ID = "cc33dd44"
        const val PUBLIC_KEY = "npub1424242424242424242424242424242424242424242424242424qamrcaj"
        val SELF_HEX = "11".repeat(32)
        val TARGET_HEX = "aa".repeat(32)
        val profile =
            UserProfileMetadataFfi(
                name = "Published name",
                displayName = null,
                about = null,
                picture = null,
                banner = null,
                nip05 = null,
                lud16 = null,
            )
    }
}
