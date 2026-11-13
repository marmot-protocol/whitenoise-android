package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Real platform dialog coverage with local counters only; never starts account state or wipes data. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ProfileKeyWipeConfirmationAndroidTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lightKeywordGateAndSeparateActions() = exerciseDialog(largeRtl = false)

    @Test
    fun amoled200PercentRtlKeywordGateAndSeparateActions() = exerciseDialog(largeRtl = true)

    private fun exerciseDialog(largeRtl: Boolean) {
        val input = mutableStateOf("")
        var confirmed = 0
        var dismissed = 0
        val context = composeRule.activity
        val cancel = context.getString(R.string.cancel)
        val keyword = context.getString(R.string.sign_out_and_wipe_confirm_keyword)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = largeRtl, amoled = largeRtl, fontScale = if (largeRtl) 2f else 1f) {
                    ProfileKeyWipeConfirmationDialog(
                        input = input.value,
                        onInputChange = { input.value = it },
                        onConfirm = { confirmed++ },
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        val confirm = composeRule.onNodeWithTag("profile_keys.wipe_confirm")
        val field = composeRule.onNodeWithText(context.getString(R.string.sign_out_and_wipe_confirm_field_label))
        val instruction =
            composeRule.onNodeWithText(context.getString(R.string.sign_out_and_wipe_confirm_instruction, keyword))
        confirm.assertIsNotEnabled()
        instruction.performScrollTo().assertIsDisplayed()
        assertInVisibleWindow(instruction)
        field.performScrollTo().assertIsDisplayed()
        assertInVisibleWindow(field)
        assertActionsInWindow(cancel)
        saveScreenshot(if (largeRtl) "amoled_200_percent_rtl_empty" else "light_normal_empty")
        field.performClick()
        awaitKeyboard()
        field.performTextInput(keyword + " wrong")
        confirm.assertIsNotEnabled()
        assertActionsInWindow(cancel)
        composeRule.onNodeWithText(cancel).performClick()
        composeRule.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, confirmed)
        }
        // Inert callbacks deliberately leave the same fixture open for the independent confirm action.
        field.performScrollTo().performClick()
        awaitKeyboard()
        field.performTextClearance()
        field.performTextInput("  " + keyword.lowercase(Locale.ROOT) + "  ")
        confirm.assertIsEnabled()
        field.performScrollTo()
        assertInVisibleWindow(field)
        assertActionsInWindow(cancel)
        saveScreenshot(if (largeRtl) "amoled_200_percent_rtl_enabled" else "light_normal_enabled")
        confirm.performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    private fun dialogWindow() =
        WindowInspector.getGlobalWindowViews().single { view ->
            view !== composeRule.activity.window.decorView && view.isShown && view.hasWindowFocus()
        }

    private fun awaitKeyboard() {
        composeRule.waitUntil(10_000) {
            composeRule.runOnUiThread {
                ViewCompat.getRootWindowInsets(dialogWindow())?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertActionsInWindow(cancel: String) {
        assertInVisibleWindow(composeRule.onNodeWithTag("profile_keys.wipe_confirm"))
        assertInVisibleWindow(composeRule.onNodeWithText(cancel))
    }

    /** Compare full semantics bounds with screen coordinates, excluding system bars and the real IME. */
    private fun assertInVisibleWindow(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val fullBounds = node.getUnclippedBoundsInRoot()
        val density = composeRule.density.density
        assertTrue(
            "Node is clipped: $bounds",
            bounds.width + 1 >= (fullBounds.right - fullBounds.left).value * density,
        )
        assertTrue(
            "Node is clipped: $bounds",
            bounds.height + 1 >= (fullBounds.bottom - fullBounds.top).value * density,
        )
        composeRule.runOnUiThread {
            val window = dialogWindow()
            val visible = Rect()
            window.getWindowVisibleDisplayFrame(visible)
            val location = IntArray(2)
            window.getLocationOnScreen(location)
            assertTrue(
                "Node outside visible screen: $bounds, $visible",
                bounds.left + location[0] >= visible.left - 1,
            )
            assertTrue(
                "Node outside visible screen: $bounds, $visible",
                bounds.top + location[1] >= visible.top - 1,
            )
            assertTrue(
                "Node outside visible screen: $bounds, $visible",
                bounds.right + location[0] <= visible.right + 1,
            )
            assertTrue(
                "Node behind keyboard/screen edge: $bounds",
                bounds.bottom + location[1] <= visible.bottom + 1,
            )
        }
    }

    /** AGP pulls additionalTestOutputDir into the workflow's instrumented reports artifact. */
    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = output ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)).path
        val directory = File(root, "profile-key-wipe-confirmation")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap =
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Actual window screenshot unavailable" }
        try {
            File(directory, "$name.png").outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
