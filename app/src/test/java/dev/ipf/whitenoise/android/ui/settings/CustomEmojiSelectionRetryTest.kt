package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real file launcher, asynchronous read/decode and shortcode preview. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CustomEmojiSelectionRetryTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun unreadableSelectionCanRetryTheSameUriAfterProviderRecovery() = exercise(unreadable = true)

    @Test fun undecodableSelectionCanRetryTheSameUriAfterFileRecovery() = exercise(unreadable = false)

    @Test
    fun accountReplacementDoesNotRestartOrPublishTheOldBlockedSelection() {
        val uri = Uri.parse("content://emoji-retry/blocked.png")
        val reads = AtomicInteger()
        val unblock = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val png = imageBytes()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            reads.incrementAndGet()
            check(unblock.await(5, TimeUnit.SECONDS))
            object : ByteArrayInputStream(png) {
                override fun close() {
                    super.close()
                    closed.set(true)
                }
            }
        }
        val app = WhiteNoiseAppState(context, DraftStore(EmptyDrafts), { null }, emptyList(), "emoji-account")
        val picker = Picker()
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    WhiteNoiseTheme { CustomEmojiScreen(app) {} }
                }
            }
            composeRule.onNodeWithText(context.getString(R.string.choose_files)).performClick()
            composeRule.runOnIdle {
                assertEquals("the file chooser must launch before its result is delivered", 1, picker.launches)
                picker.deliver(uri)
            }
            composeRule.waitForIdle()
            waitForSelection("first provider read", reads, app) { reads.get() == 1 }
            composeRule.runOnIdle {
                app.clearToast()
                app.switchMediaSessionForTest("replacement-account")
            }
            composeRule.waitForIdle()
            unblock.countDown()
            composeRule.waitUntil { closed.get() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("custom_emoji.name").assertDoesNotExist()
            composeRule.runOnIdle {
                assertEquals("old URI must not restart under its replacement owner", 1, reads.get())
                assertNull(app.toast)
            }
        } finally {
            unblock.countDown()
            app.mutationsScope.cancel()
        }
    }

    private fun exercise(unreadable: Boolean) {
        val uri = Uri.parse("content://emoji-retry/selected.png")
        val available = AtomicBoolean(false)
        val reads = AtomicInteger()
        val png = imageBytes()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            reads.incrementAndGet()
            if (available.get()) {
                ByteArrayInputStream(png)
            } else if (unreadable) {
                throw FileNotFoundException("fixture provider unavailable")
            } else {
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            }
        }
        val app = WhiteNoiseAppState(context, DraftStore(EmptyDrafts), { null }, emptyList(), "emoji-account")
        val picker = Picker()
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    WhiteNoiseTheme { CustomEmojiScreen(app) {} }
                }
            }
            val files = context.getString(R.string.choose_files)
            composeRule.onNodeWithText(files).performClick()
            composeRule.runOnIdle {
                assertEquals("the file chooser must launch before its result is delivered", 1, picker.launches)
                picker.deliver(uri)
            }
            composeRule.waitForIdle()
            waitForSelection("first provider read", reads, app) { reads.get() == 1 }
            waitForSelection("failed-selection notice", reads, app) {
                app.toast?.title == AppText.Resource(R.string.custom_emoji_not_image)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("custom_emoji.name").assertDoesNotExist()
            composeRule.runOnIdle { available.set(true) }
            composeRule.onNodeWithText(files).performClick()
            composeRule.runOnIdle {
                assertEquals("retry must launch the chooser again", 2, picker.launches)
                picker.deliver(uri)
            }
            composeRule.waitForIdle()
            composeRule.waitUntil {
                reads.get() == 2 &&
                    composeRule.onAllNodesWithTag("custom_emoji.name").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.runOnIdle { assertEquals(2, picker.launches) }
        } finally {
            app.mutationsScope.cancel()
        }
    }

    private fun waitForSelection(
        boundary: String,
        reads: AtomicInteger,
        app: WhiteNoiseAppState,
        condition: () -> Boolean,
    ) {
        try {
            composeRule.waitUntil(condition = condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "$boundary: reads=${reads.get()}, account=${app.activeAccountRef}, " +
                    "runtime=${app.runtimeGeneration}, toast=${app.toast}",
                timeout,
            )
        }
    }

    private fun imageBytes(): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
            }.toByteArray()

    private object EmptyDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private class Picker :
        ActivityResultRegistry(),
        ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var launches = 0
        private var requestCode = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            launches++
        }

        fun deliver(uri: Uri) {
            assertTrue("the launched file chooser must accept its delivered result", dispatchResult(requestCode, uri))
        }
    }
}
