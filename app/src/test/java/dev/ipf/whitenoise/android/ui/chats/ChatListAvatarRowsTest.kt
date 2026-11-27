package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.canonicalChatListGroupId
import dev.ipf.whitenoise.android.ui.common.LocalAvatarLoadsAllowed
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListAvatarRowsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visibleSearchKeysAllowLoadsWhileRetainedRowsStayPassive() {
        val fixture = ChatRowPortFixtures.item()
        val rows = List(20) { index -> fixture.copy(group = fixture.group.copy(groupIdHex = "CHAT-$index")) }
        val loadsAllowed = mutableMapOf<String, Boolean>()
        lateinit var viewport: LazyListState
        lateinit var scrollTwoRows: () -> Unit

        composeRule.setContent {
            val listState = rememberChatAvatarListState()
            val visibleKeys = rememberChatAvatarVisibleKeys(listState)
            val scope = rememberCoroutineScope()
            val distance = with(LocalDensity.current) { 80.dp.toPx() }
            viewport = listState
            scrollTwoRows = { scope.launch { listState.scrollBy(distance) } }

            LazyColumn(state = listState, modifier = Modifier.height(120.dp)) {
                chatListRows(rows, { canonicalChatListGroupId(it.id) }, visibleKeys) { row, _ ->
                    val allowed = LocalAvatarLoadsAllowed.current
                    DisposableEffect(row.id) {
                        onDispose { loadsAllowed.remove(row.id) }
                    }
                    SideEffect { loadsAllowed[row.id] = allowed }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(true, loadsAllowed["CHAT-0"])
            scrollTwoRows()
        }
        composeRule.runOnIdle {
            assertEquals(2, viewport.firstVisibleItemIndex)
            assertEquals(true, loadsAllowed["CHAT-3"])
            assertEquals(false, loadsAllowed["CHAT-0"])
        }
    }
}
