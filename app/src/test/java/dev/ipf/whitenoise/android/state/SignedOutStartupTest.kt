package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises persisted native sign-out status through the production bootstrap and explicit reactivation paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignedOutStartupTest {
    /** A stale saved selection cannot implicitly sign in either retained local or external identities. */
    @Test
    fun allSignedOutProfilesRemainOnboardingAcrossRepeatedBootstrap() =
        runBlocking {
            val accounts =
                listOf(account("local", signedOut = true), account("amber", signedOut = true, external = true))
            withFixture(accounts) { fixture ->
                assertOnboardingWithRetainedProfiles(fixture, accounts)
                fixture.bootstrap()
                fixture.ensureNotificationRuntimeStarted()
                assertOnboardingWithRetainedProfiles(fixture, accounts)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(1, fixture.subscriptionCalls.get())
                assertEquals(1, fixture.signerRegistrationCalls.get())
            }
        }

    /** Last-profile sign-out removes the saved preference while retaining a reusable native identity. */
    @Test
    fun noSavedSelectionDoesNotReactivateTheRetainedProfile() =
        runBlocking {
            val accounts = listOf(account("retained", signedOut = true))
            withFixture(accounts, selected = null) { fixture ->
                assertOnboardingWithRetainedProfiles(fixture, accounts)
            }
        }

    /** Existing signed-in preference wins even when a signed-out profile is first in native inventory. */
    @Test
    fun configuredSignedInProfileStillRestoresNormally() =
        runBlocking {
            val accounts = listOf(account("old", signedOut = true), account("other"), account("selected"))
            withFixture(accounts, selected = "selected") { fixture ->
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals("selected", fixture.appState.activeAccountRef)
                assertTrue(fixture.signInRequests.isEmpty())
            }
        }

    /** A retained signed-out selection falls back to a genuinely signed-in profile without native reactivation. */
    @Test
    fun signedOutSavedSelectionFallsBackToSignedInProfile() =
        runBlocking {
            withFixture(listOf(account("old", signedOut = true), account("available"))) { fixture ->
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals("available", fixture.appState.activeAccountRef)
                assertTrue(fixture.signInRequests.isEmpty())
            }
        }

    /** A removed Android preference never makes the first signed-out inventory entry eligible. */
    @Test
    fun missingSavedSelectionSkipsSignedOutProfiles() =
        runBlocking {
            val accounts = listOf(account("old", signedOut = true), account("available"))
            withFixture(accounts, selected = "removed") { fixture ->
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals("available", fixture.appState.activeAccountRef)
                assertTrue(fixture.signInRequests.isEmpty())
            }
        }

    /** Runtime liveness is not the native signed-out flag, including for Amber profiles. */
    @Test
    fun signedInNonRunningAmberProfileRemainsEligible() =
        runBlocking {
            withFixture(listOf(account("amber", external = true))) { fixture ->
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals("amber", fixture.appState.activeAccountRef)
                assertEquals(1, fixture.signerRegistrationCalls.get())
                assertTrue(fixture.signInRequests.isEmpty())
            }
        }

    /** Explicit Continue still uses native sign-in and publishes the retained local identity after success. */
    @Test
    fun explicitLocalProfileReactivationStillWorks() = verifyExplicitReactivation(external = false)

    /** Choosing a retained Amber profile preserves its existing native reactivation and callback registration path. */
    @Test
    fun explicitAmberProfileReactivationStillWorks() = verifyExplicitReactivation(external = true)

    /** A rejected explicit sign-in keeps onboarding and the saved profile without publishing Ready. */
    @Test
    fun failedExplicitReactivationKeepsOnboardingAndRetainedProfile() =
        runBlocking {
            val accounts = listOf(account("retained", signedOut = true))
            withFixture(accounts, signInFailure = IllegalStateException("synthetic sign-in failure")) { fixture ->
                fixture.runWithMainLooperPumping {
                    fixture.appState.reactivateRetainedAccount("retained")
                    awaitReactivationFinished(fixture)
                }
                assertEquals(listOf("retained"), fixture.signInRequests.toList())
                assertEquals(AppPhase.Onboarding, fixture.appState.phase)
                assertNull(fixture.appState.activeAccountRef)
                assertEquals(accounts, fixture.appState.accounts)
            }
        }

    /** Both signing methods cross the same explicit UI admission boundary and consume the updated native snapshot. */
    private fun verifyExplicitReactivation(external: Boolean) =
        runBlocking {
            val accounts = CopyOnWriteArrayList(listOf(account("retained", signedOut = true, external = external)))
            withFixture(
                accounts = accounts,
                onSignInAccount = { label ->
                    assertEquals("retained", label)
                    accounts[0] = accounts.single().copy(signedOut = false, running = true)
                },
            ) { fixture ->
                assertOnboardingWithRetainedProfiles(fixture, accounts)
                assertEquals(if (external) 1 else 0, fixture.signerRegistrationCalls.get())
                fixture.runWithMainLooperPumping {
                    fixture.appState.reactivateRetainedAccount("retained")
                    awaitReactivationFinished(fixture)
                }
                assertEquals(listOf("retained"), fixture.signInRequests.toList())
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals("retained", fixture.appState.activeAccountRef)
                assertTrue(fixture.appState.accounts.none { it.signedOut })
            }
        }

    /** Waits for the actual asynchronous UI admission to finish while its caller pumps the main looper. */
    private suspend fun awaitReactivationFinished(fixture: NotificationBootstrapTestFixture) {
        withTimeout(5_000L) {
            while (fixture.appState.retainedAccountReactivationRef != null) delay(1L)
        }
    }

    /** Native inventory stays available for the picker while Android has no authenticated active selection. */
    private fun assertOnboardingWithRetainedProfiles(
        fixture: NotificationBootstrapTestFixture,
        accounts: List<AccountSummaryFfi>,
    ) {
        assertEquals(AppPhase.Onboarding, fixture.appState.phase)
        assertNull(fixture.appState.activeAccountRef)
        assertNull(fixture.appState.localNotificationSettings)
        assertEquals(accounts, fixture.appState.accounts)
        assertTrue(fixture.signInRequests.isEmpty())
    }

    /** Shares the existing real-bootstrap fixture and closes process-owned test work even when assertions fail. */
    private suspend fun withFixture(
        accounts: List<AccountSummaryFfi>,
        selected: String? = accounts.first().label,
        signInFailure: Throwable? = null,
        onSignInAccount: ((String) -> Unit)? = null,
        block: suspend (NotificationBootstrapTestFixture) -> Unit,
    ) {
        val fixture =
            NotificationBootstrapTestFixture(
                context = RuntimeEnvironment.getApplication(),
                accounts = accounts,
                emitStartupNotification = false,
                signInFailure = signInFailure,
                onSignInAccount = onSignInAccount,
            )
        try {
            fixture.appState.activeAccountRef = selected
            fixture.bootstrap()
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    /** Uses synthetic identity metadata; a stopped account can remain natively signed in. */
    private fun account(
        label: String,
        signedOut: Boolean = false,
        external: Boolean = false,
    ) = AccountSummaryFfi(label, label, !external, external, signedOut, false)
}
