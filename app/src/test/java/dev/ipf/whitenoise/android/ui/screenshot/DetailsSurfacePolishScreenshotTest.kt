package dev.ipf.whitenoise.android.ui.screenshot

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.group.GroupDetailsScreen
import dev.ipf.whitenoise.android.ui.profile.PROFILE_QUICK_ACTIONS_TAG
import dev.ipf.whitenoise.android.ui.profile.ProfileSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Full-viewport visual contract for the three details surfaces tracked by #1669. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DetailsSurfacePolishScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun groupAdminLight() =
        captureGroupDetails(
            snapshot = "group_details_admin_full_light.png",
            group = group(admin = true),
            members = groupMembers(),
            darkTheme = false,
        )

    @Test
    fun groupMemberAmoled() =
        captureGroupDetails(
            snapshot = "group_details_member_full_amoled.png",
            group = group(admin = false),
            members = groupMembers(),
            darkTheme = true,
            amoled = true,
        )

    @Test
    fun dmDark() =
        captureGroupDetails(
            snapshot = "dm_details_full_dark.png",
            group = dmGroup(),
            members = listOf(member(SELF_HEX, local = true), member(PEER_HEX)),
            darkTheme = true,
        )

    @Test
    fun otherProfileLight() =
        captureProfile(
            snapshot = "profile_sheet_other_full_light.png",
            targetHex = PEER_HEX,
            profile =
                UserProfileMetadataFfi(
                    name = "wise-bee",
                    displayName = "Wise Bee",
                    about = LONG_BIO,
                    picture = null,
                    banner = null,
                    nip05 = null,
                    lud16 = "wisebee@example.com",
                ),
            darkTheme = false,
        )

    @Test
    fun ownProfileAmoled() =
        captureProfile(
            snapshot = "profile_sheet_own_full_amoled.png",
            targetHex = SELF_HEX,
            profile =
                UserProfileMetadataFfi(
                    name = "festive-panda",
                    displayName = "Festive Panda",
                    about = "Your public profile, presented without contact-only actions.",
                    picture = null,
                    banner = null,
                    nip05 = null,
                    lud16 = null,
                ),
            darkTheme = true,
            amoled = true,
        )

    @Test
    fun missingProfileLargeFontDark() =
        captureProfile(
            snapshot = "profile_sheet_missing_full_large_dark.png",
            targetHex = PEER_HEX,
            profile = null,
            darkTheme = true,
            fontScale = 2f,
        )

    @Test
    fun unresolvedProfileLargeFontDark() =
        captureProfile(
            snapshot = "profile_sheet_unresolved_full_large_dark.png",
            npub = UNRESOLVED_NPUB,
            targetHex = null,
            profile = null,
            darkTheme = true,
            fontScale = 2f,
        )

    private fun captureGroupDetails(
        snapshot: String,
        group: AppGroupRecordFfi,
        members: List<AppGroupMemberRecordFfi>,
        darkTheme: Boolean,
        amoled: Boolean = false,
    ) {
        val appState = appState()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = GroupMemberSnapshot(members),
            )

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                GroupDetailsScreen(
                    appState = appState,
                    controller = controller,
                    onBack = {},
                    onLeft = {},
                    onOpenSearch = {},
                )
            }
        }
        composeRule.waitForIdle()
        assertUnavailableCallsAreAbsent()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$snapshot")
    }

    private fun captureProfile(
        snapshot: String,
        targetHex: String?,
        profile: UserProfileMetadataFfi?,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        npub: String = if (targetHex == SELF_HEX) SELF_NPUB else PEER_NPUB,
    ) {
        val appState = appState(profileHex = targetHex, profile = profile)
        appState.presentDiscoveredProfile(npub, profile)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    ProfileSheet(
                        appState = appState,
                        npub = npub,
                        onOpenGroup = { _, _ -> },
                        onStartGroup = {},
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertUnavailableCallsAreAbsent()
        if (targetHex == null || targetHex == SELF_HEX) {
            composeRule.onNodeWithTag(PROFILE_QUICK_ACTIONS_TAG).assertDoesNotExist()
        } else {
            composeRule.onNodeWithTag(PROFILE_QUICK_ACTIONS_TAG).assertExists()
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$snapshot")
    }

    private fun assertUnavailableCallsAreAbsent() {
        composeRule.onNodeWithText(app.getString(R.string.quick_action_audio)).assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.quick_action_video)).assertDoesNotExist()
    }

    private fun appState(
        profileHex: String? = null,
        profile: UserProfileMetadataFfi? = null,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = app,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { reference ->
                when {
                    reference.equals(SELF_HEX, ignoreCase = true) || reference == SELF_NPUB -> SELF_HEX
                    profileHex != null &&
                        (reference.equals(profileHex, ignoreCase = true) || reference == PEER_NPUB) -> profileHex
                    else -> null
                }
            },
            accounts = listOf(account()),
            activeAccountRef = ACCOUNT_REF,
            profileReader = { accountIdHex -> profile.takeIf { accountIdHex == profileHex } },
            profileDisplayNameReader = { accountIdHex ->
                if (accountIdHex != profileHex) null else profile?.displayName ?: PEER_SHORT_NPUB
            },
        )

    private fun account() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = SELF_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun group(admin: Boolean) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = GROUP_HEX,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "Weekend hikers",
            description = "Plans, trail conditions, carpools, and photos for the next weekend hike.",
            admins = if (admin) listOf(SELF_HEX) else listOf(PEER_HEX),
            relays = listOf("wss://relay.example.com"),
            nostrGroupIdHex = "nostr-$GROUP_HEX",
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

    private fun dmGroup() =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = DM_HEX,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "",
            description = "",
            admins = listOf(SELF_HEX),
            relays = listOf("wss://relay.example.com"),
            nostrGroupIdHex = "nostr-$DM_HEX",
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

    private fun groupMembers(): List<AppGroupMemberRecordFfi> =
        listOf(
            member(SELF_HEX, local = true),
            member(PEER_HEX),
            member("b".repeat(64)),
            member("c".repeat(64)),
            member("d".repeat(64)),
            member("e".repeat(64)),
        )

    private fun member(
        memberId: String,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = memberId,
        account = if (local) ACCOUNT_REF else null,
        local = local,
    )

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
                        baseUrl = "https://blossom.primal.net",
                    ),
                ),
        )

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val SELF_HEX = "1111111111111111111111111111111111111111111111111111111111111111"
        const val SELF_NPUB = "npub1zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygse4sl3h"
        const val PEER_HEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PEER_NPUB = "npub1424242424242424242424242424242424242424242424242424qamrcaj"
        const val UNRESOLVED_NPUB = "npub1unresolvedprofile"
        const val PEER_SHORT_NPUB = "npub14242424...4qamrcaj"
        const val GROUP_HEX = "group-a"
        const val DM_HEX = "dm-a"
        const val LONG_BIO =
            "Product designer, trail runner, and community organizer. " +
                "Usually sharing field notes, accessibility ideas, and plans for the next group meetup."
    }
}
