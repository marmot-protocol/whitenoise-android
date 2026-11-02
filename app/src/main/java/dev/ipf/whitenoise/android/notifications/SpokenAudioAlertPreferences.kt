package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration

/**
 * Device-wide choice to keep message alerts silent while another app plays speech (#1717). Android plays
 * a channel's sound with a duckable focus request; it ducks music itself but hands a speech player the
 * focus loss, and podcast and audiobook players pause on it. On by default.
 */
internal object SpokenAudioAlertPreferences {
    const val KEY = "quiet_alerts_during_spoken_audio"

    /** Missing or unreadable preferences keep the default, which is on. */
    fun enabled(context: Context): Boolean {
        val stored = runCatching { preferences(context).getBoolean(KEY, true) }
        return stored.getOrDefault(true)
    }

    /** Persists the choice; only the next first post reads it. */
    fun setEnabled(
        context: Context,
        value: Boolean,
    ) {
        preferences(context).edit().putBoolean(KEY, value).apply()
    }

    /** The app's shared preference file, which also holds the notification preview choice. */
    private fun preferences(context: Context): SharedPreferences {
        val app = context.applicationContext
        return app.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
    }
}

/**
 * True when the choice is on, something is playing, and some player is tagged as speech for media. Android
 * hands apps only anonymized playback configurations, so this reads the kind of audio, never which app or
 * what it plays. Any failure answers false, so a platform error can only leave an alert audible.
 */
internal fun quietAlertForSpokenAudio(context: Context): Boolean =
    SpokenAudioAlertPreferences.enabled(context) &&
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return@runCatching false
            audio.isMusicActive && audio.activePlaybackConfigurations.any(::isSpeechPlayer)
        }.getOrDefault(false)

/**
 * A media player tagged as speech. The list also keeps paused and idle players, and an anonymized copy
 * carries neither the player's state nor its output device, so the caller's `isMusicActive` check is the
 * only sign that anything is playing.
 */
internal fun isSpeechPlayer(configuration: AudioPlaybackConfiguration): Boolean {
    val attributes = configuration.audioAttributes
    return attributes.contentType == AudioAttributes.CONTENT_TYPE_SPEECH && attributes.usage in SPEECH_MEDIA_USAGES
}

/** Usages a podcast or audiobook player plays under; calls, assistants and accessibility are left alone. */
private val SPEECH_MEDIA_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_UNKNOWN)
