package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.TransientNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Empty slot claims do not invalidate their callers, while later banners still observe ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TopNoticeEmptySlotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Cancels a pending indefinite snackbar after each isolated host test. */
    @After
    fun closeScope() = scope.cancel()

    /** A real slot claim leaves its empty caller alone, then follows live notice and owner changes. */
    @Test
    fun claimingAnEmptySlotDoesNotRecomposeItsCallerAndLaterNoticesStillMoveToTheNewestOwner() {
        var notice by mutableStateOf<TransientNotice?>(null)
        val host = TopNoticeHost(SnackbarHostState(), notice = { notice })
        var presentations = 0
        var presents = false
        lateinit var slot: TopNoticeSlotState
        composeRule.setContent {
            CompositionLocalProvider(LocalTopNoticeHost provides host) {
                slot = rememberTopNoticeSlot()
                val visible = slot.presents(slot.hostNotice, scope = null)
                SideEffect {
                    presentations++
                    presents = visible
                }
                Text("Viewer")
                TopNoticeRegion(slot)
            }
        }
        composeRule.runOnIdle {
            assertEquals(1, presentations)
            assertFalse(presents)
            notice = TransientNotice(id = 1, title = AppText.Plain("Notice"))
        }
        composeRule.runOnIdle {
            assertTrue(presents)
            host.claim(TopNoticeClaim())
        }
        composeRule.runOnIdle {
            assertFalse(presents)
            assertFalse(slot.owns)
        }
    }

    /** The empty fast path must retain observation of a snackbar enqueued later. */
    @Test
    fun aSnackbarQueuedAfterTheEmptyClaimStillBecomesVisible() {
        val snackbars = SnackbarHostState()
        val host = TopNoticeHost(snackbars, notice = { null })
        var presents = false
        composeRule.setContent {
            CompositionLocalProvider(LocalTopNoticeHost provides host) {
                val slot = rememberTopNoticeSlot()
                val visible = slot.presents(slot.hostNotice, scope = null)
                SideEffect { presents = visible }
            }
        }
        composeRule.runOnIdle {
            assertFalse(presents)
            scope.launch { snackbars.showSnackbar("Saved", duration = SnackbarDuration.Indefinite) }
        }
        composeRule.runOnIdle { assertTrue(presents) }
    }
}
