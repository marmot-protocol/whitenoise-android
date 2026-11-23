package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTestApplication
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.search.GlobalPersonRow
import dev.ipf.whitenoise.android.ui.search.GlobalSearchContentFilterChips
import dev.ipf.whitenoise.android.ui.search.globalSearchContentChipTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

/** Actual header callbacks retain shell transitions; presentation cannot enable an unavailable search operation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GlobalSearchPresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Voice owns the empty trailing slot, and Clear returns the same shell state to an empty query. */
    @Test fun headerQueryClearAndVoiceUseTheExistingShellTransitions() {
        val state = mutableStateOf(GlobalSearchState(isOpen = true))
        var voices = 0
        val app = emptyApp()
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBar(
                    app,
                    state.value.isOpen,
                    state.value.query,
                    remember { FocusRequester() },
                    { state.value = GlobalSearchTransitions.setQuery(state.value, it) },
                    { state.value = GlobalSearchTransitions.openSearch(state.value) },
                    { state.value = GlobalSearchTransitions.closeSearch(state.value) },
                    { voices++ },
                    {},
                    {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_list_search_voice)).performClick()
        assertEquals(1, voices)
        composeRule.onNodeWithTag("chats.searchField").performTextReplacement("alice")
        assertEquals("alice", state.value.query)
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.chat_list_search_voice))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_list_search_clear)).performClick()
        assertEquals("", state.value.query)
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_list_search_voice)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertFalse(state.value.isOpen)
    }

    /** The chips row carries no opening action of its own; the header icon owns the menu, chips only clear. */
    @Test fun chipsRowClearsRestoredFiltersWithoutAnOpeningAction() {
        val state =
            mutableStateOf(
                GlobalSearchState(
                    isOpen = true,
                    chatFilters = setOf(GlobalSearchChatFilter("group", "Friends")),
                ),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                GlobalSearchFilterControlsRow(
                    state.value,
                    { state.value = GlobalSearchTransitions.removeFilter(state.value, it) },
                    { state.value = GlobalSearchTransitions.clearAllFilters(state.value) },
                )
            }
        }
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_CLEAR_ALL_FILTERS_TAG).performClick()
        assertTrue(state.value.chatFilters.isEmpty())
        assertTrue(state.value.isOpen)
        assertFalse(state.value.filterSheetOpen)
    }

    /** One native list action delivers the complete resolved key once, never the shortened visual label. */
    @Test fun identifierRowOpensTheExactResolvedPublicReference() {
        val key = "npub1" + "a".repeat(58)
        val opened = mutableListOf<String>()
        composeRule.setContent { WhiteNoiseTheme { GlobalPersonRow(key, onOpen = opened::add) } }
        composeRule.onNodeWithTag("global.person." + key).performClick()
        assertEquals(listOf(key), opened)
    }

    /** A cold identifier result upgrades in place to the published name and picture when the refresh completes. */
    @Test
    @Config(application = MediaDecodeTestApplication::class)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun identifierPreviewShowsProfileAsItArrives() {
        val npub = "npub1" + "a".repeat(58)
        val accountIdHex = "a".repeat(64)
        val picture = "https://profiles.example/search-avatar.png"
        val fallback = context.getString(R.string.chat_list_search_open_profile)
        val refreshStarted = CompletableDeferred<Unit>()
        val profileAvailable = CompletableDeferred<Unit>()
        val profile =
            UserProfileMetadataFfi(
                name = "alice",
                displayName = "Alice Rivera",
                about = null,
                picture = picture,
                nip05 = null,
                lud16 = null,
            )
        val app =
            emptyApp(
                profileReader = { id -> profile.takeIf { id == accountIdHex && profileAvailable.isCompleted } },
                profileDisplayNameReader = { null },
                profileRefreshRequest = { id ->
                    if (id == accountIdHex) refreshStarted.complete(Unit)
                    profileAvailable.await()
                },
            )
        val png =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> png }
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    ChatListIdentifierResult(
                        resolution = IdentifierResolution.Resolved(npub, accountIdHex),
                        appState = app,
                        accountRef = null,
                        existingDirectChat = { null },
                        onOpenChat = {},
                        onOpenProfile = {},
                    )
                }
            }
            composeRule.onNodeWithText(fallback).assertExists()
            composeRule.waitUntil(5_000) { refreshStarted.isCompleted }
            composeRule.runOnIdle { profileAvailable.complete(Unit) }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Alice Rivera").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitUntil(5_000) {
                AvatarImageLoader.peek(picture) != null &&
                    composeRule.onAllNodesWithText("A", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithText(fallback).assertDoesNotExist()
        } finally {
            app.profileScope.cancel()
            AvatarImageLoader.resetProfileImageFetcherForTests()
            AvatarImageLoader.clear()
        }
    }

    /** Check rows write the existing canonical content selection and remain reachable at large text. */
    @Test fun contentRowsPreserveToggleAndClearSemanticsAtLargeText() {
        val selection = mutableStateOf(GlobalSearchContentFilterSelection())
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                GlobalSearchContentFilterChips(selection.value, { selection.value = it })
            }
        }
        val tag = globalSearchContentChipTag(GlobalSearchContentKind.FILES_DOCUMENTS)
        composeRule
            .onNodeWithTag(tag)
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        assertTrue(GlobalSearchContentKind.FILES_DOCUMENTS in selection.value.selectedKinds)
        composeRule.onNodeWithTag(tag).performClick().assertIsNotSelected()
        assertTrue(selection.value.selectedKinds.isEmpty())
    }

    /** No account/runtime is bootstrapped to exercise the actual header's independent presentation callbacks. */
    private fun emptyApp(
        profileReader: (suspend (String) -> UserProfileMetadataFfi?)? = null,
        profileDisplayNameReader: (suspend (String) -> String?)? = null,
        profileRefreshRequest: (suspend (String) -> Unit)? = null,
    ): WhiteNoiseAppState {
        return WhiteNoiseAppState(
            context,
            DraftStore(
                object : DraftPersistence {
                    /** In-memory draft persistence: returns the stored values. */
                    override fun read(): Map<String, String> = emptyMap()

                    /** In-memory draft persistence: stores or clears one value. */
                    override fun write(
                        key: String,
                        value: String?,
                    ) = Unit
                },
            ),
            { null },
            emptyList(),
            "",
            profileReader = profileReader,
            profileDisplayNameReader = profileDisplayNameReader,
            profileRefreshRequest = profileRefreshRequest,
        )
    }
}
