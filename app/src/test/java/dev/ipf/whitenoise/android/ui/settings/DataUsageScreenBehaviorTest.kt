package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MediaAutoDownloadMatrix
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Behaviour contract of Data Usage: network switches save at once, reset and queue controls act on the account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1600dp-mdpi")
class DataUsageScreenBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy { context.getSharedPreferences("data-usage-behaviour-test", Context.MODE_PRIVATE) }
    private lateinit var appState: WhiteNoiseAppState

    /** Start from cleared preferences so the matrix is the shipped default and nothing is paused. */
    @Before
    fun setUp() {
        preferences.edit().clear().commit()
        appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(EmptyDataUsageDraftPersistence),
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
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) { DataUsageScreen(appState = appState, onBack = {}) }
        }
    }

    /** A network switch in the Photos dialog writes the matrix at once; Done leaves the row summary updated. */
    @Test
    fun photosNetworkSwitchWritesImmediately() {
        composeRule.onNodeWithText("Wi-Fi, Mobile data, Metered network").assertExists()
        composeRule.onNodeWithText("Photos").performClick()
        composeRule.onNodeWithTag("download.network.WiFi").performClick()
        composeRule.runOnIdle {
            val matrix = appState.mediaAutoDownloadMatrix
            assertFalse(matrix.isEnabled(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi))
        }
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("Mobile data, Metered network").assertExists()
        composeRule.onNodeWithText("Reset download settings").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(MediaAutoDownloadMatrix.DEFAULT, appState.mediaAutoDownloadMatrix) }
        composeRule.onNodeWithText("Reset download settings").assertIsNotEnabled()
    }

    /** Files download nowhere by default and say so. */
    @Test
    fun filesShowNeverAtDefaults() {
        composeRule.onNodeWithText("Never").assertExists()
        composeRule.onNodeWithText("Reset download settings").assertIsNotEnabled()
    }

    /** Stop asks for confirmation, then pauses the queue; the row turns into Restart and resumes on tap. */
    @Test
    fun stopAsksForConfirmationThenPausesAndRestarts() {
        composeRule.onNodeWithText("Stop automatic downloads").performClick()
        composeRule.onNodeWithText(STOP_CONFIRMATION).assertExists()
        composeRule.runOnIdle { assertFalse(appState.automaticAttachmentDownloadsPaused()) }
        composeRule.onNodeWithTag("download.stop.confirm").performClick()
        composeRule.runOnIdle { assertTrue(appState.automaticAttachmentDownloadsPaused()) }
        composeRule.onNodeWithText("Automatic downloads are paused").assertExists()
        composeRule.onNodeWithText("Restart automatic downloads").performClick()
        composeRule.runOnIdle { assertFalse(appState.automaticAttachmentDownloadsPaused()) }
        composeRule.onNodeWithText("Automatic downloads follow your network rules").assertExists()
    }

    /** The quality dialog shows each option with its size hint and writes the tapped one. */
    @Test
    fun qualityDialogWritesTheChoice() {
        assertEquals(MediaQuality.Standard, appState.mediaQuality)
        composeRule.onNodeWithText("Media quality").performClick()
        composeRule.onNodeWithText("About 1.5 MB per photo — quality over data cost").assertExists()
        composeRule.onNodeWithText("High").performClick()
        composeRule.runOnIdle { assertEquals(MediaQuality.High, appState.mediaQuality) }
        composeRule.onNodeWithText("Original").assertDoesNotExist()
        composeRule.onNodeWithText("High").assertExists()
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val STOP_CONFIRMATION =
            "Clear waiting automatic downloads for this profile? " +
                "Active downloads and downloads you started yourself will continue."
    }
}

/** Draft storage that never persists, so the fixture starts clean. */
private object EmptyDataUsageDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
