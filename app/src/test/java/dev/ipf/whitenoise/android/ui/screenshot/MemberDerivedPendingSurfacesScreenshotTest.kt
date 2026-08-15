package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.ui.chats.ChatFolderChipModel
import dev.ipf.whitenoise.android.ui.chats.ChatListFilterChips
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationMembershipPendingBar
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
class MemberDerivedPendingSurfacesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun memberDerivedPendingLight() {
        render(darkTheme = false, rosterState = GroupRosterLoadState.LOADING)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/member_derived_pending_light.png")
    }

    @Test
    fun memberDerivedPendingDark() {
        render(darkTheme = true, rosterState = GroupRosterLoadState.LOADING)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/member_derived_pending_dark.png")
    }

    @Test
    fun memberDerivedFailedDarkLargeText() {
        render(darkTheme = true, rosterState = GroupRosterLoadState.FAILED, fontScale = 1.5f)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/member_derived_failed_dark_large.png")
    }

    private fun render(
        darkTheme: Boolean,
        rosterState: GroupRosterLoadState,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column {
                            ChatListFilterChips(
                                chips =
                                    listOf(
                                        ChatFolderChipModel(
                                            folderId = "people",
                                            systemKind = null,
                                            customLabel = "People",
                                            trailingCount = 0,
                                            pending = true,
                                        ),
                                    ),
                                selectedFolderId = null,
                                onSelect = {},
                            )
                            Spacer(Modifier.weight(1f))
                            ConversationMembershipPendingBar(
                                rosterState = rosterState,
                                onRetry = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
