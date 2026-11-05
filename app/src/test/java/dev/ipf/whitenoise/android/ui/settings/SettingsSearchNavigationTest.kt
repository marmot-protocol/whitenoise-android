package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.setAppLockScreenVisibleForTest
import dev.ipf.whitenoise.android.state.AccountSwitchProfileSeed
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real Settings owner, route dispatch, lazy reveal and Back handlers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h560dp-mdpi")
class SettingsSearchNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var capabilityCalls = 0
    private val nativeAccessCalls = AtomicInteger()
    private val profileNameReads = AtomicInteger()

    @Test fun resultRevealsBelowFoldControlWithoutOpeningItsDialogAndToolbarBackRestoresQuery() {
        val appState = mount()
        search("text size")
        val scale = appState.fontScale
        composeRule.onNodeWithTag("settings.search.result.Appearance.font_size").performClick()
        composeRule.onNodeWithText(context.getString(R.string.font_size)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.font_size_preview)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(scale, appState.fontScale) }
        composeRule.onNodeWithText(context.getString(R.string.font_size)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.font_size_preview)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.done)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNodeWithTag("settings.search.field").assertTextContains("text size")
        composeRule.onNodeWithTag("settings.search.result.Appearance.font_size").assertIsDisplayed()
    }

    @Test fun settingsListKeepsMultipleItemRootsVerticallySeparated() {
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsList {
                    item {
                        Text("First item root")
                        Text("Second item root")
                    }
                }
            }
        }
        val first = composeRule.onNodeWithText("First item root").fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithText("Second item root").fetchSemanticsNode().boundsInRoot
        assertTrue("Multiple roots must stack with their original gap", second.top > first.bottom)
    }

    @Test fun destinationOnlyCatalogTargetNeverRevealsUnkeyedItems() {
        var reveals = 0
        val target = settingsSearchCatalog().first { it.id == "home.Relays" }
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalSettingsSearchTarget provides target,
                    LocalSettingsSearchOnReveal provides { reveals++ },
                ) {
                    SettingsList {
                        item { Text("First relay warning") }
                        item { Text("Second relay warning") }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.runOnIdle { assertEquals(0, reveals) }
    }

    @Test fun manyFolderRestoreAnchorIsRevealedOnceWithoutExecutingRestore() {
        var revealed = 0
        var restores = 0
        val target = settingsSearchCatalog().first { it.id == "ChatFolders.restore" }
        composeRule.setContent {
            var consumed by rememberSaveable { mutableStateOf(false) }
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalSettingsSearchTarget provides target,
                    LocalSettingsSearchRevealConsumed provides consumed,
                    LocalSettingsSearchOnReveal provides {
                        consumed = true
                        revealed++
                    },
                ) {
                    ChatFoldersContent(
                        state =
                            ChatFoldersState(
                                List(40) { index ->
                                    ChatFolderManageItem("folder-$index", "Folder $index", null, 0, false, false)
                                },
                                defaultsMissing = true,
                            ),
                        onBack = {},
                        onCreate = {},
                        onMove = { _, _ -> },
                        onEdit = {},
                        onDelete = {},
                        onRestoreDefaults = { restores++ },
                    )
                }
            }
        }
        val title = context.getString(R.string.folder_restore_defaults)
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, revealed)
            assertEquals(0, restores)
        }
        composeRule.onNodeWithTag(CHAT_FOLDERS_CONTENT_TAG).performScrollToNode(hasText("Folder 0"))
        composeRule.onNodeWithTag(CHAT_FOLDERS_CONTENT_TAG).performScrollToNode(hasText(title))
        composeRule.runOnIdle {
            assertEquals(1, revealed)
            assertEquals(0, restores)
        }
    }

    @Test fun nestedAboutResultReturnsDirectlyToSearchWithoutOpeningExternalPolicy() {
        mount()
        search("privacy policy")
        composeRule.onNodeWithTag("settings.search.result.About.privacy").performClick()
        composeRule.onNodeWithTag("about.privacy_policy").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNodeWithTag("settings.search.field").assertTextContains("privacy policy")
    }

    @Test fun systemBackAndSavedStateReturnToTheSameSearchVisit() {
        val restoration = StateRestorationTester(composeRule)
        mount(restoration)
        search("text size")
        composeRule.onNodeWithTag("settings.search.result.Appearance.font_size").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("settings.search.field").assertTextContains("text size")
    }

    @Test fun typingDeletionAndClearDoNotRediscoverPlatformCapabilities() {
        mount()
        composeRule.waitForIdle()
        val baseline = capabilityCalls
        search("f")
        listOf("fo", "font", "font s", "font", "").forEach { query ->
            composeRule.onNodeWithTag("settings.search.field").performTextReplacement(query)
        }
        composeRule.runOnIdle { assertEquals(baseline, capabilityCalls) }
        composeRule.onNodeWithText(context.getString(R.string.appearance)).assertIsDisplayed()
    }

    @Test fun populatedAccountTypingDoesNotReenterColdNativeIdentityOrProfileGetters() {
        mount(withAccount = true)
        composeRule.waitUntil(timeoutMillis = 5000) {
            profileNameReads.get() > 0 &&
                composeRule
                    .onAllNodesWithText(context.getString(R.string.profile_readiness_loading))
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
        composeRule.waitForIdle()
        val nativeBaseline = nativeAccessCalls.get()
        val profileBaseline = profileNameReads.get()
        assertTrue("Cold identity path must be exercised before checking edits", nativeBaseline > 0)
        search("f")
        listOf("font", "font size", "font", "").forEach { query ->
            composeRule.onNodeWithTag("settings.search.field").performTextReplacement(query)
        }
        composeRule.runOnIdle {
            assertEquals(nativeBaseline, nativeAccessCalls.get())
            assertEquals(profileBaseline, profileNameReads.get())
        }
    }

    @Test fun editingAScrolledQueryPublishesTheStrongestResultAtTheTop() {
        val appState = mount()
        val documents =
            prepareSettingsSearch(
                settingsSearchCatalog(),
                settingsSearchEligibilitySnapshot(appState, true),
                context::getString,
            )
        val before = searchSettings(documents, "e")
        val after = searchSettings(documents, "a")
        assertTrue(before.size > 6 && after.size > 6)
        search("e")
        val expected =
            composeRule
                .onNodeWithTag("settings.search.result.${before.first().entry.id}")
                .fetchSemanticsNode()
                .boundsInRoot.top
        composeRule.onNodeWithTag("settings.search.results").performScrollToIndex(5)
        composeRule.onNodeWithTag("settings.search.field").performTextReplacement("a")
        val first = composeRule.onNodeWithTag("settings.search.result.${after.first().entry.id}")
        first.assertIsDisplayed()
        assertEquals(expected, first.fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun cachedHeaderStillReflectsProfileRevisionChangesDuringSearch() {
        val appState = mount(withAccount = true)
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText("Settings account").fetchSemanticsNodes().isNotEmpty()
        }
        search("font")
        composeRule.runOnIdle {
            appState.applyAccountSwitchProfileSeed(
                AccountSwitchProfileSeed("a".repeat(64), null, "Updated settings account", null),
            )
        }
        composeRule.onNodeWithTag("settings.search.field").performTextReplacement("")
        composeRule.onNodeWithText("Updated settings account").assertIsDisplayed()
    }

    @Test fun productionRootSuppressionDisablesFilteringWithSystemAnimationsEnabled() {
        val resolver = context.contentResolver
        val previous = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        try {
            mount(suppressMotion = true)
            search("app font")
            val expectedTop =
                composeRule
                    .onNodeWithTag("settings.search.result.Appearance.font_family")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            val edit =
                composeRule
                    .onNodeWithTag("settings.search.field")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.SetText]
                    .action!!
            composeRule.mainClock.autoAdvance = false
            composeRule.runOnIdle { edit(AnnotatedString("font size")) }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeByFrame() // Commit lazy measure, without waiting for placement duration.
            composeRule.onNodeWithTag("settings.search.field").assertTextContains("font size")
            composeRule.onNodeWithTag("settings.search.result.Appearance.font_family").assertDoesNotExist()
            val currentTop =
                composeRule
                    .onNodeWithTag("settings.search.result.Appearance.font_size")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            assertEquals(expectedTop, currentTop, 1f)
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previous)
        }
    }

    @Test fun removedResultActionCannotActivateAfterAQueryEditBeforeRecomposition() {
        mount()
        search("app font")
        val oldAction =
            composeRule
                .onNodeWithTag("settings.search.result.Appearance.font_family")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val edit =
            composeRule
                .onNodeWithTag("settings.search.field")
                .fetchSemanticsNode()
                .config[SemanticsActions.SetText]
                .action!!
        composeRule.runOnIdle {
            edit(AnnotatedString("font size"))
            oldAction()
        }
        composeRule.onNodeWithTag("settings.search.field").assertTextContains("font size")
        composeRule.onNodeWithTag("settings.search.result.Appearance.font_family").assertDoesNotExist()
    }

    @Test fun staleResultActionRevalidatesDeveloperModeBeforeNavigation() {
        val appState = mount()
        composeRule.runOnIdle { appState.updateDeveloperMode(true) }
        search("streaming debug")
        val staleAction =
            composeRule
                .onNodeWithTag("settings.search.result.Developer.streaming_debug")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            appState.updateDeveloperMode(false)
            staleAction()
        }
        composeRule.onNodeWithTag("settings.search.field").assertTextContains("streaming debug")
        composeRule.onNodeWithTag("settings.search.result.Developer.streaming_debug").assertDoesNotExist()
    }

    @Test fun updaterSearchFollowsActualDistributionPolicy() {
        mount()
        search("App updates")
        val control = composeRule.onNodeWithTag("settings.search.result.Home.update_controls")
        if (BuildConfig.SELF_UPDATE_ENABLED) control.assertIsDisplayed() else control.assertDoesNotExist()
        composeRule.onNodeWithTag("settings.search.result.Home.update_available").assertDoesNotExist()
    }

    @Test fun runtimeAndLockRetireSearchSession() {
        val appState = mount()
        search("text size")
        composeRule.runOnIdle {
            val field = appState.javaClass.getDeclaredField("runtimeGeneration\$delegate").apply { isAccessible = true }
            (field.get(appState) as MutableIntState).intValue++
        }
        composeRule.onNodeWithTag("settings.search.field").assertDoesNotExist()
        search("font")
        composeRule.runOnIdle { appState.setAppLockScreenVisibleForTest(true) }
        composeRule.runOnIdle { appState.setAppLockScreenVisibleForTest(false) }
        composeRule.onNodeWithTag("settings.search.field").assertDoesNotExist()
    }

    private fun search(query: String) {
        composeRule.onNodeWithTag("settings.search.open").performClick()
        composeRule.onNodeWithTag("settings.search.field").performTextReplacement(query)
    }

    @Suppress("LongMethod") // One production Settings owner fixture, including cold-account adapters.
    private fun mount(
        restoration: StateRestorationTester? = null,
        suppressMotion: Boolean = true,
        withAccount: Boolean = false,
    ): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts =
                    if (withAccount) {
                        listOf(
                            AccountSummaryFfi(
                                label = "settings-search-account",
                                accountIdHex = "a".repeat(64),
                                localSigning = true,
                                externalSigning = false,
                                signedOut = false,
                                running = true,
                                unlinked = false,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                activeAccountRef = if (withAccount) "settings-search-account" else "no-such-account",
                marmotAccessObserver = { nativeAccessCalls.incrementAndGet() },
                marmotIoDispatcher = if (withAccount) Dispatchers.Unconfined else Dispatchers.IO,
                profileReader = { null },
                profileDisplayNameReader = {
                    profileNameReads.incrementAndGet()
                    "Settings account"
                },
                profileRefreshRequest = {},
                nativePushCapabilityResolver = {
                    capabilityCalls++
                    NativePushCapability.Available
                },
            )
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            WhiteNoiseTheme {
                var detail by rememberSaveable { mutableStateOf<SettingsDetail?>(null) }
                var viewport by rememberSaveable(
                    stateSaver = SettingsHomeViewport.Saver,
                ) { mutableStateOf(SettingsHomeViewport.Top) }
                SettingsScreen(
                    appState,
                    {},
                    {},
                    {},
                    detail,
                    { detail = it },
                    viewport,
                    { viewport = it },
                    suppressMotion = suppressMotion,
                )
            }
        }
        if (restoration == null) composeRule.setContent(content) else restoration.setContent(content)
        return appState
    }
}
