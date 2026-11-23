package dev.ipf.whitenoise.android.ui.group

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.captureClickCallbackForReplay
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class GroupEditGifUploadTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val fixture = GroupEditGifFixture()

    @After
    fun close() {
        fixture.release.countDown()
        composeRule.runOnIdle { fixture.mounted.value = false }
        fixture.close()
    }

    /** Replaying callbacks from a cancelled selection cannot cancel or upload a same-URI reselection. */
    @Test
    fun cancellationAndSameUriReselectionFenceOldCallbacks() {
        render()
        val uri = fixture.uri("same-selection")
        pick(uri)
        val oldConfirm = composeRule.onNodeWithTag("confirmation.confirm").captureClickCallbackForReplay()
        val oldCancel = composeRule.onNodeWithTag("confirmation.cancel").captureClickCallbackForReplay()
        composeRule.onNodeWithTag("confirmation.cancel").performClick()
        assertEquals(0, fixture.uploads.get())
        pick(uri)
        composeRule.runOnIdle {
            oldCancel()
            oldConfirm()
        }
        composeRule.onNodeWithText(title()).assertIsDisplayed()
        assertEquals(0, fixture.uploads.get())
        val currentConfirm = composeRule.onNodeWithTag("confirmation.confirm").captureClickCallbackForReplay()
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        awaitSettledUploads(1)
        composeRule.runOnIdle { currentConfirm() }
        awaitSettledUploads(1)
        assertEquals(listOf("image/gif"), fixture.uploadMimeTypes)
    }

    /** A still photo also goes to a public server, so it needs the same consent before the crop editor opens. */
    @Test
    fun stillPhotoAsksBeforeCropAndCancelLeavesNothing() {
        render()
        pick(fixture.uri("still-cancel", still = true), photoTitle())
        composeRule.onNodeWithText(fixture.context.getString(R.string.group_photo_upload_notice)).assertIsDisplayed()
        composeRule.onNodeWithTag("identity_crop.dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("confirmation.cancel").performClick()
        composeRule.onNodeWithText(photoTitle()).assertDoesNotExist()
        composeRule.onNodeWithTag("identity_crop.dialog").assertDoesNotExist()
        assertEquals(0, fixture.uploads.get())
    }

    @Test
    fun confirmedStillPhotoOpensCropWithoutUploading() {
        render()
        pick(fixture.uri("still-confirm", still = true), photoTitle())
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        awaitProgress { composeRule.onAllNodesWithTag("identity_crop.dialog").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(photoTitle()).assertDoesNotExist()
        assertEquals(0, fixture.uploads.get())
    }

    /** Accepting the crop after consent uploads the cropped still exactly once, not the original file. */
    @Test
    fun acceptedCropUploadsTheStillPhotoOnce() {
        render()
        pick(fixture.uri("still-crop", still = true), photoTitle())
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        awaitProgress { composeRule.onAllNodesWithTag("identity_crop.dialog").fetchSemanticsNodes().isNotEmpty() }
        val cropConfirm = composeRule.onNodeWithTag("identity_crop.confirm").captureClickCallbackForReplay()
        composeRule.onNodeWithTag("identity_crop.confirm").performClick()
        awaitSettledUploads(1)
        composeRule.runOnIdle { cropConfirm() }
        awaitSettledUploads(1)
        assertTrue(fixture.uploadMimeTypes.single() != "image/gif")
    }

    /** A GIF the provider could only read after the still-photo notice is never uploaded as an animation. */
    @Test
    fun gifReadableOnlyAfterStillNoticeIsNotUploadedAnimated() {
        render()
        pick(fixture.uri("late-gif", failedOpens = 3), photoTitle())
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        awaitProgress { fixture.uploads.get() == 1 }
        awaitSettledUploads(1)
        assertTrue(fixture.uploadMimeTypes.none { it.equals("image/gif", ignoreCase = true) })
    }

    /** Once the provider recovers in time, the uncroppable GIF gets the animation notice instead of uploading. */
    @Test
    fun gifReadableBeforeFallbackAsksBeforeUploading() {
        render()
        pick(fixture.uri("recovered-gif", failedOpens = 2), photoTitle())
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        awaitProgress { composeRule.onAllNodesWithText(title()).fetchSemanticsNodes().isNotEmpty() }
        awaitSettledUploads(0)
    }

    @Test
    fun groupReplacementRetiresDialogAndCallbacksWithoutRemountingParent() = replacement(fixture.secondGroup)

    @Test
    fun sameGroupControllerReplacementRetiresDialogAndCallbacks() = replacement(fixture.firstGroup)

    /** A result delivered to an old registry registration cannot become a pick for a replacement editor. */
    @Test
    fun delayedDocumentResultCannotRetargetReplacementEditor() {
        render()
        launchDocument()
        val oldRequest = fixture.registry.launches.last()
        composeRule.runOnIdle { fixture.controller.value = fixture.newController(fixture.secondGroup) }
        composeRule.runOnIdle { fixture.registry.deliver(fixture.uri("retired-result"), oldRequest) }
        composeRule.onNodeWithText(title()).assertDoesNotExist()
        pick(fixture.uri("current-result"))
        composeRule.runOnIdle { fixture.registry.deliver(fixture.uri("retired-result-replay"), oldRequest) }
        composeRule.onNodeWithText(title()).assertIsDisplayed()
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        awaitProgress { fixture.uploads.get() == 1 }
    }

    /** Actual provider sniffing starts in the old editor and completes after its owner is retired. */
    @Test
    fun delayedDocumentReadCannotPublishDialogIntoReplacementEditor() {
        render()
        launchDocument()
        val held = fixture.uri("held-result", hold = true)
        composeRule.runOnIdle { assertTrue(fixture.registry.deliver(held)) }
        awaitProgress { fixture.entered.count == 0L }
        assertEquals("Actual provider read must start before replacing its editor", 0L, fixture.entered.count)
        composeRule.runOnIdle { fixture.controller.value = fixture.newController(fixture.secondGroup) }
        fixture.release.countDown()
        awaitProgress { fixture.closedStreams.get() > 0 }
        // A fresh successful pick orders a new owner result after the held provider has returned.
        pick(fixture.uri("after-held-result"))
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        awaitProgress { fixture.uploads.get() == 1 }
        composeRule.onNodeWithText(title()).assertDoesNotExist()
    }

    /** Dialog dismissal on screen disposal retires the captured real production callbacks permanently. */
    @Test
    fun disposedEditorCannotUploadFromCapturedConfirmation() {
        render()
        pick(fixture.uri("disposed"))
        val confirm = composeRule.onNodeWithTag("confirmation.confirm").captureClickCallbackForReplay()
        composeRule.runOnIdle { fixture.mounted.value = false }
        composeRule.runOnIdle { confirm() }
        assertEquals(0, fixture.uploads.get())
    }

    /** A previously enabled callback must re-read permission rather than trusting its captured UI state. */
    @Test
    fun demotionBeforeReplayedConfirmDoesNotUpload() {
        render()
        pick(fixture.uri("demoted"))
        val confirm = composeRule.onNodeWithTag("confirmation.confirm").captureClickCallbackForReplay()
        composeRule.runOnIdle {
            val controller = fixture.controller.value
            controller.applyGroupStateForTest(controller.group.copy(admins = emptyList()))
            confirm()
        }
        assertEquals(0, fixture.uploads.get())
    }

    /** Save waits while an uncroppable photo is prepared, so it cannot close the editor under the upload. */
    @Test
    fun saveIsHeldWhileFallbackPhotoIsPrepared() {
        render()
        // Opens: picker sniff and crop load fail, then the fallback's own sniff is held.
        pick(fixture.uri("save-during-fallback", still = true, failedOpens = 2, holdAtOpen = 2), photoTitle())
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        awaitProgress { fixture.entered.count == 0L }
        composeRule.onNode(hasSetTextAction() and hasText("Editor group")).performTextReplacement("Renamed group")
        val save = composeRule.onNodeWithText(fixture.context.getString(R.string.save_group))
        save.assertIsNotEnabled()
        fixture.release.countDown()
        awaitProgress { fixture.uploads.get() == 1 }
        awaitSettledUploads(1)
        save.assertIsEnabled()
    }

    private fun replacement(groupId: String) {
        render()
        pick(fixture.uri("before-replacement"))
        val oldConfirm = composeRule.onNodeWithTag("confirmation.confirm").captureClickCallbackForReplay()
        val oldCancel = composeRule.onNodeWithTag("confirmation.cancel").captureClickCallbackForReplay()
        composeRule.runOnIdle { fixture.controller.value = fixture.newController(groupId) }
        composeRule.onNodeWithText(title()).assertDoesNotExist()
        composeRule.runOnIdle { oldConfirm() }
        assertEquals(0, fixture.uploads.get())
        pick(fixture.uri("after-replacement"))
        composeRule.runOnIdle {
            oldCancel()
            oldConfirm()
        }
        composeRule.onNodeWithText(title()).assertIsDisplayed()
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        awaitProgress { fixture.uploads.get() == 1 }
    }

    private fun render() {
        composeRule.setContent { GifEditorTestContent(fixture) }
    }

    private fun launchDocument() {
        composeRule.onNodeWithTag("group_edit.photoAction").performClick()
        composeRule.onNodeWithText(fixture.context.getString(R.string.choose_files)).performClick()
        composeRule.waitForIdle()
    }

    private fun pick(
        uri: Uri,
        expectedTitle: String = title(),
    ) {
        launchDocument()
        composeRule.runOnIdle { assertTrue(fixture.registry.deliver(uri)) }
        awaitProgress { composeRule.onAllNodesWithText(expectedTitle).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits until every launched upload attempt has finished, then checks how many reached the server. */
    private fun awaitSettledUploads(expected: Int) {
        composeRule.waitForIdle()
        if (expected > 0) awaitProgress { fixture.uploads.get() == expected }
        val mutations = fixture.state.mutationsScope.coroutineContext[Job]!!
        awaitProgress { mutations.children.none { it.isActive } }
        assertEquals(expected, fixture.uploads.get())
    }

    /** Drive the real Main→IO→Main boundary without blocking the paused Android looper. */
    private fun awaitProgress(condition: () -> Boolean) {
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            condition()
        }
        composeRule.waitForIdle()
    }

    private fun title() = fixture.context.getString(R.string.group_gif_upload_title)

    private fun photoTitle() = fixture.context.getString(R.string.group_photo_upload_title)
}
