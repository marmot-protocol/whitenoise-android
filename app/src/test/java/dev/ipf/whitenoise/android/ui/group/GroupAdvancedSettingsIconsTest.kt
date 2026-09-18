package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
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
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.TtsEngineInfo
import dev.ipf.whitenoise.android.audio.tts.TtsResolutionResult
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Advanced group of a chat's details carries one semantic glyph per row (#2619).
 *
 * The goldens render the real details screen rather than isolated rows, so the leading column, the
 * trailing switch and chevron, and the RTL mirroring of the direction-dependent glyphs are all pinned
 * together. The decorative icons must stay out of the accessibility tree.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupAdvancedSettingsIconsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** All four rows show their glyph in the light theme, aligned across switch and chevron rows. */
    @Test
    fun advancedGroupLight() {
        render()
        capture("group_advanced_icons_light")
    }

    /** AMOLED seams keep the leading column readable at the shared one-pixel row edge. */
    @Test
    fun advancedGroupAmoledDark() {
        render(darkTheme = true, amoled = true)
        capture("group_advanced_icons_amoled_dark")
    }

    /**
     * Right-to-left at double type: the wrap-text and speech glyphs mirror while the bell and palette
     * keep their orientation, and every leading slot stays aligned as titles wrap.
     */
    @Test
    @Config(qualifiers = "en-w320dp-h780dp-mdpi")
    fun advancedGroupRtlLargeFont() {
        render(layoutDirection = LayoutDirection.Rtl, fontScale = 2f)
        capture("group_advanced_icons_rtl_large_font_light")
    }

    /** Each row keeps its own control role, so the glyph changes none of the reported actions. */
    @Test
    fun rowsKeepTheirControlRoles() {
        render()
        assertRoleCount(Role.Switch, 1)
        assertRoleCount(Role.Button, EXPECTED_BUTTON_ROWS)
    }

    /**
     * The glyphs are decorative. Only the Read aloud row describes itself, pairing its title with the
     * resolved provenance; no icon contributes a second description for a screen reader to repeat.
     */
    @Test
    fun decorativeIconsAddNoDescription() {
        render()
        val descriptions =
            composeRule
                .onAllNodes(inAdvancedGroup(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)))
                .fetchSemanticsNodes()
                .flatMap { it.config[SemanticsProperties.ContentDescription] }
        val readAloud = context.getString(R.string.tts_auto_read_title)
        assert(descriptions.size == 1 && descriptions.single().startsWith(readAloud)) {
            "Advanced group should describe only the Read aloud row, found: $descriptions"
        }
    }

    /** Counts the merged rows of the Advanced group that report [role]. */
    private fun assertRoleCount(
        role: Role,
        expected: Int,
    ) {
        composeRule
            .onAllNodes(inAdvancedGroup(SemanticsMatcher.expectValue(SemanticsProperties.Role, role)))
            .assertCountEquals(expected)
    }

    /** Restricts [matcher] to the rows of the chat-details Advanced group. */
    private fun inAdvancedGroup(matcher: SemanticsMatcher): SemanticsMatcher {
        val group = hasTestTag(ADVANCED_GROUP_TAG)
        return matcher and hasAnyAncestor(group)
    }

    /** Scrolls the Advanced group into view and records it alone, not the whole viewport. */
    private fun capture(name: String) {
        composeRule
            .onNodeWithTag(ADVANCED_GROUP_TAG)
            .performScrollTo()
            .captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Composes the real details screen for a group whose device has a usable speech engine. */
    private fun render(
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val appState = appState()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group(),
                initialMemberSnapshot = GroupMemberSnapshot(listOf(member(SELF_HEX, local = true), member("member-b"))),
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) {
                    GroupDetailsScreen(
                        appState = appState,
                        controller = controller,
                        onBack = {},
                        onLeft = {},
                        onOpenSearch = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** A state whose resolved speech catalog makes the Read aloud row part of the group. */
    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
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
        ).also { it.applyUsableSpeechEngine() }

    /** Publishes a resolved local engine so the conditional Read aloud row composes. */
    private fun WhiteNoiseAppState.applyUsableSpeechEngine() {
        val delegate = WhiteNoiseAppState::class.java.getDeclaredField("ttsResolution\$delegate")
        delegate.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val state = delegate.get(this) as androidx.compose.runtime.MutableState<TtsResolutionResult?>
        state.value =
            TtsResolutionResult(
                status = TextToSpeech.SUCCESS,
                engines = listOf(TtsEngineInfo("com.test.tts", "Test TTS", EngineTrust.Local)),
                defaultEnginePackage = "com.test.tts",
                handle = null,
            )
    }

    /** A named group so the section reads Advanced rather than the direct-message actions. */
    private fun group(): AppGroupRecordFfi =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = GROUP_HEX,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "Weekend hikers",
            description = "",
            admins = listOf(SELF_HEX),
            relays = emptyList(),
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

    /** The media component every group record carries. */
    private fun encryptedMedia(): AppGroupEncryptedMediaComponentFfi {
        val endpoint = AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.example")
        return AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(endpoint),
        )
    }

    /** One roster entry; the Advanced group does not depend on member identity. */
    private fun member(
        memberId: String,
        local: Boolean = false,
    ): AppGroupMemberRecordFfi =
        AppGroupMemberRecordFfi(
            memberIdHex = memberId,
            account = if (local) ACCOUNT_REF else null,
            local = local,
        )

    /** Drafts are irrelevant here, so persistence reads empty and discards writes. */
    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val SELF_HEX = "self-a"
        const val GROUP_HEX = "group-a"
        const val ADVANCED_GROUP_TAG = "chat_info.actions"

        /** Read aloud, Sounds and notifications, and Chat bubble colors all open something. */
        const val EXPECTED_BUTTON_ROWS = 3
    }
}
