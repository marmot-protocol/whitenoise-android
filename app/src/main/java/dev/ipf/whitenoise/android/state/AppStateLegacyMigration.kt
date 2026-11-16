package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun WhiteNoiseAppState.migrateLegacyMutePreferences() {
    chatMutePreferences.legacyMuteEntries().forEach { legacy ->
        val authoritative =
            chatMuteRepository.settings(legacy.accountRef, legacy.groupIdHex).getOrNull()
                ?: return@forEach
        if (legacy.expiryMillis?.let { it <= System.currentTimeMillis() } == true) {
            chatMutePreferences.setNotifyForMode(legacy.accountRef, legacy.groupIdHex, legacy.restoreMode)
            chatMutePreferences.confirmLegacyMuteMigrated(legacy.key)
            return@forEach
        }
        // Existing MDK state always wins. A local-only legacy mute is copied once.
        val confirmed =
            if (authoritative.muted || authoritative.updatedAtMs > 0L) {
                authoritative
            } else {
                chatMuteRepository
                    .setMuted(
                        legacy.accountRef,
                        legacy.groupIdHex,
                        legacy.expiryMillis?.takeIf { it > System.currentTimeMillis() },
                    ).getOrNull()
            }
        if (confirmed?.muted == true) {
            chatMutePreferences.setNotifyForMode(
                legacy.accountRef,
                legacy.groupIdHex,
                legacy.restoreMode,
            )
            chatMutePreferences.confirmLegacyMuteMigrated(legacy.key)
        }
    }
}

internal suspend fun WhiteNoiseAppState.migrateLegacyDrafts() {
    val accountRefsById = accounts.associate { it.accountIdHex to it.label }
    val legacyDrafts = withContext(Dispatchers.IO) { legacyDraftMigrationSource.read() }
    legacyDrafts.forEach { (legacyKey, encoded) ->
        migrateLegacyDraft(accountRefsById, legacyKey, encoded)
    }
}

private suspend fun WhiteNoiseAppState.migrateLegacyDraft(
    accountRefsById: Map<String, String>,
    legacyKey: String,
    encoded: String,
) {
    val separator = legacyKey.indexOf(' ')
    separator.takeIf { it > 0 && it < legacyKey.lastIndex }?.let { validSeparator ->
        accountRefsById[legacyKey.substring(0, validSeparator)]?.let { accountRef ->
            val groupIdHex = legacyKey.substring(validSeparator + 1)
            val legacyContent = decodeLegacyDraftForMigration(encoded)
            if (legacyContent == null) {
                confirmLegacyDraftMigrated(legacyKey)
            } else {
                migrateDecodedLegacyDraft(legacyKey, accountRef, groupIdHex, legacyContent)
            }
        }
    }
}

private suspend fun WhiteNoiseAppState.migrateDecodedLegacyDraft(
    legacyKey: String,
    accountRef: String,
    groupIdHex: String,
    legacyContent: String,
) {
    val currentResult = messageDraftRepository.draft(accountRef, groupIdHex)
    val current = currentResult.getOrNull()
    if (currentResult.isSuccess) {
        val result =
            if (current == null || (current.content.isBlank() && legacyContent.isNotBlank())) {
                messageDraftRepository.saveText(accountRef, groupIdHex, legacyContent)
            } else {
                MessageDraftMutationResult.Success(current)
            }
        (result as? MessageDraftMutationResult.Success)?.let { confirmed ->
            val matchesLegacy = confirmed.draft?.content == legacyContent
            val confirmedBlankDeletion = legacyContent.isBlank() && confirmed.draft == null
            val authoritativeContentWins = current?.content?.isNotBlank() == true
            if (matchesLegacy || confirmedBlankDeletion || authoritativeContentWins) {
                confirmLegacyDraftMigrated(legacyKey)
            }
        }
    }
}

private suspend fun WhiteNoiseAppState.confirmLegacyDraftMigrated(legacyKey: String) {
    withContext(Dispatchers.IO) { legacyDraftMigrationSource.confirmMigrated(legacyKey) }
}
