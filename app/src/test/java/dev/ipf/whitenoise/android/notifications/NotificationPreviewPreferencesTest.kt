package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.ipf.whitenoise.android.state.NotificationPreviewSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationPreviewPreferencesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** Only a preview containing message text opts out of Android's companion-device bridging. */
    @Test
    fun messageTextPreviewsAreLocalOnly() {
        for (messageText in listOf(false, true)) {
            val builder = androidx.core.app.NotificationCompat.Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
            val token = NotificationPreviewToken("test", 0L, allowed = true, messageText = messageText)
            NotificationPreviewPreferences.stamp(builder, token, correction = false)
            assertEquals(messageText, builder.build().flags and android.app.Notification.FLAG_LOCAL_ONLY != 0)
        }
    }

    /** Existing installs keep previews until an explicit opt-out, including after a new settings owner. */
    @Test
    fun defaultOnAndPersistedOptOutSurviveSettingsRecreation() =
        runBlocking {
            val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            preferences.edit().remove(NotificationPreviewPreferences.KEY).commit()
            assertTrue(NotificationPreviewPreferences.mode(context).showsNames)
            assertTrue(NotificationPreviewPreferences.setEnabled(context, false))
            assertEquals(NotificationPreviewMode.NONE, NotificationPreviewSettings.forContext(context).mode)
            assertFalse(preferences.getBoolean(NotificationPreviewPreferences.KEY, true))
        }

    /** Invalid preference storage cannot silently opt in to exposing private content. */
    @Test
    fun corruptPreferenceFailsClosed() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .putString(NotificationPreviewPreferences.KEY, "invalid")
            .commit()
        assertFalse(NotificationPreviewPreferences.mode(context).showsNames)
    }

    /** Failed disk commits hide content in memory and keep the exact choice available for retry. */
    @Test
    fun failedSaveRemainsHiddenAndRetryPersistsTheRequestedOptOut() =
        runBlocking {
            val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            preferences.edit().remove(NotificationPreviewPreferences.KEY).commit()
            var fail = true
            val wrapped = interceptCommits { editor -> !fail && editor.commit() }
            val settings = NotificationPreviewSettings.forContext(wrapped, scrub = { true })
            assertEquals(NotificationPreviewMode.FULL, settings.mode)
            settings.setMode(NotificationPreviewMode.NONE)
            assertEquals(NotificationPreviewMode.NONE, settings.mode)
            assertTrue(settings.failed)
            assertFalse(settings.busy)
            assertTrue(preferences.getBoolean(NotificationPreviewPreferences.KEY, true))
            fail = false
            settings.retry()
            assertEquals(NotificationPreviewMode.NONE, settings.mode)
            assertFalse(settings.failed)
            assertFalse(preferences.getBoolean(NotificationPreviewPreferences.KEY, true))
        }

    /** Posts prepared while enable is committing cannot inherit the later permission to expose content. */
    @Test
    fun enableCompletionInvalidatesTransitionTimePosts() =
        runBlocking {
            lateinit var wrapped: Context
            lateinit var token: NotificationPreviewToken
            wrapped =
                interceptCommits { editor ->
                    token = NotificationPreviewPreferences.capture(wrapped)
                    editor.commit()
                }
            assertTrue(NotificationPreviewPreferences.setEnabled(wrapped, true))
            val builder =
                androidx.core.app.NotificationCompat
                    .Builder(wrapped, NotificationChannelSpec.GROUP_MESSAGES.id)
            NotificationPreviewPreferences.stamp(builder, token, correction = false)
            assertFalse(NotificationPreviewPreferences.canExpose(wrapped, builder.build()))
        }

    /** A fresh process/application cannot confuse an old revision zero with its current session. */
    @Test
    fun newApplicationSessionRejectsOldProvenance() {
        val token = NotificationPreviewPreferences.capture(context)
        val builder =
            androidx.core.app.NotificationCompat
                .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
        NotificationPreviewPreferences.stamp(builder, token, correction = false)
        val otherApplication =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
            }
        assertFalse(NotificationPreviewPreferences.hasCurrentProvenance(otherApplication, builder.build()))
    }

    /** A current-epoch first silent post remains rich even after the preference was persisted on. */
    @Test
    fun firstSilentPostWithPersistedOnKeepsCurrentEpochProvenance() {
        val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
        preferences.edit().remove(NotificationPreviewPreferences.KEY).commit()
        val builder =
            androidx.core.app.NotificationCompat
                .Builder(context, "channel")
        NotificationPreviewPreferences.stamp(
            builder,
            NotificationPreviewPreferences.capture(context),
            correction = true,
        )
        val card = builder.build()
        assertTrue(NotificationPreviewPreferences.canExpose(context, card))
        preferences.edit().putBoolean(NotificationPreviewPreferences.KEY, true).commit()
        assertTrue(NotificationPreviewPreferences.canExpose(context, card))
        assertTrue(NotificationPreviewPreferences.canExpose(context, card, current = card))
    }

    /** Legacy cards keep previews by default, but an off/on transition permanently rejects their history. */
    @Test
    fun legacyPreviewSurvivesOnlyAnUntouchedSession() =
        runBlocking {
            val card =
                androidx.core.app.NotificationCompat
                    .Builder(context, "legacy-channel")
                    .build()
            assertTrue(NotificationPreviewPreferences.canRetainPreview(context, card))
            assertTrue(NotificationPreviewPreferences.setEnabled(context, false, scrub = { true }))
            assertTrue(NotificationPreviewPreferences.setEnabled(context, true, scrub = { true }))
            assertFalse(NotificationPreviewPreferences.canRetainPreview(context, card))
        }

    /** Cleanup failures retain the persisted opt-out and the same Retry choice. */
    @Test
    fun cleanupExceptionIsRetryableWithoutOptingIn() =
        runBlocking {
            var fail = true
            val settings =
                NotificationPreviewSettings.forContext(context, scrub = {
                    if (fail) error("simulated platform failure")
                    true
                })
            settings.setMode(NotificationPreviewMode.NONE)
            assertEquals(NotificationPreviewMode.NONE, settings.mode)
            assertTrue(settings.failed)
            assertFalse(settings.busy)
            fail = false
            settings.retry()
            assertEquals(NotificationPreviewMode.NONE, settings.mode)
            assertFalse(settings.failed)
        }

    /** Upgrades keep full previews; only a current-epoch saved choice can narrow an opt-out to name-only. */
    @Test
    fun legacyKeyMigratesAndNameOnlyPersists() =
        runBlocking {
            val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            preferences
                .edit()
                .remove(NotificationPreviewPreferences.KEY)
                .remove(NotificationPreviewPreferences.MODE_KEY)
                .commit()
            assertEquals(NotificationPreviewMode.FULL, NotificationPreviewPreferences.mode(context))
            preferences.edit().putBoolean(NotificationPreviewPreferences.KEY, false).commit()
            assertEquals(NotificationPreviewMode.NONE, NotificationPreviewPreferences.mode(context))
            assertTrue(
                NotificationPreviewPreferences.setMode(context, NotificationPreviewMode.NAME_ONLY, scrub = { true }),
            )
            assertEquals(NotificationPreviewMode.NAME_ONLY, NotificationPreviewPreferences.mode(context))
            assertFalse(preferences.getBoolean(NotificationPreviewPreferences.KEY, true))
            assertEquals("name_only", preferences.getString(NotificationPreviewPreferences.MODE_KEY, null))
            val token = NotificationPreviewPreferences.capture(context)
            assertTrue(token.allowed)
            assertFalse(token.messageText)
        }

    /** An older build's later on/off choice wins over a mode it could not read. */
    @Test
    fun olderBuildChoiceAfterDowngradeWinsOnUpgrade() =
        runBlocking {
            val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            assertTrue(
                NotificationPreviewPreferences.setMode(context, NotificationPreviewMode.NAME_ONLY, scrub = { true }),
            )
            val epochKey = "notification_preview_epoch"
            val epoch = preferences.getLong(epochKey, 0L)
            // The older build turned previews on, then off again, advancing only its epoch.
            preferences
                .edit()
                .putBoolean(NotificationPreviewPreferences.KEY, true)
                .putLong(epochKey, epoch + 1)
                .commit()
            assertEquals(NotificationPreviewMode.FULL, NotificationPreviewPreferences.mode(context))
            preferences
                .edit()
                .putBoolean(NotificationPreviewPreferences.KEY, false)
                .putLong(epochKey, epoch + 2)
                .commit()
            assertEquals(NotificationPreviewMode.NONE, NotificationPreviewPreferences.mode(context))
        }

    /** A value this build does not know never widens what a card may show. */
    @Test
    fun unknownStoredModeFailsClosed() =
        runBlocking {
            val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            assertTrue(
                NotificationPreviewPreferences.setMode(context, NotificationPreviewMode.NAME_ONLY, scrub = { true }),
            )
            preferences.edit().putString(NotificationPreviewPreferences.MODE_KEY, "future_mode").commit()
            assertEquals(NotificationPreviewMode.NONE, NotificationPreviewPreferences.mode(context))
        }

    /** Every mode change voids older cards, and only modes that hide message text scrub the tray. */
    @Test
    fun modeChangesInvalidateCardsAndScrubOnlyWhenTextIsHidden() =
        runBlocking {
            assertTrue(NotificationPreviewPreferences.setMode(context, NotificationPreviewMode.FULL, scrub = { true }))
            val builder =
                androidx.core.app.NotificationCompat
                    .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
            NotificationPreviewPreferences.stamp(
                builder,
                NotificationPreviewPreferences.capture(context),
                correction = false,
            )
            val fullCard = builder.build()
            assertTrue(NotificationPreviewPreferences.canExpose(context, fullCard))
            var scrubs = 0
            val countScrub: suspend () -> Boolean = {
                scrubs++
                true
            }
            assertTrue(NotificationPreviewPreferences.setMode(context, NotificationPreviewMode.NAME_ONLY, countScrub))
            assertEquals(1, scrubs)
            assertFalse(NotificationPreviewPreferences.canExpose(context, fullCard))
            assertTrue(NotificationPreviewPreferences.setMode(context, NotificationPreviewMode.FULL, countScrub))
            assertEquals(1, scrubs)
            assertTrue(NotificationPreviewPreferences.setMode(context, NotificationPreviewMode.NONE, countScrub))
            assertEquals(2, scrubs)
        }

    /** Name-only keeps only cards stamped without message text, so a full card from an earlier process stays hidden. */
    @Test
    fun nameOnlyRetainsOnlyCardsPreparedWithoutMessageText() =
        runBlocking {
            assertTrue(
                NotificationPreviewPreferences.setMode(context, NotificationPreviewMode.NAME_ONLY, scrub = { true }),
            )
            val current = NotificationPreviewPreferences.capture(context)
            val earlierFull = current.copy(session = "earlier-process", messageText = true)
            assertFalse(NotificationPreviewPreferences.canRetainPreview(context, stampedCard(earlierFull)))
            assertTrue(NotificationPreviewPreferences.canRetainPreview(context, stampedCard(current)))
            val unstamped =
                androidx.core.app.NotificationCompat
                    .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
                    .build()
            assertFalse(NotificationPreviewPreferences.canRetainPreview(context, unstamped))
        }

    private fun stampedCard(token: NotificationPreviewToken) =
        androidx.core.app.NotificationCompat
            .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
            .also { NotificationPreviewPreferences.stamp(it, token, correction = false) }
            .build()

    private fun interceptCommits(commit: (SharedPreferences.Editor) -> Boolean): Context =
        object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(
                name: String,
                mode: Int,
            ): SharedPreferences {
                val original = super.getSharedPreferences(name, mode)
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putBoolean(
                                key: String?,
                                value: Boolean,
                            ): SharedPreferences.Editor {
                                editor.putBoolean(key, value)
                                return this
                            }

                            override fun commit(): Boolean = commit(editor)
                        }
                    }
                }
            }
        }
}
