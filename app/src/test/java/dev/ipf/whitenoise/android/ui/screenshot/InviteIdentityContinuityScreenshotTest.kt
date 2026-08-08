package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.state.conversationIdentityProjection
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class InviteIdentityContinuityScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun acceptedInviteKeepsInviterIdentityInConversationHeader() {
        val projection =
            conversationIdentityProjection(
                members = emptyList(),
                activeAccountIdHex = "self",
                acceptedInvitePeerAccount = "alice-account",
            )
        val title =
            GroupProjector.displayTitle(
                name = "",
                pendingInviteAccount = null,
                groupIdHex = "group",
                otherMemberAccount = projection.otherMemberAccount,
                memberCount = projection.memberCount,
                memberTitle = { "Alice" },
            )

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxWidth().testTag(TAG)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {}) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Avatar(
                                title = title,
                                seed = projection.otherMemberAccount.orEmpty(),
                                size = 36.dp,
                            )
                            Text(title, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/invite_identity_continuity_dark.png")
    }

    private companion object {
        const val TAG = "invite-identity-continuity"
    }
}
