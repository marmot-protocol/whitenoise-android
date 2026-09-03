package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual regression coverage for relationship and discovery copy in recipient search rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class RecipientSearchStatusScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Followed contacts use person-centric copy while remote discoveries identify the row as a search result. */
    @Test
    fun relationshipAndSearchResultLabels() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    Column(Modifier.width(360.dp).testTag(ROOT_TAG)) {
                        ContactRow(
                            title = "Ada Lovelace",
                            subtitle = context.getString(R.string.user_search_you_follow),
                            avatarSeed = "ada",
                            avatarUrl = null,
                        )
                        ContactRow(
                            title = "Grace Hopper",
                            subtitle = context.getString(R.string.user_search_result),
                            avatarSeed = "grace",
                            avatarUrl = null,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("You follow").assertIsDisplayed()
        composeRule.onNodeWithText("Search result").assertIsDisplayed()
        composeRule.onNodeWithText("From the network").assertDoesNotExist()
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/recipient_search_status_labels_light.png")
    }

    private companion object {
        const val ROOT_TAG = "recipient-search-status-labels"
    }
}
