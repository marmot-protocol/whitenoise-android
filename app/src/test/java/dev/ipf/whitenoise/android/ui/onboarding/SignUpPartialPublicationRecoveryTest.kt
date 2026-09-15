package dev.ipf.whitenoise.android.ui.onboarding

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises accepted-account receipts and native-stage ownership without prototype success state. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignUpPartialPublicationRecoveryTest {
    /** Local edits and Back do not create accounts or publish metadata. */
    @Test fun localDraftAndCancelMakeNoNativeCalls() =
        runTest {
            val f = Fixture(this)
            f.controller.stageDraft(DRAFT)
            assertEquals(DRAFT, f.controller.draft)
            assertTrue(f.controller.discardUnsubmitted())
            advanceUntilIdle()
            assertEquals(0, f.creations)
            assertEquals(emptyList<UserProfileMetadataFfi>(), f.publications)
        }

    /** Two taps while creation is suspended accept just one native identity. */
    @Test fun doubleSubmitCreatesOnceAndPublishesExactSubmittedDraft() =
        runTest {
            val f = Fixture(this)
            val release = CompletableDeferred<Unit>()
            f.beforeCreateReturn = { release.await() }
            f.controller.submit(DRAFT)
            f.controller.submit(DRAFT.copy(name = "Wrong"))
            runCurrent()
            assertEquals(1, f.creations)
            assertFalse(f.controller.discardUnsubmitted())
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(SignUpStage.Complete, f.controller.stage)
            assertEquals("Alice", f.publications.single().name)
            assertEquals("Local about", f.publications.single().about)
            assertEquals(listOf("created"), f.publishedAccounts)
        }

    /** A failed photo retry reuses the accepted account, with no metadata publication before upload succeeds. */
    @Test fun failedPhotoRetriesForSameAcceptedIdentity() =
        runTest {
            val f = Fixture(this)
            f.uploadFails = true
            f.controller.submit(DRAFT.copy(photo = PHOTO))
            advanceUntilIdle()
            assertEquals(SignUpStage.PhotoFailed, f.controller.stage)
            assertEquals(0, f.publications.size)
            f.uploadFails = false
            f.controller.retry()
            advanceUntilIdle()
            assertEquals(1, f.creations)
            assertEquals(listOf("created", "created"), f.uploadedAccounts)
            assertEquals("https://images.example/avatar.jpg", f.publications.single().picture)
            assertEquals(SignUpStage.Complete, f.controller.stage)
        }

    /** Retrying metadata keeps the uploaded photo URL and does not create or upload again. */
    @Test fun failedPublicationRetainsDraftAccountAndUploadedPhoto() =
        runTest {
            val f = Fixture(this)
            f.publishSucceeds = false
            f.controller.submit(DRAFT.copy(photo = PHOTO))
            advanceUntilIdle()
            assertEquals(SignUpStage.PublishFailed, f.controller.stage)
            assertEquals(DRAFT.copy(photo = PHOTO), f.controller.draft)
            f.controller.submit(DRAFT.copy(name = "Replacement"))
            f.publishSucceeds = true
            f.controller.retry()
            f.controller.retry()
            advanceUntilIdle()
            assertEquals(1, f.creations)
            assertEquals(1, f.uploadedAccounts.size)
            assertEquals(2, f.publications.size)
            assertEquals(f.publications[0], f.publications[1])
            assertEquals(listOf("created"), f.finished)
        }

    /** Selecting another account while creation returns prevents stale activation and every following stage. */
    @Test fun accountChangeDuringCreationRetainsReceiptWithoutStealingSelection() =
        runTest {
            val f = Fixture(this)
            f.beforeCreateReturn = { f.owner = SignUpOwner(1, "other") }
            f.controller.submit(DRAFT)
            advanceUntilIdle()
            assertEquals(SignUpStage.OwnerChanged, f.controller.stage)
            assertEquals("created", f.controller.acceptedIdentity?.label)
            assertEquals("other", f.owner.accountRef)
            assertEquals(0, f.publications.size)
            f.controller.retry()
            f.controller.continueWithoutProfile()
            assertEquals(emptyList<String>(), f.finished)
        }

    /** An upload may finish for its captured account, but cannot start publication after an account switch. */
    @Test fun accountChangeDuringUploadSkipsPublication() =
        runTest {
            val f = Fixture(this)
            f.afterUpload = { f.owner = SignUpOwner(1, "other") }
            f.controller.submit(DRAFT.copy(photo = PHOTO))
            advanceUntilIdle()
            assertEquals(SignUpStage.OwnerChanged, f.controller.stage)
            assertEquals(0, f.publications.size)
            assertEquals(emptyList<String>(), f.finished)
        }

    /** Completion from a replaced runtime cannot mark the new runtime Chats-ready. */
    @Test fun runtimeChangeDuringPublicationRejectsFinish() =
        runTest {
            val f = Fixture(this)
            f.afterPublish = { f.owner = f.owner.copy(runtime = 2) }
            f.controller.submit(DRAFT)
            advanceUntilIdle()
            assertEquals(SignUpStage.OwnerChanged, f.controller.stage)
            assertEquals(emptyList<String>(), f.finished)
        }

    /** Choosing Continue after a failed profile keeps the identity and does not claim a successful publication. */
    @Test fun explicitContinueAfterFailureFinishesOnlyAcceptedAccount() =
        runTest {
            val f = Fixture(this)
            f.publishSucceeds = false
            f.controller.submit(DRAFT)
            advanceUntilIdle()
            f.controller.continueWithoutProfile()
            assertEquals(SignUpStage.Complete, f.controller.stage)
            assertEquals(1, f.creations)
            assertEquals(1, f.publications.size)
            assertEquals(listOf("created"), f.finished)
        }

    /** A sign-out gate prevents retries and Continue from publishing or activating any account. */
    @Test fun signOutBlocksPartialRetryAndContinue() =
        runTest {
            val f = Fixture(this)
            f.publishSucceeds = false
            f.controller.submit(DRAFT)
            advanceUntilIdle()
            f.available = false
            f.controller.retry()
            f.controller.continueWithoutProfile()
            advanceUntilIdle()
            assertEquals(SignUpStage.OwnerChanged, f.controller.stage)
            assertEquals(1, f.publications.size)
            assertEquals(emptyList<String>(), f.finished)
        }

    /** A failed creation with no receipt can accept a corrected draft on an explicit retry. */
    @Test fun creationFailureWithoutReceiptRetainsEditableDraft() =
        runTest {
            val f = Fixture(this)
            f.createFails = true
            f.controller.submit(DRAFT)
            advanceUntilIdle()
            assertEquals(SignUpStage.CreateFailed, f.controller.stage)
            assertEquals(null, f.controller.acceptedIdentity)
            f.createFails = false
            f.controller.submit(DRAFT.copy(name = "Corrected"))
            advanceUntilIdle()
            assertEquals("Corrected", f.publications.single().name)
        }

    /** Test backend returns actual FFI receipt values and counts each stage independently. */
    private class Fixture(
        scope: CoroutineScope,
    ) {
        var owner = SignUpOwner(1, null)
        var available = true
        var createFails = false
        var uploadFails = false
        var publishSucceeds = true
        var creations = 0
        var beforeCreateReturn: suspend () -> Unit = {}
        var afterUpload: () -> Unit = {}
        var afterPublish: () -> Unit = {}
        val publications = mutableListOf<UserProfileMetadataFfi>()
        val publishedAccounts = mutableListOf<String>()
        val uploadedAccounts = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val controller =
            SignUpController(
                scope,
                { owner },
                { available },
                create = {
                    creations++
                    if (createFails) error("create failed")
                    beforeCreateReturn()
                    AccountSummaryFfi("created", "11".repeat(32), true, false, false, true)
                },
                accept = { summary, captured ->
                    if (owner == captured && available) {
                        owner = captured.copy(accountRef = summary.label)
                        true
                    } else {
                        false
                    }
                },
                upload = { account, _ ->
                    uploadedAccounts += account
                    if (uploadFails) error("upload failed")
                    afterUpload()
                    "https://images.example/avatar.jpg"
                },
                publish = { account, metadata ->
                    publishedAccounts += account
                    publications += metadata
                    afterPublish()
                    publishSucceeds
                },
                finish = { summary, _ ->
                    finished += summary.label
                    true
                },
            )
    }

    private companion object {
        val DRAFT = SignUpProfileDraft(" Alice ", " Local about ")
        val PHOTO = ImageUploadDraft(byteArrayOf(1, 2, 3), "image/jpeg", null, null, null)
    }
}
