package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression coverage for the permission-free browse action across recent-media states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class RecentMediaContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Full media access keeps Browse all independent from the permission launcher. */
    @Test
    fun fullAccessKeepsBrowseAllAvailable() {
        assertBrowseAll(granted = true, partialAccess = false, items = listOf(item()))
    }

    /** Partial media access keeps Browse all independent from the permission launcher. */
    @Test
    fun partialAccessKeepsBrowseAllAvailable() {
        assertBrowseAll(granted = true, partialAccess = true, items = listOf(item()))
    }

    /** Denied media access keeps Browse all independent from the permission launcher. */
    @Test
    fun deniedAccessKeepsBrowseAllAvailable() {
        assertBrowseAll(granted = false, partialAccess = false, items = emptyList())
    }

    /** An empty permitted gallery still offers the system photo picker. */
    @Test
    fun emptyGalleryKeepsBrowseAllAvailable() {
        assertBrowseAll(granted = true, partialAccess = false, items = emptyList())
    }

    /** Renders one state and proves Browse all calls only the picker callback. */
    private fun assertBrowseAll(
        granted: Boolean,
        partialAccess: Boolean,
        items: List<RecentMediaItem>,
    ) {
        var permissionRequests = 0
        var browseRequests = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                RecentMediaContent(
                    granted = granted,
                    partialAccess = partialAccess,
                    items = items,
                    onRequestAccess = { permissionRequests += 1 },
                    onPick = {},
                    onBrowseAll = { browseRequests += 1 },
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.recent_media_browse_all))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, browseRequests)
        assertEquals(0, permissionRequests)
    }

    /** Builds one deterministic recent image entry. */
    private fun item() = RecentMediaItem(Uri.parse("content://recent/image"), isVideo = false)
}
