package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chat list's reminder to save the private key (#4279): who is reminded, what retires it, and per account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivateKeyRemindersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = context.getSharedPreferences("private-key-reminders-test", Context.MODE_PRIVATE)

    /** A Sign up account that holds its key and has a conversation is reminded. */
    @Test
    fun signUpAccountWithAConversationIsReminded() {
        val reminders = PrivateKeyReminders(preferences)

        reminders.recordSignUp(SIGNED_UP)

        assertEquals(PrivateKeyReminderStage.Pending, reminders.stage(SIGNED_UP))
        assertTrue(reminders.shows(account(SIGNED_UP), hasConversations = true))
    }

    /** Imported, Amber and remote-signer accounts never pass through Sign up, so they carry no flag and see nothing. */
    @Test
    fun accountsNotCreatedBySignUpAreNeverReminded() {
        val reminders = PrivateKeyReminders(preferences)
        reminders.recordSignUp(SIGNED_UP)

        assertNull(reminders.stage(IMPORTED))
        assertFalse(reminders.shows(account(IMPORTED), hasConversations = true))
        assertFalse(reminders.shows(account(AMBER, local = false, external = true), hasConversations = true))
        assertFalse(reminders.shows(account(REMOTE, local = false, external = true), hasConversations = true))
        assertFalse(reminders.shows(null, hasConversations = true))
    }

    /** A recorded account that no longer signs with a key held here, or is signed out, is not reminded either. */
    @Test
    fun recordedAccountWithoutItsOwnKeyIsNotReminded() {
        val reminders = PrivateKeyReminders(preferences)
        reminders.recordSignUp(SIGNED_UP)

        assertFalse(reminders.shows(account(SIGNED_UP, local = false, external = true), hasConversations = true))
        assertFalse(reminders.shows(account(SIGNED_UP, local = false), hasConversations = true))
        assertFalse(reminders.shows(account(SIGNED_UP, signedOut = true), hasConversations = true))
    }

    /** Before the first conversation there is nothing yet worth keeping, so the reminder waits. */
    @Test
    fun accountWithoutConversationsIsNotRemindedYet() {
        val reminders = PrivateKeyReminders(preferences)
        reminders.recordSignUp(SIGNED_UP)

        assertFalse(reminders.shows(account(SIGNED_UP), hasConversations = false))
        assertTrue(reminders.shows(account(SIGNED_UP), hasConversations = true))
    }

    /** Not now and a copy or export each retire the reminder, and it stays retired after a restart. */
    @Test
    fun notNowAndSavingTheKeyRetireTheReminderForGood() {
        PrivateKeyReminders(preferences).apply {
            recordSignUp(SIGNED_UP)
            recordSignUp(OTHER)
            dismiss(SIGNED_UP)
            markKeySaved(OTHER)
            // A later save cannot reopen or relabel a dismissed reminder.
            markKeySaved(SIGNED_UP)
        }

        val restarted = PrivateKeyReminders(preferences)
        assertEquals(PrivateKeyReminderStage.Dismissed, restarted.stage(SIGNED_UP))
        assertEquals(PrivateKeyReminderStage.Saved, restarted.stage(OTHER))
        assertFalse(restarted.shows(account(SIGNED_UP), hasConversations = true))
        assertFalse(restarted.shows(account(OTHER), hasConversations = true))
    }

    /** Saving or dismissing an account Sign up never recorded writes no flag a later sign-up could inherit. */
    @Test
    fun retiringAnUnrecordedAccountRecordsNothing() {
        val reminders = PrivateKeyReminders(preferences)

        reminders.markKeySaved(IMPORTED)
        reminders.dismiss(AMBER)

        assertNull(reminders.stage(IMPORTED))
        assertNull(reminders.stage(AMBER))
        assertTrue(preferences.all.isEmpty())
    }

    /** Each account keeps its own state: Not now or a save on one never hides the other's reminder. */
    @Test
    fun stateIsIsolatedPerAccount() {
        val reminders = PrivateKeyReminders(preferences)
        reminders.recordSignUp(SIGNED_UP)
        reminders.recordSignUp(OTHER)

        reminders.dismiss(SIGNED_UP)
        assertFalse(reminders.shows(account(SIGNED_UP), hasConversations = true))
        assertTrue(reminders.shows(account(OTHER), hasConversations = true))

        reminders.recordSignUp(SIGNED_UP)
        reminders.markKeySaved(OTHER)
        assertTrue(reminders.shows(account(SIGNED_UP), hasConversations = true))
        assertFalse(reminders.shows(account(OTHER), hasConversations = true))
    }

    /** The flag is keyed by the normalized account id, so letter case cannot split or leak an account's state. */
    @Test
    fun accountIdLetterCaseIsNormalized() {
        val reminders = PrivateKeyReminders(preferences)

        reminders.recordSignUp(SIGNED_UP.uppercase())

        assertEquals(PrivateKeyReminderStage.Pending, reminders.stage(SIGNED_UP))
        reminders.dismiss(" $SIGNED_UP ")
        assertEquals(PrivateKeyReminderStage.Dismissed, reminders.stage(SIGNED_UP.uppercase()))
    }

    /** Sign-out and wipe forget the account, so signing in again with the key, by any method, is not reminded. */
    @Test
    fun forgottenAccountIsNotRemindedAfterSigningInAgain() {
        val reminders = PrivateKeyReminders(preferences)
        reminders.recordSignUp(SIGNED_UP)
        reminders.recordSignUp(OTHER)

        reminders.forget(SIGNED_UP)

        assertNull(reminders.stage(SIGNED_UP))
        assertFalse(reminders.shows(account(SIGNED_UP), hasConversations = true))
        assertEquals(PrivateKeyReminderStage.Pending, reminders.stage(OTHER))
    }

    /**
     * Only Sign up's native creation records an account; no nsec import, Amber or remote-signer sign-in does. Sign-out
     * and wipe forget it. The copy and export paths are exercised in AccountKeysExportDeliveryTest.
     */
    @Test
    fun onlySignUpRecordsAndSignOutOrWipeForgets() {
        val recorders =
            appModuleFile("src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.readText().contains(".recordSignUp(") }
                .map { it.name }
                .toList()
        assertEquals(listOf("AppProfileSignUp.kt"), recorders)
        val create = stateSource("AppProfileSignUp.kt").functionBody("createOwnedIdentity")
        assertTrue(create.contains("privateKeyReminders.recordSignUp(it.accountIdHex)"))
        val signOut = stateSource("AppState.kt").functionBody("clearSignedOutAccountMemory")
        assertTrue(signOut.contains("privateKeyReminders.forget("))
        val wipe = stateSource("AppStateMediaWipe.kt").functionBody("purgeWipedAccountSources")
        assertTrue(wipe.contains("privateKeyReminders.forget(id)"))
    }

    private fun stateSource(name: String) = appModuleFile("$STATE_SOURCES/$name").readText()

    private fun account(
        accountIdHex: String,
        local: Boolean = true,
        external: Boolean = false,
        signedOut: Boolean = false,
    ) = AccountSummaryFfi(
        label = "ref-$accountIdHex",
        accountIdHex = accountIdHex,
        localSigning = local,
        externalSigning = external,
        signedOut = signedOut,
        running = !signedOut,
        unlinked = false,
    )

    private companion object {
        const val STATE_SOURCES = "src/main/java/dev/ipf/whitenoise/android/state"
        val SIGNED_UP = "a1".repeat(32)
        val OTHER = "b2".repeat(32)
        val IMPORTED = "c3".repeat(32)
        val AMBER = "d4".repeat(32)
        val REMOTE = "e5".repeat(32)
    }
}
