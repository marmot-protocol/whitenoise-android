package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual first-frame contract for #2178: cached profile data paints before the async refresh returns. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ProfileEditCachedFirstFrameScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cachedProfileOwnsTheFirstRenderedFrame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(EmptyProfileEditDraftPersistence),
                accountIdHexResolver = { null },
                accounts =
                    listOf(
                        AccountSummaryFfi(
                            label = ACCOUNT_REF,
                            accountIdHex = ACCOUNT_ID,
                            localSigning = true,
                            externalSigning = false,
                            signedOut = false,
                            running = true,
                        ),
                    ),
                activeAccountRef = ACCOUNT_REF,
            )
        val cached =
            profileEditMetadata(
                displayName = "Cached Alice",
                about = "This cached bio is ready immediately.",
                picture = "",
                banner = "",
                nip05 = "alice@example.com",
                lud16 = "alice@getalby.com",
            )

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ProfileEditScreen(
                    appState = appState,
                    onBack = {},
                    cachedProfile = { cached },
                    loadProfile = { awaitCancellation() },
                )
            }
        }

        composeRule.onNodeWithTag(PROFILE_HEADER_NAME_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PROFILE_HERO_LOADING_TAG).assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/profile_edit_cached_first_frame_light.png")
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        const val ACCOUNT_ID = "0101010101010101010101010101010101010101010101010101010101010101"
    }
}

private object EmptyProfileEditDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
