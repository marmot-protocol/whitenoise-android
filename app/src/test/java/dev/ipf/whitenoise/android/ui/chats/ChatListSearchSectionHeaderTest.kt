package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListSearchSectionHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun groupsAndMessagesHeadersExposeHeadingSemantics() {
        setSearchSections()

        listOf(CHAT_LIST_SEARCH_GROUPS_HEADER_TAG, CHAT_LIST_SEARCH_MESSAGES_HEADER_TAG).forEach { tag ->
            composeRule
                .onNodeWithTag(tag)
                .assertIsDisplayed()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
    }

    @Test
    fun groupsAndMessagesSectionsLight() {
        setSearchSections()

        capture("chat_list_search_groups_messages_light")
    }

    @Test
    fun groupsAndMessagesSectionsRtlAmoledLargeText() {
        setSearchSections(darkTheme = true, amoled = true, rtl = true, fontScale = 1.5f)

        capture("chat_list_search_groups_messages_rtl_amoled_large_text")
    }

    private fun setSearchSections(
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column {
                            ChatListSearchSectionHeader("Groups", CHAT_LIST_SEARCH_GROUPS_HEADER_TAG)
                            SearchPreviewRow("Marmot release")
                            SearchPreviewRow("Marmot friends")
                            ChatListSearchSectionHeader("Messages", CHAT_LIST_SEARCH_MESSAGES_HEADER_TAG)
                            SearchPreviewRow("…planning the marmot release")
                        }
                    }
                }
            }
        }
    }

    private fun capture(snapshotName: String) {
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/$snapshotName.png")
    }

    private companion object {
        const val SCREENSHOT_TAG = "chat-list-search-section-preview"
    }
}

@androidx.compose.runtime.Composable
private fun SearchPreviewRow(title: String) {
    Text(
        text = title,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp, vertical = 20.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}
