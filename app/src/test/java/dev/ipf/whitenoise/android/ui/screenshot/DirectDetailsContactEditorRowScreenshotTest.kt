package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.group.DirectDetailsContactEditorRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel baseline for the DM details nickname/notes action row with a saved nickname. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DirectDetailsContactEditorRowScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun directDetailsContactEditorRowWithNicknameLight() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val accounts =
            listOf(
                AccountSummaryFfi(
                    label = ACCOUNT_REF,
                    accountIdHex = SELF_HEX,
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                ),
            )
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(EmptyDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = accounts,
                activeAccountRef = ACCOUNT_REF,
                profileReader = { null },
                profileDisplayNameReader = { null },
            )
        appState.setContactNickname(PEER_HEX, "Alice")

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                    DirectDetailsContactEditorRow(
                        appState = appState,
                        groupIdHex = GROUP_HEX,
                        peerAccountIdHex = PEER_HEX,
                        isDm = true,
                        readOnlyInvite = false,
                        dmPeerNpub = "npub1peer",
                        activeAccountRef = ACCOUNT_REF,
                        accounts = accounts,
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/direct_details_contact_editor_row_nickname_light.png")
    }

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val TAG = "direct-details-contact-editor-row"
        const val ACCOUNT_REF = "account-a"
        const val SELF_HEX = "self-a"
        const val PEER_HEX = "peer-a"
        const val GROUP_HEX = "group-dm"
    }
}
