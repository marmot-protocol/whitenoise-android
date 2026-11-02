package dev.ipf.whitenoise.android.state

import android.app.Application
import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.WipeOutcomeFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/**
 * A failed post-wipe account refresh must not wipe every other account's member mutes (#2782
 * follow-up). `retainMemberMutesForAccounts` treats its input as an allow-list: a transient
 * `listAccounts()` failure used to fall back to an empty list, which then pruned every stored
 * mute, including ones for accounts the wipe never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountWipeMemberMuteRetentionTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private var listAccountsFailure: Throwable? = null
    private var remainingAccounts = listOf(localAccount(SURVIVING_ACCOUNT))
    private var signInCalls = 0
    private var wiped = false
    private var localCleanupCompleted = true

    private fun localAccount(
        label: String,
        signedOut: Boolean = false,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex =
            label
                .hashCode()
                .toUInt()
                .toString(16)
                .padStart(8, '0'),
        localSigning = true,
        externalSigning = false,
        signedOut = signedOut,
        running = !signedOut,
    )

    @Suppress("UNCHECKED_CAST")
    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            fun suspendFailure(failure: Throwable): Any {
                (arguments!!.last() as Continuation<Any?>).resumeWithException(failure)
                return COROUTINE_SUSPENDED
            }

            when (method.name) {
                "signOutAndWipe" ->
                    WipeOutcomeFfi(
                        groupsLeft = 0u,
                        groupLeaveFailures = emptyList(),
                        keyPackagesDeleted = 1u,
                        keyPackageFailures = emptyList(),
                        localCleanup = LocalCleanupReportFfi(completed = localCleanupCompleted, reason = null),
                    ).also { wiped = true }
                "listAccounts" -> {
                    listAccountsFailure?.let(::suspendFailure)
                    if (wiped) remainingAccounts else listOf(localAccount(WIPED_ACCOUNT))
                }
                "signInAccount" -> {
                    signInCalls++
                    Unit
                }
                "accountUnreadSummary", "chatList" -> emptyList<Any>()
                "toString" -> "AccountWipeMemberMuteRetentionMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else ->
                    if (arguments?.lastOrNull() is Continuation<*>) {
                        suspendFailure(UnsupportedOperationException("Unexpected Marmot call: ${method.name}"))
                    } else {
                        throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
                    }
            }
        } as MarmotInterface

    @Before
    fun setUp() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = listOf(localAccount(WIPED_ACCOUNT), localAccount(SURVIVING_ACCOUNT)),
            activeAccountRef = WIPED_ACCOUNT,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /** Wiping the final signed-in identity must not select a retained signed-out identity. */
    @Test
    fun wipeWithOnlySignedOutProfilesReturnsToOnboarding() =
        runBlocking {
            remainingAccounts = listOf(localAccount("retained", signedOut = true))
            val state = appState()

            state.signOutAndWipeActiveAccount()

            org.junit.Assert.assertNull(state.activeAccountRef)
            org.junit.Assert.assertEquals(AppPhase.Onboarding, state.phase)
            org.junit.Assert.assertFalse(
                context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE).contains("active_account"),
            )
            org.junit.Assert.assertEquals(remainingAccounts, state.accounts)
            org.junit.Assert.assertEquals(0, signInCalls)
        }

    /** Inventory order and tracked-only identities must not displace the surviving signing profile. */
    @Test
    fun wipeSkipsSignedOutAndTrackedProfilesBeforeSignedInSuccessor() =
        runBlocking {
            remainingAccounts =
                listOf(
                    localAccount("retained", signedOut = true),
                    localAccount("tracked").copy(localSigning = false),
                    localAccount(SURVIVING_ACCOUNT),
                )
            val state = appState()

            state.signOutAndWipeActiveAccount()

            org.junit.Assert.assertEquals(SURVIVING_ACCOUNT, state.activeAccountRef)
            org.junit.Assert.assertEquals(AppPhase.Ready, state.phase)
            org.junit.Assert.assertEquals(
                SURVIVING_ACCOUNT,
                context
                    .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
                    .getString("active_account", null),
            )
            org.junit.Assert.assertEquals(0, signInCalls)
        }

    /** Tracked-only inventory is retained but cannot become a signed-in successor. */
    @Test
    fun wipeWithOnlyTrackedProfilesReturnsToOnboarding() =
        runBlocking {
            remainingAccounts = listOf(localAccount("tracked").copy(localSigning = false))
            val state = appState()
            state.signOutAndWipeActiveAccount()
            org.junit.Assert.assertNull(state.activeAccountRef)
            org.junit.Assert.assertEquals(AppPhase.Onboarding, state.phase)
            org.junit.Assert.assertEquals(remainingAccounts, state.accounts)
            org.junit.Assert.assertEquals(0, signInCalls)
        }

    /** A clean wipe still prunes a genuinely removed account's mutes as before. */
    @Test
    fun successfulRefreshPrunesTheWipedAccountsMutesOnly() =
        runBlocking {
            val appState = appState()
            appState.memberMutePreferences.setMuted(WIPED_ACCOUNT, GROUP, MEMBER, muted = true)
            appState.memberMutePreferences.setMuted(SURVIVING_ACCOUNT, GROUP, MEMBER, muted = true)

            appState.signOutAndWipeActiveAccount()

            assertTrue(
                "the surviving account's mute must remain after a clean wipe",
                appState.memberMutePreferences.isMuted(SURVIVING_ACCOUNT, GROUP, MEMBER),
            )
            assertTrue(
                "the wiped account's own mute is expected to be pruned once it is gone",
                !appState.memberMutePreferences.isMuted(WIPED_ACCOUNT, GROUP, MEMBER),
            )
        }

    /** Stages originals for two accounts and verifies successful cleanup releases only the wiped account. */
    @Test
    fun successfulWipePurgesItsPrivateShareShelfAndPreservesAnotherAccount() =
        runBlocking {
            val state = appState()
            val files =
                dev.ipf.whitenoise.android.share
                    .PrivateShareFiles(context)
            val removed = stagedFile(files, localAccount(WIPED_ACCOUNT).accountIdHex)
            val retained = stagedFile(files, localAccount(SURVIVING_ACCOUNT).accountIdHex)
            try {
                state.signOutAndWipeActiveAccount()
                org.junit.Assert.assertNull(files.resolve(removed))
                org.junit.Assert.assertNotNull(files.metadata(retained))
            } finally {
                files.leases.releaseAccount(localAccount(WIPED_ACCOUNT).accountIdHex)
                files.leases.releaseAccount(localAccount(SURVIVING_ACCOUNT).accountIdHex)
            }
        }

    /** Makes native local cleanup incomplete and verifies the Android source owner is retained for recovery. */
    @Test
    fun failedNativeLocalCleanupKeepsThePrivateShareShelf() =
        runBlocking {
            localCleanupCompleted = false
            val state = appState()
            val files =
                dev.ipf.whitenoise.android.share
                    .PrivateShareFiles(context)
            val uri = stagedFile(files, localAccount(WIPED_ACCOUNT).accountIdHex)
            try {
                state.signOutAndWipeActiveAccount()
                org.junit.Assert.assertNotNull(files.metadata(uri))
            } finally {
                files.leases.releaseAccount(localAccount(WIPED_ACCOUNT).accountIdHex)
            }
        }

    /** Creates a completed one-byte original with an account-scoped shelf lease for wipe assertions. */
    private fun stagedFile(
        files: dev.ipf.whitenoise.android.share.PrivateShareFiles,
        account: String,
    ): android.net.Uri {
        val (uri, file) = files.newFile()
        file.writeBytes(byteArrayOf(1))
        files.finish(uri, "file", "application/octet-stream", 1)
        files.leases.saveShelf(account, GROUP, listOf(uri))
        return uri
    }

    /** A refresh that fails right after the wipe must not prune an unrelated account's mutes. */
    @Test
    fun failedPostWipeRefreshLeavesTheSurvivingAccountsMuteIntact() =
        runBlocking {
            val appState = appState()
            appState.memberMutePreferences.setMuted(SURVIVING_ACCOUNT, GROUP, MEMBER, muted = true)
            val author = "a".repeat(64)
            val profileChoice =
                dev.ipf.whitenoise.android.notifications.ProfileNotificationOverride(
                    dev.ipf.whitenoise.android.notifications.ProfileNotificationMode.MUTED,
                )
            appState.profileNotificationOverrides.set(SURVIVING_ACCOUNT, author, profileChoice)
            listAccountsFailure = RuntimeException("account refresh unavailable")

            appState.signOutAndWipeActiveAccount()

            assertTrue(
                "a transient refresh failure must not wipe an unrelated account's mute",
                appState.memberMutePreferences.isMuted(SURVIVING_ACCOUNT, GROUP, MEMBER),
            )
            org.junit.Assert.assertEquals(
                profileChoice,
                appState.profileNotificationOverrides.get(SURVIVING_ACCOUNT, author),
            )
        }

    private companion object {
        const val WIPED_ACCOUNT = "wiped-account"
        const val SURVIVING_ACCOUNT = "surviving-account"
        const val GROUP = "aa11bb22"
        const val MEMBER = "11aa22bb"
    }
}
