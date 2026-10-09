package dev.ipf.whitenoise.android.ui.profile

import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual private-details modal and its save callback against a real docked platform keyboard. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ContactPrivateDetailsImeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun saveAndCancelStayAboveTheRealKeyboardWhileNotesRemainEditable() {
        var saved: Pair<String, String>? = null
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactPrivateDetailsDialog(
                    profileName = "Test contact",
                    initialNickname = "Private nickname",
                    initialNotes = "Private notes\n".repeat(15),
                    onDismiss = {},
                    onSave = { nickname, notes -> saved = nickname to notes },
                    securePolicy = SecureFlagPolicy.SecureOff,
                )
            }
        }
        composeRule.onNodeWithTag("person_profile.notes").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { keyboardBounds() != null }
        composeRule
            .onNodeWithTag("person_profile.notes")
            .performTextReplacement("Still editable with the keyboard open")
        val safeBottom = requireNotNull(keyboardBounds())
        for (tag in listOf("person_profile.private_cancel", "person_profile.private_save")) {
            val action = composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("$tag overlaps the keyboard", action.boundsInWindow.bottom <= safeBottom + 2)
        }
        composeRule.onNodeWithTag("person_profile.private_save").performClick()
        assertEquals("Private nickname" to "Still editable with the keyboard open", saved)
    }

    private fun keyboardBounds(): Int? =
        composeRule.runOnIdle {
            WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { view ->
                val insets = ViewCompat.getRootWindowInsets(view)
                val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                if (view !== composeRule.activity.window.decorView && bottom > 0) view.height - bottom else null
            }
        }
}
