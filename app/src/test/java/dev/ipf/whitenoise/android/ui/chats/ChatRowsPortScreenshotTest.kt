package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real production rows with local synthetic projections; no live messages, keys, identities or network images. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatRowsPortScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun rowsLight() = capture("chat_rows_port_light")

    @Test fun rowsDark() = capture("chat_rows_port_dark", dark = true)

    @Test fun rowsAmoled() = capture("chat_rows_port_amoled", dark = true, amoled = true)

    @Test fun rowsLargeRtl() = capture("chat_rows_port_large_rtl", dark = true, largeRtl = true)

    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h780dp-xxhdpi")
    fun rowsAmoledHighDensity() = capture("chat_rows_port_amoled_xxhdpi", dark = true, amoled = true)

    @Test fun emptyNewChatsLight() = capture("chat_rows_empty_light", empty = Empty.New)

    @Test fun emptyUnreadDark() = capture("chat_rows_empty_unread_dark", dark = true, empty = Empty.Unread)

    @Test
    fun emptyArchivedAmoled() {
        capture("chat_rows_empty_archived_amoled", dark = true, amoled = true, empty = Empty.Archived)
    }

    @Test
    fun emptySearchLargeRtl() {
        capture("chat_rows_empty_search_large_rtl", dark = true, largeRtl = true, empty = Empty.Search)
    }

    /** The five materially different real-row states share the same native ListItem text/gesture surface. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
        empty: Empty? = null,
    ) {
        val state = ChatRowPortFixtures.state(context)
        val rows =
            listOf(
                ChatRowPortFixtures.item(unread = true),
                ChatRowPortFixtures.item(pinned = true, retentionSeconds = 86_400uL),
                ChatRowPortFixtures.item(membership = SelfMembershipFfi.REMOVED, unread = true),
                ChatRowPortFixtures.item(delivery = ChatListMessageDeliveryStateFfi.FAILED),
                ChatRowPortFixtures.item(preview = "Selected chat"),
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(
                    darkTheme = dark,
                    amoled = amoled,
                    fontScale = if (largeRtl) 2f else 1f,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        when (empty) {
                            Empty.New -> EmptyChats(onCreate = {})
                            Empty.Unread -> ChatListNoResults(query = "", unreadFolderSelected = true)
                            Empty.Archived -> EmptyArchivedChats()
                            Empty.Search -> ChatListNoResults(query = "unmatched", unreadFolderSelected = false)
                            null ->
                                Column {
                                    rows.forEachIndexed { index, row ->
                                        ChatRow(
                                            item = row,
                                            appState = state,
                                            onClick = {},
                                            onOpenProfile = {},
                                            isMuted = index == 1,
                                            selectionMode = index == 4,
                                            selected = index == 4,
                                        )
                                    }
                                }
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private enum class Empty { New, Unread, Archived, Search }
}
