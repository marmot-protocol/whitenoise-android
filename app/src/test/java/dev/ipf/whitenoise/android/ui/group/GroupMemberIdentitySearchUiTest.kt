package dev.ipf.whitenoise.android.ui.group

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
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
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupMemberIdentitySearchUiTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var focusManager: FocusManager

    @Test
    fun seeAllFindsPublicIdentityWithoutMetadataAndClearRestoresRoster() {
        render(fixture())
        openSearch()
        composeRule.onNodeWithTag(SEARCH).performTextReplacement("nostr:$MEMBER_NPROFILE")
        awaitMatch()
        captureSearch("group_member_search_match_light.png")
        composeRule.onNodeWithContentDescription("Clear").performClick()
        composeRule.onNodeWithTag("chat_info.member.$OTHER_HEX").assertExists()
        composeRule.onNodeWithTag(SEARCH).performTextReplacement("Bob: work")
        (1..4).forEach { index ->
            composeRule.onNodeWithTag("chat_info.member.${"%064x".format(index)}").assertExists()
        }
        composeRule.onNodeWithTag("chat_info.member.$MEMBER_HEX").assertDoesNotExist()
    }

    @Test
    fun trailingPastePassesNprofileToDecoderOffMainThread() {
        val decoded = mutableListOf<String>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("public", "nostr:$MEMBER_NPROFILE"))
        val current =
            fixture { input ->
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                synchronized(decoded) { decoded.add(input) }
                validatedFixtureIdentity(input)
            }
        render(current)
        openSearch()
        composeRule.onNodeWithContentDescription("Paste").performClick()
        awaitMatch()
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it == MEMBER_NPROFILE })
    }

    @Test
    fun malformedIdentityNeverMatchesNamesAndUnavailableDecoderCanRetry() {
        val available = AtomicBoolean(false)
        val decoded = mutableListOf<String>()
        render(
            fixture { input ->
                synchronized(decoded) { decoded.add(input) }
                check(available.get()) { "Decoder unavailable" }
                validatedFixtureIdentity(input)
            },
            dark = true,
        )
        openSearch()
        composeRule.onNodeWithTag(SEARCH).performTextReplacement("nsec1private")
        composeRule.onNodeWithText("No matches").assertExists()
        assertTrue(decoded.isEmpty())
        composeRule.onNodeWithTag(SEARCH).performTextReplacement(MEMBER_NPUB)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("No matches").assertExists()
        captureSearch("group_member_search_retry_dark.png")
        available.set(true)
        composeRule.onNodeWithText("Retry").performClick()
        awaitMatch()
        composeRule.onNodeWithTag(SEARCH).performTextReplacement(MEMBER_NPUB.dropLast(1) + "q")
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("chat_info.member.$MEMBER_HEX").assertDoesNotExist()
    }

    @Test
    fun unknownIdentityAndIncompleteIdentityHaveSafeEmptyStateAtLargeRtlText() {
        render(fixture(), dark = true, rtl = true, fontScale = 2f)
        openSearch()
        composeRule.onNodeWithTag(SEARCH).performTextReplacement(OTHER_HEX.uppercase())
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("chat_info.member.$OTHER_HEX").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SEARCH).performTextReplacement(NON_MEMBER_HEX)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("No matches").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
        captureSearch("group_member_search_empty_rtl_large_dark.png")
        composeRule.onNodeWithTag(SEARCH).performTextReplacement("npub1")
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("chat_info.member.$MEMBER_HEX").assertDoesNotExist()
    }

    @Test
    fun groupSwitchCancelsInFlightSearch() {
        val pending = CompletableDeferred<String?>()
        val started = CompletableDeferred<Unit>()
        val secondPending = CompletableDeferred<String?>()
        val secondStarted = CompletableDeferred<Unit>()
        val first =
            fixture {
                started.complete(Unit)
                pending.await()
            }
        val current = mutableStateOf(first)
        render(current)
        openSearch()
        composeRule.onNodeWithTag(SEARCH).performTextReplacement(MEMBER_NPUB)
        composeRule.waitUntil(5_000) { started.isCompleted }
        composeRule.runOnIdle {
            current.value =
                fixture(groupId = "second") {
                    secondStarted.complete(Unit)
                    secondPending.await()
                }
        }
        composeRule.onNodeWithTag("chat_info.members_screen").assertDoesNotExist()
        composeRule.onNodeWithTag(SEARCH).assertDoesNotExist()
        assertFalse(first.controller.group.groupIdHex == current.value.controller.group.groupIdHex)
        openSearch()
        composeRule.onNodeWithTag(SEARCH).performTextReplacement(MEMBER_NPUB)
        composeRule.waitUntil(5_000) { secondStarted.isCompleted }
        pending.complete(MEMBER_HEX)
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.recipient_preview_resolving))
            .assertExists()
        composeRule.onNodeWithTag("chat_info.member.$MEMBER_HEX").assertDoesNotExist()
        secondPending.complete(MEMBER_HEX)
        awaitMatch()
    }

    @Test
    fun aLaterRosterUpdateRemovesThePreviouslyMatchedMember() {
        val current = fixture()
        render(current)
        openSearch()
        composeRule.onNodeWithTag(SEARCH).performTextReplacement(MEMBER_NPUB)
        awaitMatch()
        composeRule.runOnIdle {
            current.members.remove(MEMBER_HEX)
            runBlocking { current.controller.retryMembers() }
        }
        composeRule.onNodeWithText("No matches").assertExists()
        composeRule.onNodeWithTag("chat_info.member.$MEMBER_HEX").assertDoesNotExist()
    }

    @Test
    fun accountSwitchDiscardsTheOldResultAndKeepsTheNewQuery() {
        val pending = CompletableDeferred<String?>()
        val started = CompletableDeferred<Unit>()
        val current =
            fixture { input ->
                if (input == MEMBER_NPUB) {
                    started.complete(Unit)
                    pending.await()
                } else {
                    validatedFixtureIdentity(input)
                }
            }
        render(current)
        openSearch()
        composeRule.onNodeWithTag(SEARCH).performTextReplacement(MEMBER_NPUB)
        composeRule.waitUntil(5_000) { started.isCompleted }
        composeRule.runOnIdle { runBlocking { current.state.setActiveAccount("account-b") } }
        composeRule.onNodeWithTag(SEARCH).performTextReplacement(OTHER_HEX)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("chat_info.member.$OTHER_HEX").fetchSemanticsNodes().isNotEmpty()
        }
        pending.complete(MEMBER_HEX)
        composeRule.onNodeWithTag("chat_info.member.$MEMBER_HEX").assertDoesNotExist()
    }

    @Test
    fun failedRosterKeepsItsRetryInsteadOfClaimingNoSearchMatches() {
        render(fixture(rosterFailure = true))
        composeRule.onNodeWithText("Couldn't load conversation").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertExists()
        composeRule.onNodeWithText("No matches").assertDoesNotExist()
    }

    @Test
    fun interruptedRuntimeDiscardsTheOldResultAndRestartsWhenAvailable() {
        val pending = CompletableDeferred<String?>()
        val started = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val current =
            fixture { input ->
                if (attempts.incrementAndGet() == 1) {
                    started.complete(Unit)
                    pending.await()
                } else {
                    validatedFixtureIdentity(input)
                }
            }
        render(current)
        openSearch()
        composeRule.onNodeWithTag(SEARCH).performTextReplacement(MEMBER_NPUB)
        composeRule.waitUntil(5_000) { started.isCompleted }
        composeRule.runOnIdle { current.state.wipeInProgress = true }
        pending.complete(MEMBER_HEX)
        composeRule.onNodeWithTag("chat_info.member.$MEMBER_HEX").assertDoesNotExist()
        composeRule.runOnIdle { current.state.wipeInProgress = false }
        awaitMatch()
        assertTrue(attempts.get() >= 2)
    }

    private fun openSearch() {
        composeRule.onNodeWithTag("chat_info.all_members").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Search members").performClick()
    }

    private fun awaitMatch() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("chat_info.member.$MEMBER_HEX").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("chat_info.member.$OTHER_HEX").assertDoesNotExist()
    }

    private fun captureSearch(snapshot: String) {
        // The search state is the subject, not the focused cursor's blink phase.
        composeRule.runOnIdle { focusManager.clearFocus() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH).assertIsNotFocused()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$snapshot")
    }

    private fun render(
        fixture: Fixture,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) = render(mutableStateOf(fixture), dark, rtl, fontScale)

    private fun render(
        fixture: MutableState<Fixture>,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(1f, fontScale),
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    focusManager = LocalFocusManager.current
                    GroupDetailsScreen(fixture.value.state, fixture.value.controller, onBack = {}, onLeft = {})
                }
            }
        }
    }

    private fun fixture(
        rosterFailure: Boolean = false,
        groupId: String = "group-a",
        decode: suspend (String) -> String? = { validatedFixtureIdentity(it) },
    ): Fixture {
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore =
                    DraftStore(
                        object : DraftPersistence {
                            override fun read(): Map<String, String> = emptyMap()

                            override fun write(
                                key: String,
                                value: String?,
                            ) = Unit
                        },
                    ),
                accountIdHexResolver = decode,
                accounts = accounts(),
                activeAccountRef = "account-a",
                profileReader = { key ->
                    if (key == MEMBER_HEX) {
                        null
                    } else {
                        UserProfileMetadataFfi("Bob: work", null, null, null, null, null, null)
                    }
                },
                profileDisplayNameReader = { null },
            )
        val members = (listOf(MEMBER_HEX, OTHER_HEX) + (1..4).map { "%064x".format(it) }).toMutableList()
        val group = group(groupId)
        val controller =
            ConversationController(
                appState = state,
                initialGroup = group,
                initialMemberSnapshot =
                    GroupMemberSnapshot(
                        members.map { AppGroupMemberRecordFfi(it, null, it == OTHER_HEX) },
                    ),
                groupRosterReader = { _, id ->
                    check(!rosterFailure) { "Roster unavailable" }
                    GroupRosterFfi(
                        id,
                        members.map(::memberDetails),
                        1uL,
                        1uL,
                        SelfMembershipFfi.MEMBER,
                        members.size.toUInt(),
                        GroupLifecycleStateFfi.STABLE,
                    )
                },
            )
        runBlocking {
            controller.retryMembers()
            state.warmProfilePresentationsBlocking(members)
        }
        return Fixture(state, controller, members)
    }

    private fun accounts(): List<AccountSummaryFfi> =
        listOf(
            AccountSummaryFfi(
                label = "account-a",
                accountIdHex = OTHER_HEX,
                localSigning = true,
                externalSigning = false,
                signedOut = false,
                running = true,
            ),
            AccountSummaryFfi(
                label = "account-b",
                accountIdHex = NON_MEMBER_HEX,
                localSigning = true,
                externalSigning = false,
                signedOut = false,
                running = true,
            ),
        )

    private fun memberDetails(memberHex: String): GroupMemberDetailsFfi =
        GroupMemberDetailsFfi(
            memberIdHex = memberHex,
            account = null,
            local = memberHex == OTHER_HEX,
            isAdmin = false,
            isSelf = memberHex == OTHER_HEX,
            npub = MEMBER_NPUB,
            displayName = null,
        )

    private data class Fixture(
        val state: WhiteNoiseAppState,
        val controller: ConversationController,
        val members: MutableList<String>,
    )

    private fun group(groupId: String): AppGroupRecordFfi =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = groupId,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "Member search",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$groupId",
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

    private companion object {
        const val SEARCH = "chat_info.member_search"
    }
}
