package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences

/**
 * What this device remembers about one account's sign-in alerts. All of it is presentation state for this device:
 * MDK keeps the sign-ins, the answers and the warning.
 */
internal data class SignInAlertRecord(
    /** Sign-ins the user answered "Mine, don't link" here, by slot. MDK has no answer for this yet (marmot#519). */
    val mine: Set<String> = emptySet(),
    /** Sign-ins already announced by a notification, by slot. */
    val announcedSignIns: Set<String> = emptySet(),
    /** Warning items already announced by a notification. */
    val announcedWarning: Set<String> = emptySet(),
    /** Warning items the full-screen alert has already shown. */
    val alertedWarning: Set<String> = emptySet(),
)

/** Keeps [SignInAlertRecord]s per account, keyed by the account's public key. */
internal interface SignInAlertStore {
    /** The record for [accountIdHex]; empty when none was kept. */
    fun read(accountIdHex: String): SignInAlertRecord

    /** Replaces the record for [accountIdHex]. */
    fun write(
        accountIdHex: String,
        record: SignInAlertRecord,
    )

    fun clear(accountIdHex: String) = write(accountIdHex, SignInAlertRecord())
}

/** [SignInAlertStore] on this device's shared preferences. */
internal class PreferencesSignInAlertStore(
    private val preferences: SharedPreferences,
) : SignInAlertStore {
    override fun read(accountIdHex: String) =
        SignInAlertRecord(
            mine = set(accountIdHex, MINE),
            announcedSignIns = set(accountIdHex, ANNOUNCED_SIGN_INS),
            announcedWarning = set(accountIdHex, ANNOUNCED_WARNING),
            alertedWarning = set(accountIdHex, ALERTED_WARNING),
        )

    override fun write(
        accountIdHex: String,
        record: SignInAlertRecord,
    ) {
        preferences
            .edit()
            .putStringSet(key(accountIdHex, MINE), record.mine)
            .putStringSet(key(accountIdHex, ANNOUNCED_SIGN_INS), record.announcedSignIns)
            .putStringSet(key(accountIdHex, ANNOUNCED_WARNING), record.announcedWarning)
            .putStringSet(key(accountIdHex, ALERTED_WARNING), record.alertedWarning)
            .apply()
    }

    override fun clear(accountIdHex: String) {
        preferences
            .edit()
            .remove(key(accountIdHex, MINE))
            .remove(key(accountIdHex, ANNOUNCED_SIGN_INS))
            .remove(key(accountIdHex, ANNOUNCED_WARNING))
            .remove(key(accountIdHex, ALERTED_WARNING))
            .apply()
    }

    // The returned set must not be modified, so it is copied.
    private fun set(
        accountIdHex: String,
        field: String,
    ): Set<String> = preferences.getStringSet(key(accountIdHex, field), null)?.toSet().orEmpty()

    private fun key(
        accountIdHex: String,
        field: String,
    ) = "$accountIdHex.$field"

    companion object {
        const val PREFERENCES_NAME = "sign_in_alerts"
        private const val MINE = "mine"
        private const val ANNOUNCED_SIGN_INS = "announced_sign_ins"
        private const val ANNOUNCED_WARNING = "announced_warning"
        private const val ALERTED_WARNING = "alerted_warning"
    }
}
