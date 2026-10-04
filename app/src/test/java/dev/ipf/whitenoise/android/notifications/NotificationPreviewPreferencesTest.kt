package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.ipf.whitenoise.android.state.NotificationPreviewSettings
import kotlinx.coroutines.runBlocking
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

    /** Existing installs keep previews until an explicit opt-out, including after a new settings owner. */
    @Test
    fun defaultOnAndPersistedOptOutSurviveSettingsRecreation() =
        runBlocking {
            val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            preferences.edit().remove(NotificationPreviewPreferences.KEY).commit()
            assertTrue(NotificationPreviewPreferences.enabled(context))
            assertTrue(NotificationPreviewPreferences.setEnabled(context, false))
            assertFalse(NotificationPreviewSettings.forContext(context).enabled)
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
        assertFalse(NotificationPreviewPreferences.enabled(context))
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
            assertTrue(settings.enabled)
            settings.setEnabled(false)
            assertFalse(settings.enabled)
            assertTrue(settings.failed)
            assertFalse(settings.busy)
            assertTrue(preferences.getBoolean(NotificationPreviewPreferences.KEY, true))
            fail = false
            settings.retry()
            assertFalse(settings.enabled)
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

    /** A first silent default-on card is supported; persisted choices cannot restore missing corrections. */
    @Test
    fun firstSilentPostRequiresTheUntouchedDefaultOrALiveVisibleCard() {
        val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
        preferences.edit().remove(NotificationPreviewPreferences.KEY).commit()
        val builder = androidx.core.app.NotificationCompat.Builder(context, "channel")
        NotificationPreviewPreferences.stamp(
            builder,
            NotificationPreviewPreferences.capture(context),
            correction = true,
        )
        val card = builder.build()
        assertTrue(NotificationPreviewPreferences.canExpose(context, card))
        preferences.edit().putBoolean(NotificationPreviewPreferences.KEY, true).commit()
        assertFalse(NotificationPreviewPreferences.canExpose(context, card))
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
            settings.setEnabled(false)
            assertFalse(settings.enabled)
            assertTrue(settings.failed)
            assertFalse(settings.busy)
            fail = false
            settings.retry()
            assertFalse(settings.enabled)
            assertFalse(settings.failed)
        }

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
