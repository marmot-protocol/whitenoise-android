package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

/**
 * Only another player's speech, while something is actually playing, quiets an alert (#1717). Runs on the
 * minimum and newest API, because an app's anonymized playback configurations carry no player state or
 * output device on any release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class SpokenAudioAlertsTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val audio: ShadowAudioManager
        get() = Shadows.shadowOf(context.getSystemService(AudioManager::class.java))

    /** Starts from the default choice with nothing playing. */
    @Before
    fun setUp() {
        clearChoice()
        audio.setIsMusicActive(false)
        audio.setActivePlaybackConfigurationsFor(emptyList())
    }

    /** Leaves the shared preference file as other tests expect it. */
    @After
    fun tearDown() = clearChoice()

    /** The choice is off by default, so a podcast or audiobook player keeps the alert audible. */
    @Test
    fun playingSpeechKeepsAlertsAudibleByDefault() {
        playing(attributes(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH))
        assertFalse(SpokenAudioAlertPreferences.enabled(context))
        assertFalse(quietAlertForSpokenAudio(context))
    }

    /** With the choice on, a podcast or audiobook player quiets the alert. */
    @Test
    fun playingSpeechQuietsAlertsWhenOn() {
        SpokenAudioAlertPreferences.setEnabled(context, true)
        playing(attributes(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH))
        assertTrue(quietAlertForSpokenAudio(context))
    }

    /** Music is ducked by Android itself, so it keeps the alert audible. */
    @Test
    fun musicKeepsAlertsAudible() {
        SpokenAudioAlertPreferences.setEnabled(context, true)
        playing(attributes(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC))
        assertFalse(quietAlertForSpokenAudio(context))
    }

    /** Assistant and accessibility speech are not media, so they never quiet an alert. */
    @Test
    fun assistantAndAccessibilitySpeechKeepAlertsAudible() {
        SpokenAudioAlertPreferences.setEnabled(context, true)
        playing(
            attributes(AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH),
            attributes(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, AudioAttributes.CONTENT_TYPE_SPEECH),
        )
        assertFalse(quietAlertForSpokenAudio(context))
    }

    /** A speech player that is registered but not playing keeps the alert audible. */
    @Test
    fun idleSpeechPlayerKeepsAlertsAudible() {
        SpokenAudioAlertPreferences.setEnabled(context, true)
        audio.setActivePlaybackConfigurationsFor(
            listOf(attributes(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH)),
        )
        audio.setIsMusicActive(false)
        assertFalse(quietAlertForSpokenAudio(context))
    }

    /** Turning the choice on quiets alerts over speech, and turning it off again keeps them audible. */
    @Test
    fun turningTheChoiceOnAndOffAgain() {
        playing(attributes(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH))
        SpokenAudioAlertPreferences.setEnabled(context, true)
        assertTrue(quietAlertForSpokenAudio(context))
        SpokenAudioAlertPreferences.setEnabled(context, false)
        assertFalse(quietAlertForSpokenAudio(context))
    }

    /** Reports [players] as registered while media is playing. */
    private fun playing(vararg players: AudioAttributes) {
        audio.setActivePlaybackConfigurationsFor(players.toList())
        audio.setIsMusicActive(true)
    }

    /** Media attributes with the given [usage] and [contentType]. */
    private fun attributes(
        usage: Int,
        contentType: Int,
    ): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()

    /** Removes the stored choice so the default applies. */
    private fun clearChoice() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .remove(SpokenAudioAlertPreferences.KEY)
            .commit()
    }
}
