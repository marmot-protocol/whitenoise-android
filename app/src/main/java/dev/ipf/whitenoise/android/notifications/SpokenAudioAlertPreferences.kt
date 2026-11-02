package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build

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
 * True when the choice is on and some player is producing speech for media now. Android hands apps only
 * anonymized playback configurations, so this reads the kind of audio, never which app or what it plays.
 * Any failure answers false, so a platform error can only leave an alert audible.
 */
internal fun quietAlertForSpokenAudio(context: Context): Boolean =
    SpokenAudioAlertPreferences.enabled(context) &&
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return@runCatching false
            audio.isMusicActive && audio.activePlaybackConfigurations.any(::isPlayingSpeech)
        }.getOrDefault(false)

/**
 * A media player tagged as speech that is playing now. The list also keeps paused and idle players; from
 * API 31 only a playing one reports an output device. API 30 has no per-player state, so there the caller's
 * `isMusicActive` check is the only sign that anything is playing.
 */
@Suppress("DEPRECATION")
internal fun isPlayingSpeech(configuration: AudioPlaybackConfiguration): Boolean {
    val attributes = configuration.audioAttributes
    val speechMedia =
        attributes.contentType == AudioAttributes.CONTENT_TYPE_SPEECH &&
            attributes.usage in SPEECH_MEDIA_USAGES
    return speechMedia &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || configuration.audioDeviceInfo != null)
}

/** Usages a podcast or audiobook player plays under; calls, assistants and accessibility are left alone. */
private val SPEECH_MEDIA_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_UNKNOWN)
