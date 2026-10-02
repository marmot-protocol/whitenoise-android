package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.audio.ConversationDictationProvider
import dev.ipf.whitenoise.android.audio.discoverConversationDictationProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads installed Android speech services off Main and drops only a stale saved installation choice. */
internal suspend fun discoverDictationProvidersForSettings(
    context: Context,
    preferences: ConversationDictationPreferences,
): List<ConversationDictationProvider> {
    val providers = withContext(Dispatchers.IO) { discoverConversationDictationProviders(context) }
    val saved = preferences.current().providerSelection
    if (saved != null && providers.flatMap { it.choices }.none(saved::sameInstallation)) {
        preferences.setProviderSelection(null)
    }
    return providers
}
