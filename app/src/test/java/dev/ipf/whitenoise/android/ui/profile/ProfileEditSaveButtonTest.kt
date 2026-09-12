package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue

/** Exercises the real profile owner under the read/Edit/Save contract without native publication or network calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ProfileEditSaveButtonTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val owner = mutableStateOf(profilePortTestState(app, "alice", ACCOUNT_A))
    private var backs = 0

    /** The standalone Save control continues to respect the authoritative load/submission baseline. */
    @Test
    fun renderedSaveTracksLoadEditsRevertsAndPublishCompletion() {
        val state = ProfileEditSaveState()
        val loaded = profilePortTestMetadata("Alice")
        val current = mutableStateOf(loaded)
        val busy = mutableStateOf(false)
        state.beginLoad(ACCOUNT_A)
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileSaveButton(
                    enabled = !busy.value && state.canSave(ACCOUNT_A, current.value),
                    busy = busy.value,
                    onSave = {},
                )
            }
        }
        val saveButton = composeRule.onNodeWithText(app.getString(R.string.save))

        saveButton.assertIsNotEnabled()
        composeRule.runOnIdle { state.completeLoad(ACCOUNT_A, loaded) }
        saveButton.assertIsNotEnabled()

        composeRule.runOnIdle { current.value = loaded.copy(about = "Changed") }
        saveButton.assertIsEnabled()
        composeRule.runOnIdle { current.value = loaded }
        saveButton.assertIsNotEnabled()

        composeRule.runOnIdle { current.value = loaded.copy(picture = "https://example.com/new-picture.jpg") }
        saveButton.assertIsEnabled()
        composeRule.runOnIdle { current.value = loaded.copy(banner = "https://example.com/new-banner.jpg") }
        saveButton.assertIsEnabled()

        val successfulSubmission = current.value
        composeRule.runOnIdle { busy.value = true }
        saveButton.assertIsNotEnabled()
        composeRule.runOnIdle {
            state.completeSave(ACCOUNT_A, successfulSubmission, succeeded = true)
            busy.value = false
        }
        saveButton.assertIsNotEnabled()

        composeRule.runOnIdle { current.value = successfulSubmission.copy(about = "retry") }
        val failedSubmission = current.value
        composeRule.runOnIdle { busy.value = true }
        saveButton.assertIsNotEnabled()
        composeRule.runOnIdle {
            state.completeSave(ACCOUNT_A, failedSubmission, succeeded = false)
            busy.value = false
        }
        saveButton.assertIsEnabled()

        composeRule.runOnIdle {
            busy.value = true
            current.value = failedSubmission.copy(displayName = "Edited while saving", name = "Edited while saving")
        }
        saveButton.assertIsNotEnabled()
        composeRule.runOnIdle {
            state.completeSave(ACCOUNT_A, failedSubmission, succeeded = true)
            busy.value = false
        }
        saveButton.assertIsEnabled()
    }

    /** Initial content is read-only; Back from an edit discards it before a second Back leaves the destination. */
    @Test
    fun readModeAndEditBackPreserveTheSavedProfile() {
        show()
        composeRule.onNodeWithTag("profile.save").assertDoesNotExist()
        composeRule.onNodeWithTag("profile.name_field").assert(hasSetTextAction().not())
        editName("Draft")
        composeRule.onNodeWithTag("profile.save").assertIsEnabled()
        back()
        composeRule.onNodeWithTag("profile.name_field").performScrollTo().assertTextContains("Alice")
        composeRule.onNodeWithTag("profile.save").assertDoesNotExist()
        assertEquals(0, backs)
        back()
        assertEquals(1, backs)
    }

    /** Save publishes one captured snapshot, freezes field edits during publication, then returns to read mode. */
    @Test
    fun successfulSaveUsesOneSnapshotAndReturnsToReadMode() {
        val submitted = LinkedBlockingQueue<UserProfileMetadataFfi>()
        val complete = CompletableDeferred<Boolean>()
        show(publish = {
            submitted.add(it)
            complete.await()
        })
        editName("Bob")
        composeRule.onNodeWithTag("profile.save").performClick()
        composeRule.waitUntil { submitted.isNotEmpty() }
        composeRule.onNodeWithTag("profile.name_field").assertIsNotEnabled()
        assertEquals("Bob", submitted.peek().displayName)
        complete.complete(true)
        composeRule.waitUntil { composeRule.onAllNodesWithTag("profile.save").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("profile.name_field").assertTextContains("Bob")
        composeRule.onNodeWithTag("profile.edit").performClick()
        composeRule.onNodeWithTag("profile.save").assertIsNotEnabled()
        assertEquals(1, submitted.size)
    }

    /** Failed publication leaves the complete draft available for retry instead of accepting it as a baseline. */
    @Test
    fun failedSaveRetainsTheDraftForRetry() {
        val submitted = LinkedBlockingQueue<UserProfileMetadataFfi>()
        show(publish = {
            submitted.add(it)
            false
        })
        editName("Retry draft")
        composeRule.onNodeWithTag("profile.save").performClick()
        composeRule.waitUntil { submitted.isNotEmpty() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile.save").assertIsEnabled()
        composeRule.onNodeWithTag("profile.name_field").assertTextContains("Retry draft")
    }

    /** Cached fields paint without waiting for refresh and remain authoritative when the refresh fails. */
    @Test
    fun cachedProfileSurvivesBlockedAndFailedRefresh() {
        val refresh = CompletableDeferred<UserProfileMetadataFfi?>()
        show(load = { refresh.await() })
        composeRule.onNodeWithTag("profile.name_field").assertTextContains("Alice")
        composeRule.onNodeWithTag(PROFILE_HERO_LOADING_TAG).assertDoesNotExist()
        refresh.completeExceptionally(IllegalStateException("offline"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile.name_field").assertTextContains("Alice")
        editName("Local edit")
        composeRule.onNodeWithTag("profile.save").assertIsEnabled()
    }

    /** An async refresh replaces untouched fields while preserving text the user changed after loading began. */
    @Test
    fun asyncRefreshMergesUntouchedFieldsWithoutClobberingEdits() {
        val refresh = CompletableDeferred<UserProfileMetadataFfi?>()
        show(load = { refresh.await() })
        editName("Local name")
        refresh.complete(profilePortTestMetadata("Fresh", "Fresh biography"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile.name_field").assertTextContains("Local name")
        composeRule.onNodeWithTag("profile.about_field").performScrollTo().assertTextContains("Fresh biography")
        back()
        composeRule.onNodeWithTag("profile.name_field").performScrollTo().assertTextContains("Fresh")
    }

    /** A pre-save refresh cannot reset the accepted baseline used by the next Edit session. */
    @Test
    fun slowRefreshAfterAcceptedSaveKeepsThePublishedBaseline() {
        val refresh = CompletableDeferred<UserProfileMetadataFfi?>()
        show(load = { refresh.await() }, publish = { true })
        editName("Published Bob")
        composeRule.onNodeWithTag("profile.save").performClick()
        composeRule.waitUntil { composeRule.onAllNodesWithTag("profile.save").fetchSemanticsNodes().isEmpty() }
        refresh.complete(profilePortTestMetadata("Stale Alice", "Stale biography"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile.name_field").assertTextContains("Published Bob")
        composeRule.onNodeWithTag("profile.edit").performClick()
        composeRule.onNodeWithTag("profile.name_field").assertTextContains("Published Bob")
        composeRule.onNodeWithTag("profile.save").assertIsNotEnabled()
        back()
        composeRule.onNodeWithTag("profile.name_field").assertTextContains("Published Bob")
    }

    /** Failure without any trusted baseline may show the editor but cannot publish an invented replacement. */
    @Test
    fun cacheMissFailureKeepsSaveUnqualified() {
        val refresh = CompletableDeferred<UserProfileMetadataFfi?>()
        show(cached = null, load = { refresh.await() })
        composeRule.onNodeWithTag(PROFILE_HERO_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag("profile.edit").assertIsNotEnabled()
        refresh.completeExceptionally(IllegalStateException("offline"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile.edit").performClick()
        composeRule.onNodeWithTag("profile.name_field").performScrollTo().performTextReplacement("Untrusted")
        composeRule.onNodeWithTag("profile.save").assertIsNotEnabled()
    }

    /** Replacing the account clears the old edit session; a late old load cannot overwrite the new cached profile. */
    @Test
    fun accountSwitchClearsDraftAndIgnoresLateOldRefresh() {
        val oldRefresh = CompletableDeferred<UserProfileMetadataFfi?>()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileEditScreen(
                    appState = owner.value,
                    onBack = {},
                    cachedProfile = { profilePortTestMetadata(if (it == ACCOUNT_A) "Alice" else "Bob") },
                    loadProfile = { if (it == ACCOUNT_A) oldRefresh.await() else awaitCancellation() },
                    resolveAddress = { null },
                    resolveLightning = { true },
                )
            }
        }
        editName("Old draft")
        composeRule.runOnIdle { owner.value = profilePortTestState(app, "bob", ACCOUNT_B) }
        oldRefresh.complete(profilePortTestMetadata("Late Alice"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile.save").assertDoesNotExist()
        composeRule.onNodeWithTag("profile.name_field").performScrollTo().assertTextContains("Bob")
    }

    /** A completion owned by the old account cannot close or replace the new account's edit session. */
    @Test
    fun stalePublicationDoesNotChangeNewAccountDraft() {
        val complete = CompletableDeferred<Boolean>()
        val started = CompletableDeferred<Unit>()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileEditScreen(
                    appState = owner.value,
                    onBack = {},
                    cachedProfile = { profilePortTestMetadata(if (it == ACCOUNT_A) "Alice" else "Bob") },
                    loadProfile = { awaitCancellation() },
                    publishProfile = {
                        started.complete(Unit)
                        complete.await()
                    },
                    resolveAddress = { null },
                    resolveLightning = { true },
                )
            }
        }
        editName("Submitted Alice")
        composeRule.onNodeWithTag("profile.save").performClick()
        composeRule.waitUntil { started.isCompleted }
        composeRule.runOnIdle { owner.value = profilePortTestState(app, "bob", ACCOUNT_B) }
        editName("Bob draft")
        complete.complete(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile.name_field").assertTextContains("Bob draft")
        composeRule.onNodeWithTag("profile.save").assertIsEnabled()
    }

    /** Back during Lightning preflight invalidates that attempt before any irreversible publication begins. */
    @Test
    fun backDuringLightningPreflightPreventsPublication() {
        val lightning = CompletableDeferred<Boolean>()
        val started = CompletableDeferred<Unit>()
        var publications = 0
        show(
            cached = profilePortTestMetadata("Alice").copy(lud16 = "alice@example.com"),
            resolveLightning = {
                started.complete(Unit)
                lightning.await()
            },
            publish = {
                publications++
                true
            },
        )
        editName("Cancelled")
        composeRule.onNodeWithTag("profile.save").performClick()
        composeRule.waitUntil { started.isCompleted }
        back()
        lightning.complete(true)
        composeRule.waitForIdle()
        assertEquals(0, publications)
        composeRule.onNodeWithTag("profile.name_field").performScrollTo().assertTextContains("Alice")
    }

    /** Render the production owner with controlled remote boundaries. */
    private fun show(
        cached: UserProfileMetadataFfi? = profilePortTestMetadata("Alice"),
        load: suspend (String) -> UserProfileMetadataFfi? = { awaitCancellation() },
        publish: suspend (UserProfileMetadataFfi) -> Boolean = { true },
        resolveLightning: suspend (String) -> Boolean = { true },
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileEditScreen(
                    owner.value,
                    { backs++ },
                    { cached },
                    load,
                    publish,
                    resolveAddress = { null },
                    resolveLightning = resolveLightning,
                )
            }
        }
    }

    /** Start an edit through the public action before entering text. */
    private fun editName(value: String) {
        composeRule.onNodeWithTag("profile.edit").performClick()
        composeRule.onNodeWithTag("profile.name_field").performScrollTo().performTextReplacement(value)
    }

    /** Uses the same app-bar Back action as a user. */
    private fun back() {
        composeRule.onNodeWithContentDescription("Back").performClick()
    }

    private companion object {
        const val ACCOUNT_A = "0101010101010101010101010101010101010101010101010101010101010101"
        const val ACCOUNT_B = "0202020202020202020202020202020202020202020202020202020202020202"
    }
}

/** Local account model only; this fixture never creates an identity or contacts a native runtime. */
internal fun profilePortTestState(
    context: Context,
    label: String,
    account: String,
): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore.forContext(context),
        accountIdHexResolver = { null },
        accounts =
            listOf(
                AccountSummaryFfi(
                    label = label,
                    accountIdHex = account,
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                ),
            ),
        activeAccountRef = label,
    )

/** Complete metadata used to distinguish untouched loaded fields from user edits. */
internal fun profilePortTestMetadata(
    name: String,
    about: String = "A public biography",
): UserProfileMetadataFfi = profileEditMetadata(name, about, "", "", "", "")
