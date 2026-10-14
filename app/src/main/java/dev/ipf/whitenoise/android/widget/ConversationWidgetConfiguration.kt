package dev.ipf.whitenoise.android.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import dev.ipf.whitenoise.android.state.AndroidKeystoreSecretKeyProvider
import dev.ipf.whitenoise.android.state.KeystoreSecureStore
import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import org.json.JSONObject

internal data class ConversationWidgetChoice(
    val account: String,
    val allAccounts: Boolean = false,
    val previews: Boolean = false,
    val unreadOnly: Boolean = false,
)

/** Only launcher/UI configuration is durable. Rows are rebuilt from current native projections. */
internal class ConversationWidgetConfiguration(
    context: Context,
) {
    private val store = KeystoreSecureStore(context, STORE_NAME, keyProvider)

    init {
        // This unreleased configuration had no encrypted migration source. Reconfigure safely
        // rather than retain account selectors or revive a legacy preview preference in plaintext.
        context
            .getSharedPreferences("conversation_widget_choices", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun read(id: Int): ConversationWidgetChoice? =
        synchronized(mutationLock) {
            runCatching {
                val choice = store.readAll()[id.toString()]?.let(::JSONObject) ?: return null
                val account = choice.getString("account").takeIf { it.isNotBlank() } ?: return null
                ConversationWidgetChoice(
                    account,
                    choice.optBoolean("all", false),
                    false,
                    choice.optBoolean("unread", false),
                )
            }.getOrNull()
        }

    fun save(
        id: Int,
        choice: ConversationWidgetChoice,
    ): Boolean {
        require(id > 0 && choice.account.isNotBlank())
        val value =
            JSONObject()
                .put("account", choice.account)
                .put("all", choice.allAccounts)
                .put("unread", choice.unreadOnly)
                .toString()
        return synchronized(mutationLock) { store.putAllDurably(mapOf(id.toString() to value)) }
    }

    fun remove(id: Int) {
        synchronized(mutationLock) { store.write(id.toString(), null) }
    }

    companion object {
        // Each configuration instance wraps its own store; all updates share one encrypted map.
        private val mutationLock = Any()
        private const val STORE_NAME = "conversation_widget_choices.keystore"
        private const val KEY_ALIAS = "whitenoise_conversation_widget_choices"
        private var keyProvider: SecureStoreKeyProvider = AndroidKeystoreSecretKeyProvider(KEY_ALIAS)

        /** Robolectric cannot load Android Keystore; production always uses its existing key. */
        @VisibleForTesting
        internal fun installKeyProvider(provider: SecureStoreKeyProvider?) {
            keyProvider = provider ?: AndroidKeystoreSecretKeyProvider(KEY_ALIAS)
        }
    }
}

internal data class ConversationWidgetRow(
    val account: String,
    val group: String,
    val title: String,
    val unread: Boolean,
    val preview: String? = null,
    val activity: ULong = 0uL,
)

internal fun selectConversationWidgetRows(
    choice: ConversationWidgetChoice,
    accounts: Set<String>,
    unlocked: Boolean,
    rows: List<ConversationWidgetRow>,
    limit: Int,
): List<ConversationWidgetRow> {
    if (!unlocked || choice.account !in accounts) return emptyList()
    return rows
        .asSequence()
        .filter { it.account in accounts && (choice.allAccounts || it.account == choice.account) }
        .filter { !choice.unreadOnly || it.unread }
        .distinctBy { it.account to it.group }
        .sortedByDescending { it.activity }
        .take(limit.coerceIn(1, MAX_WIDGET_ROWS))
        .map { it.copy(preview = null) }
        .toList()
}

internal const val MAX_WIDGET_ROWS = 8
