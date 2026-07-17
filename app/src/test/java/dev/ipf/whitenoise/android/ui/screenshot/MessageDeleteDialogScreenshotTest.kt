package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageDeleteDialogContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baselines for the unified delete dialog: both scopes offered, the
 * compact delete-for-me-only variant, the moderator copy for removing another
 * member's group message, all three themes, a doubled font scale, and RTL
 * with a long sender name. The dialog content is captured directly (the dialog
 * wrapper renders in its own window, which this harness can't capture).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageDeleteDialogScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bothScopes = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true)
    private val localOnly = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = false)

    @Composable
    private fun DialogVariant(
        capability: MessageDeleteCapability,
        mine: Boolean,
        senderDisplayName: String = "Member",
    ) {
        Surface {
            MessageDeleteDialogContent(
                capability = capability,
                mine = mine,
                senderDisplayName = senderDisplayName,
                deleteInFlight = false,
                onDeleteForEveryone = {},
                onDeleteForMe = {},
                onCancel = {},
            )
        }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    @Test
    fun bothScopesLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) { DialogVariant(capability = bothScopes, mine = true) }
        }
        capture("message_delete_dialog_both_light")
    }

    @Test
    fun bothScopesDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) { DialogVariant(capability = bothScopes, mine = true) }
        }
        capture("message_delete_dialog_both_dark")
    }

    @Test
    fun bothScopesAmoled() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) { DialogVariant(capability = bothScopes, mine = true) }
        }
        capture("message_delete_dialog_both_amoled")
    }

    @Test
    fun deleteForMeOnlyStaysCompact() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) { DialogVariant(capability = localOnly, mine = false) }
        }
        capture("message_delete_dialog_local_only_light")
    }

    @Test
    fun moderatorCopyForAnotherMembersMessage() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                DialogVariant(
                    capability = bothScopes,
                    mine = false,
                    senderDisplayName = "Maple Bat",
                )
            }
        }
        capture("message_delete_dialog_moderator_dark")
    }

    @Test
    fun largeFontScaleKeepsChoicesReadable() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale = 2f),
                ) {
                    DialogVariant(capability = bothScopes, mine = true)
                }
            }
        }
        capture("message_delete_dialog_font_scale_2x_light")
    }

    @Test
    fun rtlWithLongSenderNameWraps() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    DialogVariant(
                        capability = bothScopes,
                        mine = false,
                        senderDisplayName = "عضو المجموعة ذو الاسم الطويل جدا للتجربة",
                    )
                }
            }
        }
        capture("message_delete_dialog_rtl_long_name_light")
    }
}
