package dev.ipf.whitenoise.android.ui.settings

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the production folder pointer handler, measured insertion and store callback on an emulator. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class FolderDragAndroidTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var ids by mutableStateOf(listOf("a", "b", "c"))
    private val moves = mutableListOf<Pair<String, Int>>()
    private lateinit var drag: FolderDragState
    private lateinit var list: LazyListState
    private var previousScale: String? = null

    /** Hosted emulators default to disabled animations; exercise enabled motion without changing later tests. */
    @Before
    fun enableMotion() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        val resolver = rule.activity.contentResolver
        previousScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        check(Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }

    /** Restore the emulator setting even when a pointer or geometry assertion fails. */
    @After
    fun restoreMotion() {
        try {
            rule.mainClock.autoAdvance = true
            if (::drag.isInitialized) rule.runOnUiThread { drag.cancel() }
        } finally {
            try {
                Settings.Global.putString(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
            } finally {
                InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
            }
        }
    }

    /** Reversing a held pointer updates the preview; release writes its final insertion once without an origin snap. */
    @Test
    fun pointerReversalCommitsTheDisplayedVariableHeightInsertionOnce() {
        render()
        val a = bounds("a")
        val b = bounds("b")
        val c = bounds("c")
        rule.mainClock.autoAdvance = false
        pointerTo(a.center, c.center, release = false)
        rule.mainClock.advanceTimeBy(160)
        assertEquals(listOf("a", "b", "c"), ids)
        assertTrue(bounds("b").top < b.top)
        val viewportOrigin = rule.onNodeWithTag("folders.viewport").fetchSemanticsNode().boundsInRoot.topLeft
        rule.onNodeWithTag("folders.viewport").performTouchInput { moveTo(b.center - viewportOrigin, 80) }
        rule.mainClock.advanceTimeBy(64)
        val beforeRelease = bounds("a")
        rule.onNodeWithTag("folders.viewport").performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        assertEquals("release must start at the rendered position", beforeRelease.top, bounds("a").top, 2f)
        rule.mainClock.advanceTimeBy(240)
        assertEquals(listOf("a" to 1), moves)
        assertEquals(listOf("b", "a", "c"), ids)
        assertTrue(bounds("a").top > bounds("b").top)
    }

    /** A rejected store write and a cancelled pointer restore the actual order, not the visual preview. */
    @Test
    fun rejectedSaveAndPointerCancellationKeepAuthoritativeOrder() {
        render(acceptSave = false)
        val origin = bounds("a")
        rule.mainClock.autoAdvance = false
        pointerTo(origin.center, bounds("c").center, release = true)
        rule.mainClock.advanceTimeBy(240)
        assertEquals(listOf("a" to 2), moves)
        assertEquals(listOf("a", "b", "c"), ids)
        assertEquals(origin.top, bounds("a").top, 2f)
        pointerTo(origin.center, bounds("b").center, release = false)
        rule.onNodeWithTag("folders.viewport").performTouchInput { cancel() }
        rule.mainClock.advanceTimeBy(240)
        assertEquals(1, moves.size)
        assertNull(drag.folderId)
        assertEquals(origin.top, bounds("a").top, 2f)
    }

    /** Consumed edge scroll keeps the held point in viewport space; external removal cannot later commit it. */
    @Test
    fun edgeScrollAndExternalRemovalRetireTheHeldPointerWithoutSaving() {
        ids = (0 until 20).map { "folder-$it" }
        render()
        rule.mainClock.autoAdvance = false
        val origin = bounds("folder-0")
        val edge = with(rule.density) { Offset(120.dp.toPx(), 280.dp.toPx()) }
        pointerTo(origin.center, edge, release = false)
        rule.mainClock.advanceTimeBy(240)
        assertTrue("the pointer must consume real list scrolling", list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
        val heldCenter = bounds("folder-0").center.y
        assertTrue("scroll must not drag the held row away from the pointer", kotlin.math.abs(heldCenter - edge.y) < with(rule.density) { 32.dp.toPx() })
        rule.runOnIdle { ids = ids.drop(1) }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("folders.viewport").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(240)
        assertNull(drag.folderId)
        assertTrue(moves.isEmpty())
    }

    /** Pointer coordinates use the stable viewport, so moving row transforms cannot move the input origin. */
    private fun pointerTo(from: Offset, to: Offset, release: Boolean) {
        val viewport = rule.onNodeWithTag("folders.viewport").fetchSemanticsNode().boundsInRoot.topLeft
        rule.onNodeWithTag("folders.viewport").performTouchInput {
            down(from - viewport)
            moveTo(to - viewport, 160)
            if (release) up()
        }
    }

    /** Captures placed bounds, including the production insertion translation. */
    private fun bounds(id: String) = rule.onNodeWithTag(id).fetchSemanticsNode().boundsInRoot

    /** Variable-height rows use the same pointer handle and placement modifiers as ChatFoldersScreen. */
    private fun render(acceptSave: Boolean = true) {
        rule.setContent {
            WhiteNoiseTheme {
                list = rememberLazyListState()
                drag = rememberFolderDrag(list, ids)
                LazyColumn(
                    Modifier.size(240.dp, 300.dp).testTag("folders.viewport"),
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ids, key = { it }) { id ->
                        Box(
                            Modifier.fillMaxWidth()
                                .height(if (id == "b") 80.dp else 48.dp)
                                .then(folderDragRowModifier(id, drag))
                                .folderDragHandle(id, drag, ids) { moved, delta ->
                                    moves += moved to delta
                                    if (acceptSave) {
                                        ids = ids.toMutableList().apply {
                                            val from = indexOf(moved)
                                            add(from + delta, removeAt(from))
                                        }
                                    }
                                }.testTag(id),
                        )
                    }
                }
            }
        }
    }
}
