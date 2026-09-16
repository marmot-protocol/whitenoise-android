package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.BlockListSnapshotFfi
import dev.ipf.marmotkit.BlockedUserFfi
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.BlockedUsersScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual regression coverage for the blocked-users settings list. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class BlockedUsersScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val appState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "acct",
                        accountIdHex = "c".repeat(64),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = "acct",
        )

    /** Empty list explains that nobody is blocked. */
    @Test
    fun blockedUsersEmptyLight() {
        composeRule.setContent { WhiteNoiseTheme(darkTheme = false) { BlockedUsersScreen(appState, onBack = {}) } }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/blocked_users_empty_light.png")
    }

    /** Two blocked accounts render as unblock rows. */
    @Test
    fun blockedUsersPopulatedDark() {
        appState.runtimeMirrors.blocks.install(
            BlockListSnapshotFfi(
                revision = 1uL,
                users =
                    listOf(
                        BlockedUserFfi("a".repeat(64), isPrivate = false, createdAtMs = 1L),
                        BlockedUserFfi("b".repeat(64), isPrivate = true, createdAtMs = 2L),
                    ),
            ),
        )
        composeRule.setContent { WhiteNoiseTheme(darkTheme = true) { BlockedUsersScreen(appState, onBack = {}) } }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/blocked_users_populated_dark.png")
    }
}
