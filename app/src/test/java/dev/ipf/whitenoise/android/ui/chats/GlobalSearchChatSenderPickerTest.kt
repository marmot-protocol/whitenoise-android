package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real picker consumers preserve selection through search, loading and membership changes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GlobalSearchChatSenderPickerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Search virtualizes a large roster, announces its count and toggles stable identities. */
    @Test
    fun largeRosterSearchAndMultiSelectKeepAccountKeys() {
        val state = mutableStateOf(GlobalSearchState(isOpen = true, openFilterCategory = GlobalSearchFilterCategory.Sender))
        val options =
            GlobalSearchFilterOptions(
                senders = List(1000) { WhiteNoisePickerItem("key-$it", "Person ${it.toString().padStart(4, '0')}") },
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                GlobalSearchFilterPicker(state.value, options, { state.value = it(state.value) })
            }
        }
        assertResultCount(1000)
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTER_SEARCH_TAG).performTextInput("Person 0999")
        assertResultCount(1)
        composeRule
            .onNodeWithText("Person 0999")
            .assertIsOff()
            .performClick()
            .assertIsOn()
        composeRule.runOnIdle { assertEquals(setOf("key-999"), state.value.senderFilters.mapTo(mutableSetOf()) { it.stableId }) }
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTER_SEARCH_TAG).performTextClearance()
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTER_SEARCH_TAG).performTextInput("Person 0000")
        composeRule.onNodeWithText("Person 0000").performClick()
        composeRule.runOnIdle { assertEquals(setOf("key-999", "key-0"), state.value.senderFilters.mapTo(mutableSetOf()) { it.stableId }) }
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTER_SEARCH_TAG).performTextClearance()
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTER_SEARCH_TAG).performTextInput("Nobody matches")
        assertResultCount(0)
        composeRule.onNodeWithText(context.getString(R.string.no_results)).assertExists()
        composeRule.runOnIdle { assertEquals(2, state.value.senderFilters.size) }
    }

    /** An absent selected member never broadens the query; the same-name replacement is distinct. */
    @Test
    fun pendingAndMissingMemberRemainsExplicitlyRemovable() {
        val selected = GlobalSearchSenderFilter("old-key", "Alice")
        val state =
            mutableStateOf(
                GlobalSearchState(
                    isOpen = true,
                    openFilterCategory = GlobalSearchFilterCategory.Sender,
                    senderFilters = setOf(selected),
                ),
            )
        val options = mutableStateOf(GlobalSearchFilterOptions(loading = true))
        composeRule.setContent {
            WhiteNoiseTheme {
                GlobalSearchFilterPicker(state.value, options.value, { state.value = it(state.value) })
            }
        }
        composeRule.onNodeWithText("Alice").assertIsOn()
        composeRule.onNodeWithText(context.getString(R.string.search_filter_members_pending)).assertExists()
        composeRule.runOnIdle { options.value = GlobalSearchFilterOptions(membersPending = true) }
        composeRule.onNodeWithText("Alice").assertIsOn()
        composeRule.onNodeWithText(context.getString(R.string.search_filter_members_pending)).assertExists()
        composeRule.runOnIdle {
            options.value = GlobalSearchFilterOptions(senders = listOf(WhiteNoisePickerItem("new-key", "Alice")))
        }
        val missing = "Alice · ${context.getString(R.string.search_filter_unavailable)}"
        composeRule.onNodeWithText(missing).assertIsOn()
        composeRule.onNodeWithText("Alice").assertIsOff()
        composeRule.runOnIdle { assertEquals(setOf(selected), state.value.senderFilters) }
        composeRule.onNodeWithText(missing).performClick()
        composeRule.runOnIdle { assertTrue(state.value.senderFilters.isEmpty()) }
    }

    /** A deleted folder remains a selected unavailable row, rather than silently turning into all chats. */
    @Test
    fun deletedFolderRemainsRemovableUntilUserClearsIt() {
        val state =
            mutableStateOf(
                GlobalSearchState(
                    isOpen = true,
                    openFilterCategory = GlobalSearchFilterCategory.Folder,
                    folderFilters = setOf("removed-folder"),
                ),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                GlobalSearchFilterPicker(state.value, GlobalSearchFilterOptions(), { state.value = it(state.value) })
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.search_filter_unavailable)).assertExists()
        composeRule.onNodeWithTag(globalSearchFilterOptionTag("removed-folder")).performClick()
        composeRule.runOnIdle { assertTrue(state.value.folderFilters.isEmpty()) }
    }

    /** TalkBack collection metadata counts the filtered choices, never the raw account keys. */
    private fun assertResultCount(expected: Int) {
        composeRule.onNodeWithTag("entity.list").assert(
            SemanticsMatcher("$expected search choices") {
                it.config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount == expected
            },
        )
    }
}
