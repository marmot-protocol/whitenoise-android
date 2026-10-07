package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.diagnostics.DmCreationInteraction
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Reproduces Compose cancelling an old preparation scope before starting the replacement recipient effect. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DmPreparationEffectOwnershipTest {
    @get:Rule val composeRule = createComposeRule()

    /** Key replacement records the old unfinished ordinal even when its Deferreds are already cancelled. */
    @Test
    fun composeScopeCancellationBeforeReplacementKeepsOwnerAttribution() {
        val records = mutableListOf<Map<String, Any>>()
        val interaction = DmCreationInteraction(records::add)
        val coordinator = NewMessageRecipientPreparationCoordinator()
        val key = mutableStateOf(NewMessageRecipientPreparationKey("a", 1, "q", "p", 0))
        val mounted = mutableStateOf(true)
        var disposed = false
        val heldPrewarm = CompletableDeferred<Unit>()
        val heldLookup = CompletableDeferred<NewMessageDirectChatResolution>()
        composeRule.setContent {
            if (mounted.value) {
                DisposableEffect(Unit) {
                    onDispose {
                        coordinator.clear()
                        disposed = true
                    }
                }
                val capturedKey = key.value
                LaunchedEffect(capturedKey) {
                    coordinator
                        .prepare(
                            this,
                            capturedKey,
                            prewarm = { if (capturedKey.retryKey == 0) heldPrewarm.await() },
                            lookup = {
                                if (capturedKey.retryKey == 0) {
                                    heldLookup.await()
                                } else {
                                    NewMessageDirectChatResolution(null, true)
                                }
                            },
                            diagnosticAttempt = interaction.preparation(),
                        ).awaitCompletion()
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(2, records.count { it["attempt"] == 0L && it["outcome"] == "start" })
            key.value = key.value.copy(retryKey = 1)
            Snapshot.sendApplyNotifications()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val ownerRecords = records.filter { it["phase"] == "owner" }
            assertEquals(1, ownerRecords.size)
            assertEquals(0L, ownerRecords.single()["attempt"])
            assertEquals("owner_replaced", ownerRecords.single()["failure"])
            assertEquals(2, records.count { it["attempt"] == 0L && it["outcome"] == "cancelled" })
            assertTrue(records.any { it["attempt"] == 1L && it["outcome"] == "success" })
            mounted.value = false
            Snapshot.sendApplyNotifications()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(disposed)
            assertEquals(1, records.count { it["phase"] == "owner" })
        }
    }
}
