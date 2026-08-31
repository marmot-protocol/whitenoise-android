package dev.ipf.whitenoise.android.audio

import android.content.ComponentName

/** Parses Android's secure recognition-service setting, including relative service class names. */
internal fun conversationDictationRecognitionServiceComponent(configuredValue: String?): ComponentName? {
    val value = configuredValue?.trim()?.takeIf(String::isNotEmpty)
    val separator = value?.indexOf('/') ?: -1
    return if (value == null || separator <= 0 || separator == value.lastIndex) {
        null
    } else {
        val packageName = value.substring(0, separator)
        val configuredClassName = value.substring(separator + 1)
        val className =
            if (configuredClassName.startsWith('.')) {
                packageName + configuredClassName
            } else {
                configuredClassName
            }
        ComponentName(packageName, className)
    }
}

/** Reports whether Android's selected recognition service is still installed and discoverable. */
internal fun conversationDictationRecognitionServiceAvailable(
    selected: ComponentName?,
    discovered: Collection<ComponentName>,
): Boolean = selected != null && discovered.any { it == selected }
