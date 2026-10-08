package dev.ipf.whitenoise.android.maestro

import android.content.SharedPreferences
import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.state.ContactNicknamePreferences
import dev.ipf.whitenoise.android.state.ContactNotesPreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Verify actual private storage and unchanged public profiles without synthesizing ownership or results. */
internal suspend fun verifyMaestroContactPrivateDetails(
    native: Marmot,
    state: WhiteNoiseAppState?,
    contact: MaestroExternalContact?,
    preferences: SharedPreferences,
    postcondition: String?,
): Boolean {
    if (postcondition !in setOf("contact-private-saved", "contact-private-cleared", "contact-private-boundary")) {
        return false
    }
    val app = checkNotNull(state)
    val external = checkNotNull(contact)
    val expected =
        when (postcondition) {
            "contact-private-saved" -> "Maestro private Dave" to "Maestro private note"
            "contact-private-boundary" -> "M".repeat(80) to null
            else -> null to null
        }
    val accounts = native.listAccounts()
    check(accounts.size == 3 && accounts.none { it.accountIdHex == external.accountIdHex })
    withContext(Dispatchers.Main.immediate) {
        check(app.activeAccountRef == external.owner)
        check(app.accounts.map { it.accountIdHex }.toSet() == accounts.map { it.accountIdHex }.toSet())
        check(app.contactNicknameFor(external.owner, external.accountIdHex) == expected.first)
        check(app.contactNotes(external.accountIdHex) == expected.second)
    }
    for (account in accounts) {
        val values =
            ContactNicknamePreferences.readNickname(preferences, account.label, external.accountIdHex) to
                ContactNotesPreferences.readNotes(preferences, account.label, external.accountIdHex)
        val expectedForAccount = if (account.label == external.owner) expected else null to null
        check(values == expectedForAccount)
    }
    check(external.publicProfiles.size == 4)
    for ((id, original) in external.publicProfiles) {
        check(native.userProfile(id) == original) { "Private contact edits changed a public native profile" }
    }
    return true
}
