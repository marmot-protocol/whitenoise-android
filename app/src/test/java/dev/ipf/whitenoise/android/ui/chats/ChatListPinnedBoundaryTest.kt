package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListPinnedBoundaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun boundaryAppearsOnlyWhenBothPinnedAndUnpinnedRowsAreVisible() {
        assertEquals(2, pinnedBoundaryIndex(listOf(true, true, false, false), showArchived = false))
        assertEquals(1, pinnedBoundaryIndex(listOf(true, false), showArchived = false))
        assertNull(pinnedBoundaryIndex(listOf(false, false), showArchived = false))
        assertNull(pinnedBoundaryIndex(listOf(true, true), showArchived = false))
        assertNull(pinnedBoundaryIndex(listOf(true, false, true), showArchived = false))
        assertNull(pinnedBoundaryIndex(emptyList(), showArchived = false))
        assertNull(pinnedBoundaryIndex(listOf(true, false), showArchived = true))
    }

    @Test
    fun pinnedBoundaryLight() {
        captureRows(
            darkTheme = false,
            amoled = false,
            snapshotName = "chat_list_pinned_boundary_light",
            pinnedTitles = listOf("Pinned project", "Pinned friends"),
            unpinnedTitles = listOf("Recent conversation"),
        )
    }

    @Test
    fun pinnedBoundaryDark() {
        captureRows(
            darkTheme = true,
            amoled = false,
            snapshotName = "chat_list_pinned_boundary_dark",
            pinnedTitles = listOf("Pinned project", "Pinned friends"),
            unpinnedTitles = listOf("Recent conversation"),
        )
    }

    @Test
    fun pinnedBoundaryAmoled() {
        captureRows(
            darkTheme = true,
            amoled = true,
            snapshotName = "chat_list_pinned_boundary_amoled",
            pinnedTitles = listOf("Pinned project", "Pinned friends"),
            unpinnedTitles = listOf("Recent conversation"),
        )
    }

    @Test
    fun unpinnedHeadSettledLight() {
        captureRows(
            darkTheme = false,
            amoled = false,
            snapshotName = "chat_list_unpinned_head_settled_light",
            pinnedTitles = listOf("Pinned friends"),
            unpinnedTitles = listOf("Formerly pinned", "Recent conversation"),
        )
    }

    private fun captureRows(
        darkTheme: Boolean,
        amoled: Boolean,
        snapshotName: String,
        pinnedTitles: List<String>,
        unpinnedTitles: List<String>,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        pinnedTitles.forEach { PreviewRow(it, pinned = true) }
                        ChatListPinnedBoundary()
                        unpinnedTitles.forEach { PreviewRow(it, pinned = false) }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(CHAT_LIST_PINNED_BOUNDARY_TAG).assertIsDisplayed()
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/$snapshotName.png")
    }

    private companion object {
        const val SCREENSHOT_TAG = "chat-list-pinned-boundary-preview"
    }
}

@Composable
private fun PreviewRow(
    title: String,
    pinned: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (pinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
