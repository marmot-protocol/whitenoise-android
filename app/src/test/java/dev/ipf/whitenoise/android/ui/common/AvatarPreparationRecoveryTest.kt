package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupCreationSession
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupDraft
import dev.ipf.whitenoise.android.ui.chats.newchat.rememberNewGroupDraft
import dev.ipf.whitenoise.android.ui.group.GroupAvatarUploadAttempt
import dev.ipf.whitenoise.android.ui.navigation.rememberProtectedShellSavedState
import dev.ipf.whitenoise.android.ui.profile.ProfileImageDrafts
import dev.ipf.whitenoise.android.ui.profile.ProfileImageDraftsSaver
import dev.ipf.whitenoise.android.ui.profile.ProfileImageTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real saved-owner disposal with the profile coroutine and native group owner boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AvatarPreparationRecoveryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun profileAcceptedCropInterruptedByLockIsActionable() = exercise(Surface.Profile)

    @Test fun groupAcceptedCropInterruptedByLockIsActionable() = exercise(Surface.Group)

    @Test fun groupAcceptedCropUploadInterruptedByLockIsActionable() = exercise(Surface.Group, suspendUpload = true)

    @Test fun knownGroupPublishFailureDoesNotBecomeALaterInterruption() {
        exercise(Surface.Group, completeBeforeLock = true, publishFailure = true)
    }

    @Test fun deliberateProfileCancellationDoesNotBecomeALaterInterruption() {
        exercise(Surface.Profile, deliberateCancel = true)
    }

    @Test fun deliberateGroupCancellationDoesNotBecomeALaterInterruption() {
        exercise(Surface.Group, deliberateCancel = true)
    }

    @Test fun deliberateNewGroupCancellationDoesNotBecomeALaterInterruption() {
        exercise(Surface.NewGroup, deliberateCancel = true)
    }

    @Test fun newGroupAcceptedCropInterruptedByLockRequiresReselection() = exercise(Surface.NewGroup)

    @Test fun profileKnownSuccessDoesNotReportInterruption() = exercise(Surface.Profile, completeBeforeLock = true)

    @Test fun groupKnownSuccessDoesNotReportInterruption() = exercise(Surface.Group, completeBeforeLock = true)

    @Test fun newGroupKnownSuccessDoesNotReportInterruption() = exercise(Surface.NewGroup, completeBeforeLock = true)

    @Test fun profileRetiredAccountGetsNoNoticeOrLateImage() = exercise(Surface.Profile, replaceAccount = true)

    @Test fun groupRetiredAccountGetsNoNoticeOrLateImage() = exercise(Surface.Group, replaceAccount = true)

    @Test fun newGroupRetiredAccountGetsNoNoticeOrLateImage() = exercise(Surface.NewGroup, replaceAccount = true)

    private fun exercise(
        surface: Surface,
        completeBeforeLock: Boolean = false,
        replaceAccount: Boolean = false,
        suspendUpload: Boolean = false,
        publishFailure: Boolean = false,
        deliberateCancel: Boolean = false,
    ) {
        AvatarRecoveryFixture(
            composeRule,
            surface,
            AvatarRecoveryScenario(completeBeforeLock, replaceAccount, suspendUpload, publishFailure, deliberateCancel),
        ).exercise()
    }
}

private enum class Surface {
    Profile,
    Group,
    NewGroup,
}

private data class AvatarRecoveryScenario(
    val completeBeforeLock: Boolean,
    val replaceAccount: Boolean,
    val suspendUpload: Boolean,
    val publishFailure: Boolean,
    val deliberateCancel: Boolean,
)

