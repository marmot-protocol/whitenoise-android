package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchAccountScope
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchChatFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchContentFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchDateFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchSenderFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MainShellGlobalSearchStateRestorationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openQueryAndFiltersSurviveSavedStateRecreation() {
        val accountRef = mutableStateOf("personal")
        val runtimeGeneration = mutableIntStateOf(1)
        var holder: MainShellGlobalSearchStateHolder? = null
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            GlobalSearchStateOwnerProbe(
                accountRef = accountRef.value,
                runtimeGeneration = runtimeGeneration.intValue,
                onHolderReady = { holder = it },
            )
        }

        composeRule.runOnIdle {
            requireNotNull(holder).update(::populateSearch)
        }
        assertHolderState({ holder }, expectedSearchState(runtimeGeneration = 1))

        restorationTester.emulateSavedInstanceStateRestore()

        assertHolderState({ holder }, expectedSearchState(runtimeGeneration = 1))
    }

    @Test
    fun restoredStateReconcilesAChangedAccountScope() {
        val accountRef = mutableStateOf("personal")
        val runtimeGeneration = mutableIntStateOf(1)
        var holder: MainShellGlobalSearchStateHolder? = null
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            GlobalSearchStateOwnerProbe(
                accountRef = accountRef.value,
                runtimeGeneration = runtimeGeneration.intValue,
                onHolderReady = { holder = it },
            )
        }

        composeRule.runOnIdle {
            requireNotNull(holder).update(::populateSearch)
        }

        restorationTester.emulateSavedInstanceStateRestore()
        assertHolderState({ holder }, expectedSearchState(runtimeGeneration = 1))

        composeRule.runOnIdle {
            runtimeGeneration.intValue = 2
        }
        composeRule.waitForIdle()

        assertHolderState(
            { holder },
            expectedSearchState(
                runtimeGeneration = 2,
                includeAccountOwnedFilters = false,
            ),
        )
    }

    private fun assertHolderState(
        holder: () -> MainShellGlobalSearchStateHolder?,
        expected: GlobalSearchState,
    ) {
        composeRule.runOnIdle {
            assertEquals(expected, requireNotNull(holder()).scopedState)
        }
    }
}

@Composable
private fun GlobalSearchStateOwnerProbe(
    accountRef: String?,
    runtimeGeneration: Int,
    onHolderReady: (MainShellGlobalSearchStateHolder) -> Unit,
) {
    val holder = rememberMainShellGlobalSearchState(accountRef, runtimeGeneration)
    SideEffect {
        onHolderReady(holder)
    }
}

private fun populateSearch(state: GlobalSearchState): GlobalSearchState =
    state.copy(
        isOpen = true,
        query = "needle",
        chatFilters = setOf(GlobalSearchChatFilter("g1", "Alice")),
        senderFilters = setOf(GlobalSearchSenderFilter("npub1", "Bob")),
        dateFilters = setOf(GlobalSearchDateFilter("today", "Today")),
        contentFilters = setOf(GlobalSearchContentFilter("text", "Text")),
    )

private fun expectedSearchState(
    runtimeGeneration: Int,
    includeAccountOwnedFilters: Boolean = true,
): GlobalSearchState =
    GlobalSearchState(
        isOpen = true,
        query = "needle",
        accountScopeToken = GlobalSearchAccountScope.from("personal", runtimeGeneration).encodeToken(),
        chatFilters =
            if (includeAccountOwnedFilters) {
                setOf(GlobalSearchChatFilter("g1", "Alice"))
            } else {
                emptySet()
            },
        senderFilters =
            if (includeAccountOwnedFilters) {
                setOf(GlobalSearchSenderFilter("npub1", "Bob"))
            } else {
                emptySet()
            },
        dateFilters = setOf(GlobalSearchDateFilter("today", "Today")),
        contentFilters = setOf(GlobalSearchContentFilter("text", "Text")),
    )
