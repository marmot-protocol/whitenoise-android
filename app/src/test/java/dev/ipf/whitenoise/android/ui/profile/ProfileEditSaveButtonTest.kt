package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ProfileEditSaveButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun renderedSaveTracksLoadEditsRevertsAndPublishCompletion() {
        val state = ProfileEditSaveState()
        val loaded = metadata(displayName = "Alice")
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
        val saveButton = composeRule.onNodeWithText(context.getString(R.string.save))

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

    @Test
    fun screenUsesControlledLoadAndSubmittedPublishSnapshot() {
        val loaded = metadata(displayName = "Alice")
        val loadCompletion = CompletableDeferred<UserProfileMetadataFfi?>()
        val publishCalls = LinkedBlockingQueue<PublishCall>()
        val screenAppState = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileEditScreen(
                    appState = screenAppState,
                    onBack = {},
                    loadProfile = { loadCompletion.await() },
                    publishProfile = { submitted ->
                        val call = PublishCall(submitted)
                        publishCalls.put(call)
                        call.completion.await()
                    },
                )
            }
        }
        val saveButton = composeRule.onNodeWithText(context.getString(R.string.save))
        val displayNameMatcher = hasSetTextAction() and hasText(context.getString(R.string.display_name))
        val displayNameField = composeRule.onNode(displayNameMatcher)

        saveButton.assertIsNotEnabled()
        loadCompletion.complete(loaded)
        composeRule.waitForIdle()
        saveButton.assertIsNotEnabled()
        composeRule.onNode(hasScrollAction()).performScrollToNode(displayNameMatcher)

        displayNameField.performTextReplacement("Bob")
        saveButton.assertIsEnabled()
        displayNameField.performTextReplacement("Alice")
        saveButton.assertIsNotEnabled()
        displayNameField.performTextReplacement("Bob")
        saveButton.assertIsEnabled().performClick()
        val publish = requireNotNull(publishCalls.poll(5, TimeUnit.SECONDS))
        saveButton.assertIsNotEnabled()

        displayNameField.performTextReplacement("Carol")
        publish.completion.complete(true)
        composeRule.waitForIdle()

        assertEquals("Bob", publish.metadata.displayName)
        saveButton.assertIsEnabled().performClick()
        val failedPublish = requireNotNull(publishCalls.poll(5, TimeUnit.SECONDS))
        failedPublish.completion.complete(false)
        composeRule.waitForIdle()

        assertEquals("Carol", failedPublish.metadata.displayName)
        saveButton.assertIsEnabled()
    }

    @Test
    fun screenIgnoresStalePublishCompletionAfterAccountSwitch() {
        val currentAppState = mutableStateOf(appState(ACCOUNT_A_REF, ACCOUNT_A_ID))
        val loadCalls = LinkedBlockingQueue<LoadCall>()
        val publishCalls = LinkedBlockingQueue<PublishCall>()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileEditScreen(
                    appState = currentAppState.value,
                    onBack = {},
                    loadProfile = { accountId ->
                        val call = LoadCall(accountId)
                        loadCalls.put(call)
                        call.completion.await()
                    },
                    publishProfile = { submitted ->
                        val call = PublishCall(submitted)
                        publishCalls.put(call)
                        call.completion.await()
                    },
                )
            }
        }
        val saveButton = composeRule.onNodeWithText(context.getString(R.string.save))
        val displayNameMatcher = hasSetTextAction() and hasText(context.getString(R.string.display_name))
        val displayNameField = composeRule.onNode(displayNameMatcher)

        val accountALoad = requireNotNull(loadCalls.poll(5, TimeUnit.SECONDS))
        assertEquals(ACCOUNT_A_ID, accountALoad.accountId)
        accountALoad.completion.complete(metadata(displayName = "Alice"))
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollAction()).performScrollToNode(displayNameMatcher)
        displayNameField.performTextReplacement("Alicia")
        saveButton.assertIsEnabled().performClick()
        val accountAPublish = requireNotNull(publishCalls.poll(5, TimeUnit.SECONDS))

        composeRule.runOnIdle {
            currentAppState.value = appState(ACCOUNT_B_REF, ACCOUNT_B_ID)
        }
        composeRule.waitForIdle()
        val accountBLoad = requireNotNull(loadCalls.poll(5, TimeUnit.SECONDS))
        assertEquals(ACCOUNT_B_ID, accountBLoad.accountId)
        accountBLoad.completion.complete(metadata(displayName = "Bob"))
        composeRule.waitForIdle()
        saveButton.assertIsNotEnabled()

        composeRule.onNode(hasScrollAction()).performScrollToNode(displayNameMatcher)
        displayNameField.performTextReplacement("Bobby")
        saveButton.assertIsEnabled()
        accountAPublish.completion.complete(true)
        composeRule.waitForIdle()

        saveButton.assertIsEnabled()
        displayNameField.performTextReplacement("Bob")
        saveButton.assertIsNotEnabled()
    }

    private fun metadata(displayName: String): UserProfileMetadataFfi =
        profileEditMetadata(
            displayName = displayName,
            about = "",
            picture = "",
            banner = "",
            nip05 = "",
            lud16 = "",
        )

    private fun appState(
        accountRef: String = ACCOUNT_A_REF,
        accountId: String = ACCOUNT_A_ID,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(ProfileEditDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = accountRef,
                        accountIdHex = accountId,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = accountRef,
        )

    private data class LoadCall(
        val accountId: String,
        val completion: CompletableDeferred<UserProfileMetadataFfi?> = CompletableDeferred(),
    )

    private data class PublishCall(
        val metadata: UserProfileMetadataFfi,
        val completion: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_A_REF = "alice"
        const val ACCOUNT_A_ID = "0101010101010101010101010101010101010101010101010101010101010101"
        const val ACCOUNT_B_REF = "bob"
        const val ACCOUNT_B_ID = "0202020202020202020202020202020202020202020202020202020202020202"
    }
}

private class ProfileEditDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
