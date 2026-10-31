package dev.ipf.whitenoise.android.media.sandbox

import android.content.pm.ApplicationInfo
import dev.ipf.whitenoise.android.media.MediaPipeline

/**
 * Runs once in the still-image worker's application zygote, before any worker generation exists.
 * The zygote has its own isolated UID and never receives descriptors, account state or untrusted
 * bytes, each generation forks from it into a fresh isolated process, skipping APK class-loader setup.
 * An OS that exec-spawns isolated services instead (GrapheneOS by default) runs this in each new
 * worker before its service starts, still before any input.
 */
class MediaZygotePreload : android.app.ZygotePreload {
    /**
     * Initializes the worker's codec-path classes so each fork starts warm. Their static state is
     * constant, they start no threads and open no files: the zygote must stay single-threaded to fork.
     */
    override fun doPreload(appInfo: ApplicationInfo) {
        listOf(
            MediaDecodeService::class.java,
            PlatformMediaDecoder::class.java,
            MediaPipeline::class.java,
            MediaDecodePolicy::class.java,
        ).forEach { Class.forName(it.name, true, it.classLoader) }
        // Initializes the animation-admission file facade that guards every still decode.
        dev.ipf.whitenoise.android.media.malformedAnimationSource
    }
}
