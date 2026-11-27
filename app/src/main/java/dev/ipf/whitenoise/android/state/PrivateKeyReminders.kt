package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AccountSummaryFfi
import java.util.Locale

/** Where the reminder to save the private key stands for one account created by Sign up on this device. */
internal enum class PrivateKeyReminderStage {
    /** Sign up created the key here and it has not been copied or exported yet. */
    Pending,

    /** The private key was copied or exported, plain or encrypted. */
    Saved,

    /** The user chose Not now. */
    Dismissed,
}

/**
 * Remembers, per account, whether the chat list still reminds the user to save the private key (#4279). Only Sign
 * up on this device records an account, so imported, Amber and remote-signer accounts, and accounts created before
 * this existed, are never reminded. Copying or exporting the key, or Not now, retires the reminder for that account
 * alone. These are host presentation flags; they never touch the key.
 */
@Stable
internal class PrivateKeyReminders(
    private val preferences: SharedPreferences,
) {
    // Bumped on every write so composables that read a stage are redrawn.
    private var revision by mutableIntStateOf(0)

    /** The stage recorded for [accountIdHex], or null when the account was not created by Sign up here. */
    fun stage(accountIdHex: String?): PrivateKeyReminderStage? {
        revision
        val stored = accountIdHex?.let { preferences.getString(key(it), null) }
        return PrivateKeyReminderStage.entries.firstOrNull { it.name == stored }
    }

    /**
     * Whether the chat list shows the reminder for [account]: created by Sign up here and not yet retired, holding
     * its own key, signed in, and with at least one conversation ([hasConversations]).
     */
    fun shows(
        account: AccountSummaryFfi?,
        hasConversations: Boolean,
    ): Boolean {
        val ownsKey = account != null && account.localSigning && !account.externalSigning && !account.signedOut
        return ownsKey && hasConversations && stage(account.accountIdHex) == PrivateKeyReminderStage.Pending
    }

    /** Sign up created [accountIdHex] on this device, so its key exists nowhere else yet. */
    fun recordSignUp(accountIdHex: String) = write(accountIdHex, PrivateKeyReminderStage.Pending)

    /** The private key of [accountIdHex] was copied or exported. */
    fun markKeySaved(accountIdHex: String) = retire(accountIdHex, PrivateKeyReminderStage.Saved)

    /** Not now for [accountIdHex]. */
    fun dismiss(accountIdHex: String) = retire(accountIdHex, PrivateKeyReminderStage.Dismissed)

    /** Forgets [accountIdHex] after it signs out or is wiped, so a later sign-in with the key is never reminded. */
    fun forget(accountIdHex: String) = write(accountIdHex, null)

    private fun retire(
        accountIdHex: String,
        stage: PrivateKeyReminderStage,
    ) {
        if (stage(accountIdHex) == PrivateKeyReminderStage.Pending) write(accountIdHex, stage)
    }

    private fun write(
        accountIdHex: String,
        stage: PrivateKeyReminderStage?,
    ) {
        val editor = preferences.edit()
        if (stage == null) editor.remove(key(accountIdHex)) else editor.putString(key(accountIdHex), stage.name)
        editor.apply()
        revision++
    }

    private fun key(accountIdHex: String) = KEY_PREFIX + accountIdHex.trim().lowercase(Locale.ROOT)

    private companion object {
        const val KEY_PREFIX = "private_key_reminder."
    }
}
