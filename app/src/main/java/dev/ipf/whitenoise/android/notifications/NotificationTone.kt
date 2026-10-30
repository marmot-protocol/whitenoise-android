package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R

/** Permanent public tone tokens: retained channel URIs must survive resource ID changes and restore. */
internal enum class NotificationTone(
    val token: String,
    @param:RawRes val audio: Int,
    @param:StringRes val title: Int,
) {
    DING("ding-v1", R.raw.notification_ding_v1, R.string.notification_tone_ding),
    PULSE("pulse-v1", R.raw.notification_pulse_v1, R.string.notification_tone_pulse),
    ;

    fun uri(context: Context): Uri = legacyUri(context).buildUpon().appendPath(filename.removeSuffix(".wav")).build()

    fun legacyUri(context: Context): Uri = Uri.parse("content://${context.packageName}.notification-tones/$token")

    val filename: String get() =
        when (this) {
            DING -> "White Noise Ding.wav"
            PULSE -> "White Noise Pulse.wav"
        }

    companion object {
        val mimeType: String get() =
            android.webkit.MimeTypeMap
                .getSingleton()
                .getMimeTypeFromExtension("wav") ?: "audio/x-wav"

        val audioAttributes: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        fun resolve(
            context: Context,
            uri: Uri,
        ): NotificationTone? = entries.firstOrNull { it.uri(context) == uri || it.legacyUri(context) == uri }
    }
}
