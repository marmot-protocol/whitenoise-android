package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerToolbarPreferencesTest {
    /** Fresh preferences and Reset use the compact groups, with the voice launcher sharing Send's slot. */
    @Test
    fun freshDefaultsAndResetPersistCompactPhysicalGroups() =
        runTest {
            val prefs = preferences("compact-defaults")
            val owner = ComposerToolbarPreference(prefs, this, Mutex())
            val expected =
                ComposerToolbarSettings(
                    listOf(
                        ComposerShortcut.SendingIdentity,
                        ComposerShortcut.More,
                        ComposerShortcut.Emoji,
                    ),
                    rightShortcuts = setOf(ComposerShortcut.VoiceNote),
                )
            assertEquals(expected, owner.value)
            assertEquals(ComposerVoicePlacement.Send, owner.value.voicePlacement)
            val custom = ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Dictation))
            val saved = CompletableDeferred<Boolean>()
            owner.save(custom) { saved.complete(it) }
            assertTrue(saved.await())
            val reset = CompletableDeferred<Boolean>()
            // The editor's Reset action supplies this same default value to the preference owner.
            owner.save(ComposerToolbarSettings()) { reset.complete(it) }
            assertTrue(reset.await())
            assertEquals(expected, owner.value)
            assertEquals(expected, ComposerToolbarPreference(prefs, this, Mutex()).value)
        }

    /** Explicit version-2 order, layout and physical sides survive a new default layout. */
    @Test
    fun savedV2ChoicesKeepTheirOrderLayoutAndPhysicalSides() {
        val saved =
            ComposerToolbarSettings.decode(
                """{"version":2,"shortcuts":["dictation","more","voice_note","emoji","paste"],""" +
                    """"layout":"always_expanded","rightShortcuts":["more","emoji","paste"]}""",
            )
        assertEquals(
            listOf(
                ComposerShortcut.Dictation,
                ComposerShortcut.More,
                ComposerShortcut.VoiceNote,
                ComposerShortcut.Emoji,
                ComposerShortcut.Paste,
            ),
            saved.shortcuts,
        )
        assertEquals(ComposerToolbarLayout.AlwaysExpanded, saved.layout)
        assertEquals(
            listOf(ComposerShortcut.Dictation, ComposerShortcut.VoiceNote),
            saved.onSide(ComposerShortcutSide.Left),
        )
        assertEquals(
            listOf(ComposerShortcut.More, ComposerShortcut.Emoji, ComposerShortcut.Paste),
            saved.onSide(ComposerShortcutSide.Right),
        )
        assertEquals(saved, ComposerToolbarSettings.decode(saved.encode()))
    }

    /** Each voice placement survives storage; Send and Off never leave a pinned voice slot behind. */
    @Test
    fun voicePlacementsRoundTripAndPinningLeavesSendSlot() {
        val defaults = ComposerToolbarSettings()
        ComposerVoicePlacement.entries.forEach { placement ->
            val placed = defaults.withVoicePlacement(placement)
            assertEquals(placement, placed.voicePlacement)
            assertEquals(placed, ComposerToolbarSettings.decode(placed.encode()))
            assertEquals(
                placement == ComposerVoicePlacement.Left || placement == ComposerVoicePlacement.Right,
                ComposerShortcut.VoiceNote in placed.normalized().shortcuts,
            )
        }
        val left = defaults.withVoicePlacement(ComposerVoicePlacement.Left)
        assertEquals(ComposerShortcut.VoiceNote, left.onSide(ComposerShortcutSide.Left).last())
        assertEquals(ComposerVoicePlacement.Send, left.withVoicePlacement(ComposerVoicePlacement.Send).voicePlacement)
        // Adding the voice shortcut from the palette pins it, leaving Send's slot to Send.
        assertEquals(ComposerVoicePlacement.Right, defaults.insert(ComposerShortcut.VoiceNote, 9).voicePlacement)
        assertEquals(ComposerVoicePlacement.Send, left.without(ComposerShortcut.VoiceNote).voicePlacement)
    }

    /** Older records keep a pinned voice launcher; an unpinned one moves to Send's slot. */
    @Test
    fun switchedOffVoiceHidesEverywhereAndKeepsItsPlacement() {
        val left = ComposerToolbarSettings().withVoicePlacement(ComposerVoicePlacement.Left)
        val off = left.copy(voiceEnabled = false)
        assertFalse(off.voiceOnSend)
        assertFalse(ComposerShortcut.VoiceNote in off.displayedShortcuts)
        assertFalse(ComposerToolbarSettings().copy(voiceEnabled = false).voiceOnSend)
        val decoded = ComposerToolbarSettings.decode(off.encode())
        assertEquals(off, decoded)
        assertEquals(ComposerVoicePlacement.Left, decoded.copy(voiceEnabled = true).voicePlacement)
        assertTrue(ComposerShortcut.VoiceNote in decoded.copy(voiceEnabled = true).displayedShortcuts)
        assertEquals(
            ComposerToolbarSettings(),
            ComposerToolbarSettings.decode("""{"version":3,"shortcuts":[],"rightShortcuts":[],"voiceEnabled":"no"}"""),
        )
    }

    @Test
    fun earlierVersionsKeepTheirPinnedVoiceChoice() {
        val pinned =
            ComposerToolbarSettings.decode(
                """{"version":2,"shortcuts":["more","voice_note"],"layout":"automatic","rightShortcuts":[]}""",
            )
        assertEquals(ComposerVoicePlacement.Left, pinned.voicePlacement)
        val unpinned =
            ComposerToolbarSettings.decode(
                """{"version":2,"shortcuts":["more","emoji"],"layout":"automatic","rightShortcuts":[]}""",
            )
        assertEquals(ComposerVoicePlacement.Send, unpinned.voicePlacement)
    }

    @Test
    fun roundTripRetainsOrderAndIntentionalEmptyOptionalSelection() {
        val settings =
            ComposerToolbarSettings(
                listOf(ComposerShortcut.Camera, ComposerShortcut.More, ComposerShortcut.Gallery),
                ComposerToolbarLayout.AlwaysExpanded,
            )
        assertEquals(settings, ComposerToolbarSettings.decode(settings.encode()))
        assertEquals(
            listOf(ComposerShortcut.More),
            ComposerToolbarSettings
                .decode(
                    """{"version":1,"shortcuts":[],"layout":"automatic"}""",
                ).shortcuts,
        )
    }

    @Test
    fun unknownAndRepeatedIdsKeepSurvivingOrderWithoutAddingOptionalActions() {
        val settings =
            ComposerToolbarSettings.decode(
                """{"version":1,"shortcuts":["camera","future","camera","paste"],"layout":"future"}""",
            )
        assertEquals(listOf(ComposerShortcut.More, ComposerShortcut.Camera, ComposerShortcut.Paste), settings.shortcuts)
        assertEquals(ComposerToolbarLayout.Automatic, settings.layout)
    }

    @Test
    fun malformedVersionsAndPrimitiveTypesResetDeterministically() {
        listOf(
            "bad",
            "[]",
            """{"version":"1","shortcuts":[]}""",
            """{"version":2,"shortcuts":[]}""",
            """{"version":1,"shortcuts":[false]}""",
            """{"version":1,"shortcuts":"camera"}""",
            """{"version":4,"shortcuts":[],"rightShortcuts":[],"voiceOnSend":true}""",
        ).forEach { encoded ->
            assertEquals(ComposerToolbarSettings(), ComposerToolbarSettings.decode(encoded))
        }
    }

    @Test
    fun moreCannotBeRemovedAndMovingItDoesNotChangeOtherActions() {
        val settings =
            ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera, ComposerShortcut.Paste))
        assertEquals(settings, settings.without(ComposerShortcut.More))
        assertEquals(
            listOf(ComposerShortcut.Camera, ComposerShortcut.Paste, ComposerShortcut.More),
            settings.insert(ComposerShortcut.More, 2).shortcuts,
        )
    }

    @Test
    fun v1MigrationKeepsOrderAndAssignsOriginalPhysicalSides() {
        val migrated =
            ComposerToolbarSettings.decode(
                """{"version":1,"shortcuts":["paste","more","emoji","dictation","voice_note","camera"],""" +
                    """"layout":"automatic"}""",
            )
        assertEquals(listOf(ComposerShortcut.More, ComposerShortcut.Camera), migrated.onSide(ComposerShortcutSide.Left))
        assertEquals(
            listOf(
                ComposerShortcut.Paste,
                ComposerShortcut.Emoji,
                ComposerShortcut.Dictation,
                ComposerShortcut.VoiceNote,
            ),
            migrated.onSide(ComposerShortcutSide.Right),
        )
        val changed =
            migrated
                .withSide(ComposerShortcut.Camera, ComposerShortcutSide.Right)
                .withSide(ComposerShortcut.Paste, ComposerShortcutSide.Left)
        assertEquals(changed, ComposerToolbarSettings.decode(changed.encode()))
    }

    @Test
    fun rapidSavesAreSerializedAndTheFinalChoiceSurvivesANewOwner() =
        runTest {
            val prefs = preferences("completed")
            val lock = Mutex(locked = true)
            val owner = ComposerToolbarPreference(prefs, this, lock)
            val changed = ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera))
            val result = CompletableDeferred<Boolean>()
            owner.save(changed) { result.complete(it) }
            assertTrue(owner.saving)
            val finalChoice = ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Paste))
            val second = CompletableDeferred<Boolean>()
            owner.save(finalChoice) { second.complete(it) }
            assertEquals(finalChoice, owner.requestedValue)
            assertEquals(ComposerToolbarSettings(), owner.value)
            lock.unlock()
            assertTrue(result.await())
            assertTrue(second.await())
            assertEquals(finalChoice, owner.value)
            assertEquals(finalChoice, ComposerToolbarPreference(prefs, this, Mutex()).value)
            assertFalse(owner.failed)
        }

    @Test
    fun failedDiskCommitRestoresTheInMemoryPreferenceAndReportsFailure() =
        runTest {
            val actual = preferences("failed")
            val failing = failOnCommit(actual, 1)
            val owner = ComposerToolbarPreference(failing, this, Mutex())
            val result = CompletableDeferred<Boolean>()
            owner.save(
                ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera)),
            ) { result.complete(it) }
            assertFalse(result.await())
            assertEquals(ComposerToolbarSettings(), owner.value)
            assertEquals(owner.value, ComposerToolbarPreference(actual, this, Mutex()).value)
            assertTrue(owner.failed)
            assertEquals(listOf(ComposerShortcut.More, ComposerShortcut.Camera), owner.requestedValue.shortcuts)
            val retry = CompletableDeferred<Boolean>()
            owner.save(owner.requestedValue) { retry.complete(it) }
            assertTrue(retry.await())
            assertFalse(owner.failed)
            assertEquals(owner.requestedValue, owner.value)
        }

    @Test
    fun queuedFailureNeverReplacesTheLatestOutcomeAndFailedIntentCanRetry() =
        runTest {
            for (failAt in listOf(1, 2)) {
                val actual = preferences("mixed-$failAt")
                val lock = Mutex(locked = true)
                val owner = ComposerToolbarPreference(failOnCommit(actual, failAt), this, lock)
                val first = ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera))
                val last = ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Paste))
                val done = CompletableDeferred<Boolean>()
                owner.save(first) { }
                owner.save(last) { done.complete(it) }
                lock.unlock()
                assertEquals(failAt == 1, done.await())
                assertEquals(last, owner.requestedValue)
                assertEquals(failAt == 2, owner.failed)
                assertEquals(if (failAt == 1) last else first, owner.value)
                if (owner.failed) {
                    val retry = CompletableDeferred<Boolean>()
                    owner.save(owner.requestedValue) { retry.complete(it) }
                    assertTrue(retry.await())
                    assertFalse(owner.failed)
                    assertEquals(last, owner.value)
                }
                assertEquals(last, ComposerToolbarPreference(actual, this, Mutex()).value)
            }
        }

    /** Fails one selected durable write while retaining the real preference editor. */
    private fun failOnCommit(
        actual: SharedPreferences,
        failAt: Int,
    ): SharedPreferences {
        var commits = 0
        return object : SharedPreferences by actual {
            /** Wraps a real edit so its selected commit can report failure. */
            override fun edit(): SharedPreferences.Editor {
                val editor = actual.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(
                        key: String?,
                        value: String?,
                    ): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }

                    /** Commits the real values and reports failure only for the configured write. */
                    override fun commit(): Boolean {
                        val committed = editor.commit()
                        commits++
                        return committed && commits != failAt
                    }
                }
            }
        }
    }

    /** Returns an isolated preference file for one persistence scenario. */
    private fun preferences(name: String): SharedPreferences =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("composer-toolbar-test-$name", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
}
