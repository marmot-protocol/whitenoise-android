package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production reader/invalidation boundary used by both Settings and the profile editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileReadinessReconciliationTest {
    @get:Rule val composeRule = createComposeRule()

    /** A late read from a previous account must not change the current account's readiness. */
    @Test fun accountSwitchRejectsLateRead() {
        val account = mutableStateOf("alice")
        val oldRead = CompletableDeferred<UserProfileMetadataFfi?>()
        val empty = profileEditMetadata("", "", "", "", "", "")
        val owner = Any()
        composeRule.setContent {
            val readiness =
                rememberProfileReadiness(owner, account.value, 0, { null }) { id ->
                    if (id == "alice") withContext(NonCancellable) { oldRead.await() } else empty
                }
            Text(stringResource(readiness.summary))
        }
        composeRule.onNodeWithText("Loading profile…").assertIsDisplayed()
        composeRule.runOnIdle { account.value = "bob" }
        composeRule.onNodeWithText("Set up your profile").assertIsDisplayed()
        oldRead.complete(empty.copy(name = "Alice"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Set up your profile").assertIsDisplayed()
    }

    /** Publication invalidates the native projection; drafts and failed readbacks keep the prior state. */
    @Test fun readinessChangesOnlyAfterAuthoritativeReadback() {
        val empty = profileEditMetadata("", "", "", "", "", "")
        var native: UserProfileMetadataFfi? = empty
        val revision = mutableIntStateOf(0)
        val owner = Any()
        composeRule.setContent {
            val readiness = rememberProfileReadiness(owner, "alice", revision.intValue, { null }) { native }
            Text(stringResource(readiness.summary))
        }
        composeRule.onNodeWithText("Set up your profile").assertIsDisplayed()
        composeRule.runOnIdle {
            native = null
            revision.intValue++
        }
        composeRule.onNodeWithText("Set up your profile").assertIsDisplayed()
        composeRule.runOnIdle {
            native = empty.copy(name = "Alice")
            revision.intValue++
        }
        composeRule.onNodeWithText("Profile ready · Optional details available").assertIsDisplayed()
        composeRule.runOnIdle {
            native = empty
            revision.intValue++
        }
        composeRule.onNodeWithText("Set up your profile").assertIsDisplayed()
    }
}
