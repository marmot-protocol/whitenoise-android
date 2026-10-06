package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import android.graphics.Color
import dev.ipf.whitenoise.android.state.ContactPictureChange
import dev.ipf.whitenoise.android.state.ContactPictureStore
import dev.ipf.whitenoise.android.state.contactPicturePng
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draft clear and pending Save are bound to the mounted contact/account, not the current global account. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContactPictureEditorControllerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val store get() =
        ContactPictureStore(
            context.getSharedPreferences("editor", Context.MODE_PRIVATE),
            File(context.noBackupFilesDir, "editor"),
        )

    @Test fun clearDoesNotTouchStorageUntilSaveAndCancellationDropsTheDraft() =
        runTest {
            store.save("a", "contact", "", "", ContactPictureChange.Replace(contactPicturePng(Color.RED))) { true }
            val initial = store.reference("a", "contact")
            val controller = ContactPictureEditorController(store, initial, this) { true }
            controller.clear()
            assertFalse(controller.state.value.hasPicture)
            assertEquals(initial, store.reference("a", "contact"))
            controller.dispose()
            var commits = 0
            controller.save({ _, _ ->
                commits++
                true
            }, {})
            advanceUntilIdle()
            assertEquals(0, commits)
            assertEquals(initial, store.reference("a", "contact"))
        }

    @Test fun expiredOwnerCannotCommitAQueuedSaveOrPublishSuccess() =
        runTest {
            var current = true
            val controller = ContactPictureEditorController(store, null, this) { current }
            var saved = false
            controller.save(
                { change, canWrite -> store.save("a", "contact", "Late", "Late", change, canWrite) },
                { saved = true },
            )
            current = false
            advanceUntilIdle()
            assertFalse(saved)
            assertFalse(store.hasChoice("a", "contact"))
        }

    @Test fun successfulSaveUsesTheCapturedDraftAndFailedSaveKeepsItAvailable() =
        runTest {
            val controller = ContactPictureEditorController(store, null, this) { true }
            controller.clear()
            controller.save({ _, _ -> false }, {})
            advanceUntilIdle()
            assertTrue(controller.state.value.failed)
            var saved = false
            controller.save(
                { change, owner -> store.save("a", "contact", "Saved", "Note", change, owner) },
                { saved = true },
            )
            advanceUntilIdle()
            assertTrue(saved)
            assertFalse(controller.state.value.busy)
        }
}
