package dev.ipf.whitenoise.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer
import dev.ipf.whitenoise.android.ui.profile.AvatarViewerImageState
import dev.ipf.whitenoise.android.ui.profile.rememberAvatarViewerImageState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Full-picture actions must keep their original-byte behavior when avatar pixels are already warm. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AvatarViewerLocalAssetTest {
    @get:Rule val composeRule = createComposeRule()

    @Before
    @After
    fun clearLoader() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
    }

    @Test
    fun warmPublicPictureKeepsSaveEnabled() {
        val bytes = largePng()
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> bytes }
        val preview = AvatarImageLoader.decodeAndCache(URL, bytes, AvatarImageLoader.currentCacheLifetime())
        assertNotNull(preview)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                AvatarFullScreenViewer("Picture", "picture", URL, preview, onDismiss = {})
            }
        }
        composeRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.actions)).performClick()
        val save = composeRule.onNodeWithText(context.getString(R.string.media_save))
        composeRule.waitUntil(5_000) { !save.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        save.assertIsEnabled()
    }

    @Test
    fun warmPublicPictureLoadsOriginalResolution() {
        val bytes = largePng()
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> bytes }
        val preview = checkNotNull(AvatarImageLoader.decodeAndCache(URL, bytes, AvatarImageLoader.currentCacheLifetime()))
        assertEquals(512, preview.width)
        var observed: AvatarViewerImageState? = null
        composeRule.setContent {
            val state = rememberAvatarViewerImageState(URL, preview)
            SideEffect { observed = state }
        }
        composeRule.waitUntil(5_000) { observed is AvatarViewerImageState.Ready }
        assertEquals(1024, (observed as AvatarViewerImageState.Ready).bitmap.width)
    }

    @Test
    fun retainedOriginalUpgradesWarmPixelsWithoutNetworkOrRepeatedReads() {
        val bytes = largePng()
        val preview = checkNotNull(AvatarImageLoader.decodeAndCache("stored", bytes, AvatarImageLoader.currentCacheLifetime()))
        val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val requests = AtomicInteger()
        AvatarImageLoader.attachProfileImageFetcher { _, _ ->
            requests.incrementAndGet()
            error("unexpected network")
        }
        val reader: suspend () -> ByteArray? = {
            reads.incrementAndGet()
            release.await()
            bytes
        }
        val tick = mutableStateOf(0)
        var observed: AvatarViewerImageState? = null
        composeRule.setContent {
            Text(tick.value.toString())
            val state = rememberAvatarViewerImageState(null, preview, reader)
            SideEffect { observed = state }
        }
        try {
            composeRule.waitUntil(5_000) { reads.get() == 1 }
            assertSame(preview, (observed as AvatarViewerImageState.Local).image)
            release.complete(Unit)
            composeRule.waitUntil(5_000) { observed is AvatarViewerImageState.Ready }
            assertEquals(1024, (observed as AvatarViewerImageState.Ready).bitmap.width)
            composeRule.runOnIdle { tick.value++ }
            composeRule.waitForIdle()
            assertEquals(1, reads.get())
            assertEquals(0, requests.get())
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun replacedOwnerCannotRetainThePreviousFullResolutionPixels() {
        val bytes = largePng()
        val preview = checkNotNull(AvatarImageLoader.decodeAndCache("stored", bytes, AvatarImageLoader.currentCacheLifetime()))
        val picture = mutableStateOf<ImageBitmap?>(preview)
        val reader = mutableStateOf<suspend () -> ByteArray?>({ bytes })
        val replacementReads = AtomicInteger()
        var observed: AvatarViewerImageState? = null
        composeRule.setContent {
            val state = rememberAvatarViewerImageState(null, picture.value, reader.value)
            Text(state.javaClass.simpleName)
            SideEffect { observed = state }
        }
        composeRule.waitUntil(5_000) { observed is AvatarViewerImageState.Ready }
        composeRule.runOnIdle {
            picture.value = null
            reader.value = {
                replacementReads.incrementAndGet()
                null
            }
        }
        composeRule.waitForIdle()
        try {
            composeRule.waitUntil(5_000) { observed == AvatarViewerImageState.Failed }
        } catch (error: Throwable) {
            throw AssertionError("Owner replacement left ${observed?.javaClass?.simpleName}; reads=${replacementReads.get()}", error)
        }
    }

    @Test
    fun retainedSaveRechecksTheReaderAfterItsInitialRead() {
        val bytes = largePng()
        val preview = checkNotNull(AvatarImageLoader.decodeAndCache("stored", bytes, AvatarImageLoader.currentCacheLifetime()))
        val ownerValid = AtomicBoolean(true)
        val reads = AtomicInteger()
        val reader: suspend () -> ByteArray? = {
            reads.incrementAndGet()
            bytes.takeIf { ownerValid.get() }
        }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                AvatarFullScreenViewer("Picture", "picture", picture = preview, onDismiss = {}, readLocalBytes = reader)
            }
        }
        composeRule.waitUntil(5_000) { reads.get() == 1 }
        composeRule.waitForIdle()
        ownerValid.set(false)
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.actions)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.media_save)).performClick()
        composeRule.waitUntil(5_000) { reads.get() == 2 }
    }
}

private const val URL = "https://example.invalid/original.png"

private fun largePng(): ByteArray {
    val bitmap = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.BLUE)
    return ByteArrayOutputStream().use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        bitmap.recycle()
        out.toByteArray()
    }
}
