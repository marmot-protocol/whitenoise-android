package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

/** The two entry actions remain usable at the safe bottom edge, including short RTL and large-text fixtures. */
internal fun ComposeContentTestRule.assertWelcomeActionsAtBottom() {
    val signIn = onNodeWithTag("onboarding.welcome.sign_in").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    val signUp = onNodeWithTag("onboarding.welcome.sign_up").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    val signInBounds = signIn.getUnclippedBoundsInRoot()
    val signUpBounds = signUp.getUnclippedBoundsInRoot()
    val bottomGap = onRoot().getUnclippedBoundsInRoot().bottom - signUpBounds.bottom
    assertTrue("Entry actions must not overlap", signInBounds.bottom <= signUpBounds.top)
    assertTrue("Sign Up must fit within the viewport", bottomGap >= 0.dp)
    assertTrue("Sign Up must stay at the bottom above padding/system navigation", bottomGap <= 48.dp)
}
