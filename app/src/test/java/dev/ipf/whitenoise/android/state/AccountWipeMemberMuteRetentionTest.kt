package dev.ipf.whitenoise.android.state

import android.app.Application
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
    private var wiped = false

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
                        localCleanup = LocalCleanupReportFfi(completed = true, reason = null),
                    ).also { wiped = true }
                "listAccounts" -> {
                    listAccountsFailure?.let(::suspendFailure)
                    if (wiped) listOf(localAccount(SURVIVING_ACCOUNT)) else listOf(localAccount(WIPED_ACCOUNT))
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

    /** A refresh that fails right after the wipe must not prune an unrelated account's mutes. */
    @Test
    fun failedPostWipeRefreshLeavesTheSurvivingAccountsMuteIntact() =
        runBlocking {
            val appState = appState()
            appState.memberMutePreferences.setMuted(SURVIVING_ACCOUNT, GROUP, MEMBER, muted = true)
            listAccountsFailure = RuntimeException("account refresh unavailable")

            appState.signOutAndWipeActiveAccount()

            assertTrue(
                "a transient refresh failure must not wipe an unrelated account's mute",
                appState.memberMutePreferences.isMuted(SURVIVING_ACCOUNT, GROUP, MEMBER),
            )
        }

    private companion object {
        const val WIPED_ACCOUNT = "wiped-account"
        const val SURVIVING_ACCOUNT = "surviving-account"
        const val GROUP = "aa11bb22"
        const val MEMBER = "11aa22bb"
    }
}
