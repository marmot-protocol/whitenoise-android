package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteBreakdown
import dev.ipf.whitenoise.android.ui.conversation.messages.BatchMessageDeleteDialog
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageDeleteDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baselines for the unified delete dialog: both scopes offered, the
 * compact delete-for-me-only variant, the moderator copy for removing another
 * member's group message, all three themes, a doubled font scale, and RTL
 * with a long sender name. Captures the actual Material dialog window.
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
        MessageDeleteDialog(
            capability = capability,
            mine = mine,
            senderDisplayName = senderDisplayName,
            deleteInFlight = false,
            onDeleteForEveryone = {},
            onDeleteForMe = {},
            onDismissRequest = {},
        )
    }

    private fun capture(name: String) {
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/$name.png")
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
        RuntimeEnvironment.setFontScale(2f)
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = false) { DialogVariant(capability = bothScopes, mine = true) }
            }
            capture("message_delete_dialog_font_scale_2x_light")
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
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

    @Test
    fun batchMixedLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                BatchMessageDeleteDialog(
                    selectedCount = 3,
                    breakdown = BatchDeleteBreakdown(deleteForEveryone = 2, hideLocally = 1),
                    deleteInFlight = false,
                    onDeleteForEveryone = {},
                    onDeleteForMe = {},
                    onDismissRequest = {},
                )
            }
        }
        capture("message_delete_dialog_batch_mixed_light")
    }
}
