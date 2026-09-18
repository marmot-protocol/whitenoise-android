package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * The name field offers two opposite actions. Suggesting a name and putting back the one already
 * published are different intentions, and before this the only ways back from a suggestion were
 * retyping the name or discarding the whole form.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ProfileEditNameActionsTest {
    @get:Rule val composeRule = createComposeRule()

    /** With the field untouched there is nothing to restore, so the action stays out of reach. */
    @Test
    fun restoreIsDisabledWhileTheNameMatchesTheSavedOne() {
        renderEditing(saved = SAVED_NAME)
        composeRule.onNodeWithTag("profile.restore_name").assertIsDisplayed().assertIsNotEnabled()
    }

    /** A suggestion makes the field differ, which is exactly when restoring becomes meaningful. */
    @Test
    fun suggestingANameEnablesRestore() {
        renderEditing(saved = SAVED_NAME)
        composeRule.onNodeWithTag("profile.suggest_name").performClick()
        composeRule.onNodeWithText(SUGGESTED_NAME).assertExists()
        composeRule.onNodeWithTag("profile.restore_name").assertIsEnabled()
    }

    /** Restoring puts the published name back and disables itself again, without touching the rest. */
    @Test
    fun restoringReturnsTheSavedNameAndDisablesItself() {
        renderEditing(saved = SAVED_NAME)
        composeRule.onNodeWithTag("profile.suggest_name").performClick()
        composeRule.onNodeWithTag("profile.restore_name").performClick()
        composeRule.onNodeWithText(SAVED_NAME).assertExists()
        composeRule.onNodeWithTag("profile.restore_name").assertIsNotEnabled()
    }

    /** Both actions stay available to a reader who is not editing nothing: they are distinct targets. */
    @Test
    fun theTwoActionsAreSeparateTargets() {
        renderEditing(saved = SAVED_NAME)
        val restore = composeRule.onNodeWithTag("profile.restore_name").fetchSemanticsNode().boundsInRoot
        val suggest = composeRule.onNodeWithTag("profile.suggest_name").fetchSemanticsNode().boundsInRoot
        val apart = restore.right <= suggest.left + 1f || suggest.right <= restore.left + 1f
        assert(apart) { "the two name actions overlap: restore=$restore suggest=$suggest" }
    }

    private fun renderEditing(saved: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "0101010101010101010101010101010101010101010101010101010101010101"
        val appState = profilePortTestState(context, "alice", account, marmot = pseudonymEngine())
        val cached = profilePortTestMetadata(saved, "A public profile shared with my contacts.")
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileEditScreen(
                    appState,
                    {},
                    { cached },
                    { awaitCancellation() },
                    { true },
                    resolveAddress = { account },
                    resolveLightning = { true },
                )
            }
        }
        composeRule.onNodeWithTag("profile.edit").performClick()
    }

    private companion object {
        const val SAVED_NAME = "Alice"
        const val SUGGESTED_NAME = "Quiet Otter"
    }
}

/** An engine that only answers the pseudonym draw, so the suggested name is the same every run. */
private fun pseudonymEngine(): MarmotInterface =
    Proxy.newProxyInstance(
        MarmotInterface::class.java.classLoader,
        arrayOf(MarmotInterface::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "randomProfilePseudonym" -> "Quiet Otter"
            "toString" -> "PseudonymEngineFixture"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw UnsupportedOperationException("Unused native test call: ${method.name}")
        }
    } as MarmotInterface