private class AvatarRecoveryFixture(
    private val composeRule: ComposeContentTestRule,
    private val surface: Surface,
    private val scenario: AvatarRecoveryScenario,
) {
    private val completeBeforeLock get() = scenario.completeBeforeLock
    private val replaceAccount get() = scenario.replaceAccount
    private val suspendUpload get() = scenario.suspendUpload
    private val publishFailure get() = scenario.publishFailure
    private val deliberateCancel get() = scenario.deliberateCancel
    private val locked = mutableStateOf(false)
    private val account = mutableStateOf("original")
    private val load = CompletableDeferred<ImageUploadDraft>()
    private val retainedScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var acceptCrop: (() -> Unit)? = null
    private var cancelCrop: (() -> Unit)? = null
    private var knownPublishFailure = false
    private var notices = 0
    private val publishedGroupImage = mutableStateOf("previous")
    private var image = "previous"
    private var needsReselection = false

    fun exercise() {
        try {
            mount()
            completeBeforeSuspension()
            suspendAndDeliver()
            assertRecovered()
        } finally {
            retainedScope.cancel()
        }
    }

    private fun mount() {
        composeRule.setContent {
            val protected = rememberProtectedShellSavedState(account.value, 1, suspended = locked.value)
            if (!locked.value) {
                protected.holder.SaveableStateProvider(protected.key) {
                    val capturedAccount = account.value
                    var profileImages by rememberSaveable(stateSaver = ProfileImageDraftsSaver) {
                        mutableStateOf(ProfileImageDrafts(picture = "previous"))
                    }
                    val newGroupDraft = rememberNewGroupDraft()
                    val session = remember { GroupCreationSession { account.value == capturedAccount } }
                    DisposableEffect(session) { onDispose { session.dispose() } }
                    val profileScope = rememberCoroutineScope()
                    val recovery = rememberRecovery(capturedAccount, newGroupDraft)
                    cancelCrop = {
                        recovery.settled()
                        session.dispose()
                    }
                    acceptCrop = {
                        recovery.accepted()
                        val scope = if (surface == Surface.Profile) profileScope else retainedScope
                        scope.launch {
                            when (surface) {
                                Surface.Profile -> {
                                    load.await()
                                    if (session.isCurrent()) {
                                        profileImages =
                                            profileImages.withUploadedImage(
                                                ProfileImageTarget.Picture,
                                                "updated",
                                                capturedAccount,
                                                account.value,
                                            )
                                        recovery.settled()
                                    }
                                }
                                Surface.Group -> {
                                    runGroupUpload(session, recovery)
                                }
                                Surface.NewGroup -> {
                                    newGroupDraft.imageDraft = session.currentValue { load.await() }
                                    newGroupDraft.imageNeedsReselection = false
                                    recovery.settled()
                                }
                            }
                        }
                    }
                    image =
                        when (surface) {
                            Surface.Profile -> profileImages.picture
                            Surface.Group -> publishedGroupImage.value
                            Surface.NewGroup -> if (newGroupDraft.imageDraft != null) "updated" else "previous"
                        }
                    needsReselection = newGroupDraft.imageNeedsReselection
                }
            }
        }
    }

    @Composable
    private fun rememberRecovery(
        capturedAccount: String,
        newGroupDraft: NewGroupDraft,
    ): AvatarPreparationRecovery =
        rememberAvatarPreparationRecovery(
            origin = "1:$capturedAccount:$surface",
            isCurrent = { account.value == capturedAccount },
            onInterrupted = {
                notices++
                if (surface == Surface.NewGroup) newGroupDraft.imageNeedsReselection = true
            },
        )

    private suspend fun runGroupUpload(
        session: GroupCreationSession,
        recovery: AvatarPreparationRecovery,
    ) {
        val updated =
            GroupAvatarUploadAttempt(session::isCurrent, { 0L }).run(
                prepare = {
                    if (suspendUpload) {
                        ImageUploadDraft(
                            byteArrayOf(1),
                            "image/jpeg",
                            null,
                            null,
                            null,
                        )
                    } else {
                        load.await()
                    }
                },
                upload = {
                    if (suspendUpload) load.await()
                    "https://safe.example/image.jpg"
                },
                publish = {
                    if (publishFailure) {
                        knownPublishFailure = true
                        false
                    } else {
                        publishedGroupImage.value = "updated"
                        true
                    }
                },
            )
        if (updated || (session.isCurrent() && knownPublishFailure)) {
            recovery.settled()
        }
    }

    private fun completeBeforeSuspension() {
        composeRule.runOnIdle { checkNotNull(acceptCrop).invoke() }
        if (completeBeforeLock) {
            composeRule.runOnIdle {
                load.complete(
                    ImageUploadDraft(
                        byteArrayOf(1),
                        "image/jpeg",
                        null,
                        null,
                        null,
                    ),
                )
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(if (publishFailure) "previous" else "updated", image) }
        }
    }

    private fun suspendAndDeliver() {
        if (deliberateCancel) composeRule.runOnIdle { checkNotNull(cancelCrop).invoke() }
        composeRule.runOnIdle { locked.value = true }
        composeRule.waitForIdle()
        if (replaceAccount) composeRule.runOnIdle { account.value = "replacement" }
        composeRule.waitForIdle()
        if (!completeBeforeLock) {
            composeRule.runOnIdle {
                load.complete(
                    ImageUploadDraft(
                        byteArrayOf(1),
                        "image/jpeg",
                        null,
                        null,
                        null,
                    ),
                )
            }
        }
    }

    private fun assertRecovered() {
        composeRule.runOnIdle { locked.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(if (completeBeforeLock || replaceAccount || deliberateCancel) 0 else 1, notices)
            val retainedImage = completeBeforeLock && surface != Surface.NewGroup && !publishFailure
            assertEquals(if (retainedImage) "updated" else "previous", image)
            if (surface == Surface.NewGroup && !replaceAccount && !deliberateCancel) {
                assertTrue(needsReselection)
            } else {
                assertFalse(needsReselection)
            }
        }
        // The consumed interruption cannot repeat on another lock/unlock.
        composeRule.runOnIdle { locked.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { locked.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                if (completeBeforeLock || replaceAccount || deliberateCancel) 0 else 1,
                notices,
            )
        }
    }
}
