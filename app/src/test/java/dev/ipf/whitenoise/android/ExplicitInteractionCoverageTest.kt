package dev.ipf.whitenoise.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Explicit user actions must use their real Android boundary, not unrelated platform hints. */
class ExplicitInteractionCoverageTest {
    @Test
    fun signupUsesAppDefaultAvailabilityWithoutChangingBackgroundValidation() {
        val onboarding = source("ui/onboarding/OnboardingScreen.kt")
        val signup = source("ui/onboarding/SignUpScreen.kt")
        val app = source("ui/WhiteNoiseApp.kt")
        assertTrue("hasActiveNetwork: () -> Boolean = appState::hasActiveNetwork" in onboarding)
        assertTrue("hasActiveNetwork = appState::hasActiveNetwork" in app)
        assertTrue("if (!hasActiveNetwork())" in signup)
        assertFalse("hasValidatedInternet" in onboarding)
        assertFalse("hasValidatedInternet" in signup)
        val state = source("state/AppState.kt")
        assertTrue("fun hasActiveNetwork(): Boolean = hasActiveNetworkSnapshot" in state)
        assertTrue("hasActiveNetworkSnapshot = activeDefaultNetwork.seed(network?.networkHandle)" in state)
        assertTrue("hasValidatedPhysicalNetwork = validatedInternetNetworks.hasValidatedInternet()" in state)
    }

    @Test
    fun eventReaderWiresPointerAndAccessibleCopyToTheExistingSafeHitTest() {
        val reader = source("ui/conversation/nostr/NostrEventReaderEmoji.kt")
        assertTrue("remember(block) { MarkdownLinkLayoutRegistry() }" in reader)
        assertTrue("readerTextSelectionLongPress(block)" in reader)
        assertTrue("markdownLinkDestinationAt(layouts.values, position)" in reader)
        assertTrue("onLinkTextLayoutChanged = layouts.report" in reader)
        assertTrue("onCopyLink = copyLink" in reader)
        assertTrue("onWebLinkActivated = onWebLinkActivated" in reader)
    }

    private fun source(relative: String) = File("src/main/java/dev/ipf/whitenoise/android/$relative").readText()
}
