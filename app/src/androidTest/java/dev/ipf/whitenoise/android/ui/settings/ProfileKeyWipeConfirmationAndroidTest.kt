package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Bitmap
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
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
        confirm.assertIsNotEnabled()
        assertActionsInWindow(cancel)
        saveScreenshot(if (largeRtl) "amoled_200_percent_rtl_empty" else "light_normal_empty")
        field.performTextReplacement(keyword + " wrong")
        confirm.assertIsNotEnabled()
        composeRule.onNodeWithText(cancel).performClick()
        composeRule.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, confirmed)
        }
        // Inert callbacks deliberately leave the same fixture open for the independent confirm action.
        field.performTextReplacement("  " + keyword.lowercase(Locale.ROOT) + "  ")
        confirm.assertIsEnabled()
        assertActionsInWindow(cancel)
        saveScreenshot(if (largeRtl) "amoled_200_percent_rtl_enabled" else "light_normal_enabled")
        confirm.performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    private fun assertActionsInWindow(cancel: String) {
        val window =
            composeRule.runOnIdle {
                WindowInspector.getGlobalWindowViews().single { view ->
                    view !== composeRule.activity.window.decorView && view.isShown && view.hasWindowFocus()
                }
            }
        val dialog = composeRule.onNode(isDialog()).getUnclippedBoundsInRoot()
        val density = composeRule.density.density
        assertTrue("Dialog exceeds actual window", (dialog.right - dialog.left).value * density <= window.width + 1)
        assertTrue("Dialog exceeds actual window", (dialog.bottom - dialog.top).value * density <= window.height + 1)
        val actions = listOf(composeRule.onNodeWithTag("profile_keys.wipe_confirm"), composeRule.onNodeWithText(cancel))
        for (action in actions) {
            val bounds = action.assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(
                "Action has no visible area: $bounds",
                (bounds.right - bounds.left).value > 0 && (bounds.bottom - bounds.top).value > 0,
            )
            assertTrue(
                "Action outside actual dialog window: $bounds",
                bounds.left >= dialog.left && bounds.top >= dialog.top,
            )
            assertTrue(
                "Action outside actual dialog window: $bounds",
                bounds.right <= dialog.right && bounds.bottom <= dialog.bottom,
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
