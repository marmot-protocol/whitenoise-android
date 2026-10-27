package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendMotion
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Modal state must gate outgoing ink without restarting the conversation that owns its transcript. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationMediaViewerSendMotionTest {
    @get:Rule val rule = createComposeRule()
    private val state = ConversationMediaViewerSessionState(ConversationMediaViewerOwner("account", "group", 1))
    private val motion = ComposerSendMotion()
    private val enabled = mutableStateOf(true)
    private var transcriptCompositions = 0
    private lateinit var textLayout: TextLayoutResult
    private lateinit var coordinates: LayoutCoordinates

    /** Idle ink must not subscribe its canvas to eligibility changes or probe attached geometry. */
    @Test
    fun idleOverlayReadsNoSnapshotStateWithAnAttachedOrigin() {
        render()
        val drawScope = CanvasDrawScope()
        rule.runOnIdle {
            assertTrue(coordinates.isAttached)
            val reads = mutableSetOf<Any>()
            Snapshot.observe({ reads.add(it) }, null) {
                with(motion) { drawScope.drawFlight(null, coordinates, null) }
            }
            assertTrue("Idle canvas subscribed to snapshot state", reads.isEmpty())
        }
    }

    @Test
    fun idleMediaOpenPagingAndDismissOnlyRecomposeTheSendGuard() {
        render()
        val initialCompositions = transcriptCompositions
        rule.runOnIdle { assertTrue(state.open(request())) }
        rule.runOnIdle {
            assertFalse(motion.enabled)
            assertEquals(initialCompositions, transcriptCompositions)
            state.selectPage(checkNotNull(state.active).sessionId, ConversationMediaViewerAttachmentId("sibling", 1))
        }
        rule.runOnIdle {
            assertFalse(motion.enabled)
            assertEquals(initialCompositions, transcriptCompositions)
            state.dismiss(checkNotNull(state.active).sessionId)
        }
        rule.runOnIdle {
            assertTrue(motion.enabled)
            assertEquals(initialCompositions, transcriptCompositions)
        }
    }

    @Test
    fun closingMediaNeverOverridesASeparateMotionSuppression() {
        render()
        rule.runOnIdle { state.open(request()) }
        rule.runOnIdle {
            assertFalse(motion.enabled)
            enabled.value = false
        }
        rule.runOnIdle { state.dismiss(checkNotNull(state.active).sessionId) }
        rule.runOnIdle {
            assertFalse(motion.enabled)
            enabled.value = true
        }
        rule.runOnIdle { assertTrue(motion.enabled) }
    }

    /** Opening media retires protected ink and fences a delayed callback even after media closes. */
    @Test
    fun mediaOpenRetiresAnActualFlightAndRejectsItsOldCallbackAfterReturn() {
        render()
        val bounds = Rect(0f, 0f, 300f, 80f)
        val source =
            ComposerSendSource(
                text = "Transcript remains mounted",
                ownerGeneration = 0L,
                layout = textLayout,
                surface = bounds,
                editor = bounds,
                fill = Color.Black,
                border = Color.Transparent,
                topRadius = 0f,
                bottomRadius = 0f,
                strokeWidth = 0f,
                paragraphDirection = ResolvedTextDirection.Ltr,
            )
        rule.runOnIdle {
            assertTrue(motion.accept(source, "accepted-row", 1L))
            state.open(request())
        }
        rule.runOnIdle {
            assertFalse(motion.enabled)
            assertNull(motion.flight)
            state.dismiss(checkNotNull(state.active).sessionId)
        }
        rule.runOnIdle {
            assertTrue(motion.enabled)
            assertFalse(motion.accept(source, "stale-row", 1L))
            assertNull(motion.flight)
        }
    }

    private fun render() {
        rule.setContent {
            SideEffect { transcriptCompositions++ }
            ComposerSendMotionMediaGate(motion, state, enabled.value)
            Text(
                "Transcript remains mounted",
                modifier = Modifier.onGloballyPositioned { coordinates = it },
                onTextLayout = { textLayout = it },
            )
        }
        rule.waitForIdle()
        assertTrue(motion.enabled)
    }

    private fun request() =
        ConversationMediaViewerOpenRequest(
            messageIdHex = "message",
            attachments =
                listOf(
                    IndexedValue(
                        0,
                        MediaAttachmentReferenceFfi(
                            locators = emptyList(),
                            ciphertextSha256 = "aa".repeat(32),
                            plaintextSha256 = "bb".repeat(32),
                            nonceHex = "cc".repeat(12),
                            fileName = "fixture.png",
                            mediaType = "image/png",
                            version = EncryptedMediaVersionFfi.V1,
                            sourceEpoch = 1uL,
                            dim = "64x32",
                            thumbhash = null,
                        ),
                    ),
                ),
            tappedAttachmentIndex = 0,
            sender = "self",
            recordedAt = 1uL,
            mine = true,
        )
}
