package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Plays a single bounded preview; no pattern repeats and a new preview cancels the old one. */
fun previewConversationVibration(
    context: Context,
    pattern: ConversationVibrationPattern,
) {
    val vibrator = conversationVibrator(context)?.takeIf { it.hasVibrator() } ?: return
    val effect =
        pattern.waveform?.let { VibrationEffect.createWaveform(it.copyOf(), -1) }
            ?: VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    runCatching {
        vibrator.cancel()
        vibrator.vibrate(effect)
    }
}

private fun conversationVibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }
