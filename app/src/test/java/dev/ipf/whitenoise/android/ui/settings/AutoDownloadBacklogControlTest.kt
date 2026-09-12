package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentDownloadIntentStore
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The download queue boundary on Data Usage: Stop asks first and limits itself to queued automatic work. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AutoDownloadBacklogControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy {
        context.getSharedPreferences("auto-download-backlog-control-test", Context.MODE_PRIVATE)
    }

    /** Every case starts from cleared preferences. */
    @Before
    fun resetPreferences() {
        preferences.edit().clear().commit()
    }

    /** Stop opens a confirmation that names only waiting automatic downloads. */
    @Test
    fun stopConfirmationClearlyLimitsTheActionToQueuedAutomaticWork() {
        render()

        composeRule
            .onNodeWithTag("data_usage.queue.action")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithText(context.getString(R.string.download_stop_confirmation)).assertIsDisplayed()
        composeRule.onNodeWithTag("download.stop.confirm").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/auto_download_stop_automatic_confirmation_light.png")
    }

    /** The same confirmation in the dark theme. */
    @Test
    fun stopConfirmationUsesDarkThemeColors() {
        render(darkTheme = true)

        composeRule
            .onNodeWithTag("data_usage.queue.action")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithText(context.getString(R.string.download_stop_confirmation)).assertIsDisplayed()
        composeRule.onNodeWithTag("download.stop.confirm").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/auto_download_stop_automatic_confirmation_dark.png")
    }

    /** A paused account shows Restart with the paused subtitle. */
    @Test
    fun pausedAccountOffersAnExplicitRestartBoundary() {
        AttachmentDownloadIntentStore(preferences).pauseAutomatic(ACCOUNT_REF)

        render()

        composeRule
            .onNodeWithText(context.getString(R.string.download_restart))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.download_paused)).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/auto_download_paused_restart_light.png")
    }

    /** The paused Restart boundary in the dark theme. */
    @Test
    fun pausedAccountRestartBoundaryUsesDarkThemeColors() {
        AttachmentDownloadIntentStore(preferences).pauseAutomatic(ACCOUNT_REF)

        render(darkTheme = true)

        composeRule
            .onNodeWithText(context.getString(R.string.download_restart))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/auto_download_paused_restart_dark.png")
    }

    /** Renders Data Usage for the test account. */
    private fun render(darkTheme: Boolean = false) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                DataUsageScreen(appState(), onBack = {})
            }
        }
    }

    /** One signed-in account on the test preference file. */
    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyBacklogDraftPersistence),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = "ab".repeat(32),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
            preferences = preferences,
        )

    private companion object {
        const val ACCOUNT_REF = "account-a"
    }
}

/** Draft storage that never persists. */
private object EmptyBacklogDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
