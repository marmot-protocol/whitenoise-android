package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyPackageDeletionRelayTest {
    @Test
    fun externalOnlyPublicSourceReachesDeletionBoundary() =
        runBlocking {
            var deletedThrough: List<String>? = null

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://relay.example"),
                    classify = ::classifyTestRelays,
                    resolve = { arrayOf(publicAddress()) },
                    delete = { deletedThrough = it },
                )

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(listOf("wss://relay.example"), deletedThrough)
        }

    @Test
    fun invalidDuplicateAndPrivatePeersCannotVetoPublicSource() =
        runBlocking {
            val resolvedHosts = mutableListOf<String>()
            var deletedThrough: List<String>? = null

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays =
                        listOf(
                            " https://not-a-relay.example ",
                            "wss://relay.example",
                            "wss://relay.example",
                            "wss://127.0.0.1",
                            "wss://private.example",
                            "retired://relay.example",
                        ),
                    classify = ::classifyTestRelays,
                    resolve = { host ->
                        resolvedHosts += host
                        if (host == "private.example") arrayOf(loopbackAddress()) else arrayOf(publicAddress())
                    },
                    delete = { deletedThrough = it },
                )

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(listOf("relay.example", "private.example"), resolvedHosts)
            assertEquals(listOf("wss://relay.example"), deletedThrough)
        }

    @Test
    fun allMalformedOrLiteralPrivateSourcesFailBeforeResolution() =
        runBlocking {
            var resolverCalled = false
            var deleteCalled = false

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("https://relay.example", "wss://127.0.0.1", "not a relay"),
                    classify = ::classifyTestRelays,
                    resolve = {
                        resolverCalled = true
                        arrayOf(publicAddress())
                    },
                    delete = { deleteCalled = true },
                )

            assertEquals(KeyPackageDeletionResult.NoUsableRelay, result)
            assertFalse(resolverCalled)
            assertFalse(deleteCalled)
        }

    @Test
    fun hostnameResolvingToPrivateAddressFailsBeforeDeletion() =
        runBlocking {
            var deleteCalled = false

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://private.example"),
                    classify = ::allowEveryRelay,
                    resolve = { arrayOf(loopbackAddress()) },
                    delete = { deleteCalled = true },
                )

            assertEquals(KeyPackageDeletionResult.NoUsableRelay, result)
            assertFalse(deleteCalled)
        }

    @Test
    fun everyDnsAnswerMustBePublicUnicastBeforeDeletion() =
        runBlocking {
            val unsafeAnswers =
                listOf(
                    address(192, 0, 2, 1),
                    ipv6(0xFF, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
                    ipv6(0x20, 0x01, 0, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
                    ipv6(0x20, 0x02, 8, 8, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                )

            unsafeAnswers.forEach { unsafe ->
                var deleteCalled = false
                val result =
                    deleteKeyPackageThroughSafeSourceRelays(
                        sourceRelays = listOf("wss://unsafe-answer.example"),
                        classify = ::allowEveryRelay,
                        resolve = { arrayOf(publicAddress(), unsafe) },
                        delete = { deleteCalled = true },
                    )

                assertEquals(unsafe.hostAddress, KeyPackageDeletionResult.NoUsableRelay, result)
                assertFalse(unsafe.hostAddress, deleteCalled)
            }
        }

    @Test
    fun unavailableResolutionRemainsDistinctAndDoesNotDelete() =
        runBlocking {
            var deleteCalled = false

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://offline.example"),
                    classify = ::allowEveryRelay,
                    resolve = { null },
                    delete = { deleteCalled = true },
                )

            assertEquals(KeyPackageDeletionResult.HostVerificationUnavailable, result)
            assertFalse(deleteCalled)
        }

    @Test
    fun oneVerifiedSourceAllowsDeletionWhenAnotherResolverIsUnavailable() =
        runBlocking {
            var deletedThrough: List<String>? = null

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://online.example", "wss://offline.example"),
                    classify = ::allowEveryRelay,
                    resolve = { host -> if (host == "online.example") arrayOf(publicAddress()) else null },
                    delete = { deletedThrough = it },
                )

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(listOf("wss://online.example"), deletedThrough)
        }

    @Test
    fun retryUsesTheSameCanonicalSourceSetWithoutAndroidOwnedState() =
        runBlocking {
            val attempts = mutableListOf<List<String>>()

            repeat(2) {
                val result =
                    deleteKeyPackageThroughSafeSourceRelays(
                        sourceRelays = listOf(" WSS://RELAY.EXAMPLE ", "wss://relay.example"),
                        classify = ::allowEveryRelay,
                        resolve = { arrayOf(publicAddress()) },
                        delete = { attempts += it },
                    )
                assertEquals(KeyPackageDeletionResult.Deleted, result)
            }

            assertEquals(
                listOf(listOf("wss://relay.example"), listOf("wss://relay.example")),
                attempts,
            )
        }

    @Test
    fun packageFromAnotherAccountCannotReachDeletionBoundary() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val appState =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore.forContext(context),
                    accountIdHexResolver = { null },
                    accounts = listOf(activeAccount(ACCOUNT_B)),
                    activeAccountRef = ACCOUNT_B,
                )

            val deleted =
                appState.deleteKeyPackage(
                    accountRef = ACCOUNT_A,
                    eventIdHex = "ab".repeat(32),
                    sourceRelays = listOf("wss://relay.example"),
                )

            assertFalse(deleted)
        }

    @Test
    fun appStateRoutesAnExternalSourceThroughClassifierAndNativeDelete() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val appState = activeAppState(context)
            var deletedAccount: String? = null
            var deletedEvent: String? = null
            var deletedRelays: List<String>? = null

            val deleted =
                appState.deleteKeyPackageWithDependencies(
                    accountRef = ACCOUNT_A,
                    eventIdHex = EVENT_ID,
                    sourceRelays = listOf("wss://relay.external.example"),
                    classify = ::allowEveryRelay,
                    resolve = { arrayOf(publicAddress()) },
                    delete = { account, event, relays ->
                        deletedAccount = account
                        deletedEvent = event
                        deletedRelays = relays
                    },
                )

            assertTrue(deleted)
            assertEquals(ACCOUNT_A, deletedAccount)
            assertEquals(EVENT_ID, deletedEvent)
            assertEquals(listOf("wss://relay.external.example"), deletedRelays)
            assertEquals(
                AppText.Resource(R.string.toast_key_package_deleted),
                appState.transientNotice?.title,
            )
        }

    @Test
    fun appStateExplainsWhenNoSafeSourceRelayRemains() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val appState = activeAppState(context)

            val deleted =
                appState.deleteKeyPackageWithDependencies(
                    accountRef = ACCOUNT_A,
                    eventIdHex = EVENT_ID,
                    sourceRelays = listOf("wss://127.0.0.1"),
                    classify = { endpoints ->
                        endpoints.map { endpoint ->
                            RelayEndpointClassificationFfi(
                                endpoint = endpoint,
                                normalizedEndpoint = endpoint,
                                policy = RelayEndpointPolicyFfi.UNSAFE,
                            )
                        }
                    },
                    resolve = { error("unsafe sources must not reach DNS") },
                    delete = { _, _, _ -> error("unsafe sources must not reach deletion") },
                )

            assertFalse(deleted)
            assertEquals(
                AppText.Resource(R.string.error_no_safe_key_package_source_relay),
                appState.toast?.detail,
            )
            assertFalse(appState.toast?.copyable ?: true)
        }

    @Test
    fun cancellationAtDeletionBoundaryPropagates() =
        runBlocking {
            var cancellationPropagated = false

            try {
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://relay.example"),
                    classify = ::allowEveryRelay,
                    resolve = { arrayOf(publicAddress()) },
                    delete = { throw CancellationException("cancel deletion") },
                )
            } catch (_: CancellationException) {
                cancellationPropagated = true
            }

            assertTrue(cancellationPropagated)
        }

    @Test
    fun deletionFailureIsReturnedWithoutChangingItsCause() =
        runBlocking {
            val failure = IllegalStateException("no acknowledgement")

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://relay.example"),
                    classify = ::allowEveryRelay,
                    resolve = { arrayOf(publicAddress()) },
                    delete = { throw failure },
                )

            assertTrue(result is KeyPackageDeletionResult.Failed)
            assertSame(failure, (result as KeyPackageDeletionResult.Failed).cause)
        }

    @Test
    fun nativeRetiredPeerCannotVetoAnAllowedSource() =
        runBlocking {
            var deletedThrough: List<String>? = null

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://relay.example", "wss://relay.damus.io"),
                    classify = { endpoints ->
                        endpoints.map { endpoint ->
                            RelayEndpointClassificationFfi(
                                endpoint = endpoint,
                                normalizedEndpoint = endpoint,
                                policy =
                                    if (endpoint == "wss://relay.damus.io") {
                                        RelayEndpointPolicyFfi.RETIRED
                                    } else {
                                        RelayEndpointPolicyFfi.ALLOWED
                                    },
                            )
                        }
                    },
                    resolve = { arrayOf(publicAddress()) },
                    delete = { deletedThrough = it },
                )

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(listOf("wss://relay.example"), deletedThrough)
        }

    @Test
    fun deletionRouteIsBoundedBeforeTheStrictNativeDeleteCall() =
        runBlocking {
            val sources = (0 until 20).map { "wss://relay-$it.example" }
            var deletedThrough: List<String>? = null

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = sources,
                    classify = ::allowEveryRelay,
                    resolve = { arrayOf(publicAddress()) },
                    delete = { deletedThrough = it },
                )

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(sources.take(16), deletedThrough)
        }

    @Test
    fun hostileSourceFanoutIsBoundedBeforeMdkClassificationAndDns() =
        runBlocking {
            val sources = (0 until 300).map { "wss://relay-$it.example" }
            var classifiedCount = 0
            var resolveCount = 0

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = sources,
                    classify = { endpoints ->
                        classifiedCount = endpoints.size
                        allowEveryRelay(endpoints)
                    },
                    resolve = {
                        resolveCount += 1
                        arrayOf(publicAddress())
                    },
                    delete = {},
                )

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(256, classifiedCount)
            assertEquals(16, resolveCount)
        }

    @Test
    fun blockedPrefixCannotHideALaterUsableSourceFromTheNativeCap() =
        runBlocking {
            val blocked = (0 until 16).map { "wss://blocked-$it.example" }
            val safe = "wss://safe.example:8443"
            var deletedThrough: List<String>? = null

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = blocked + safe,
                    classify = ::allowEveryRelay,
                    resolve = { host ->
                        if (host == "safe.example") arrayOf(publicAddress()) else arrayOf(loopbackAddress())
                    },
                    delete = { deletedThrough = it },
                )

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(listOf(safe), deletedThrough)
        }

    @Test
    fun classifierApprovedNonDefaultWssPortReachesDeletion() =
        runBlocking {
            val source = "wss://relay.example:8443/path"
            var deletedThrough: List<String>? = null

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf(source),
                    classify = ::allowEveryRelay,
                    resolve = { arrayOf(publicAddress()) },
                    delete = { deletedThrough = it },
                )

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(listOf(source), deletedThrough)
        }

    @Test
    fun oneDnsVerdictIsSharedByClassifierApprovedUrlsOnTheSameHost() =
        runBlocking {
            var resolves = 0
            var deletedThrough: List<String>? = null

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://relay.example/one", "wss://relay.example/two"),
                    classify = ::allowEveryRelay,
                    resolve = {
                        resolves += 1
                        if (resolves == 1) arrayOf(publicAddress()) else arrayOf(loopbackAddress())
                    },
                    delete = { deletedThrough = it },
                )

            assertEquals(KeyPackageDeletionResult.Deleted, result)
            assertEquals(1, resolves)
            assertEquals(listOf("wss://relay.example/one", "wss://relay.example/two"), deletedThrough)
        }

    @Test
    fun accountSwitchDuringHostVerificationSupersedesDeletion() =
        runBlocking {
            var active = true
            var deleteCalled = false

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://relay.example"),
                    classify = ::allowEveryRelay,
                    resolve = {
                        active = false
                        arrayOf(publicAddress())
                    },
                    accountStillActive = { active },
                    delete = { deleteCalled = true },
                )

            assertEquals(KeyPackageDeletionResult.Superseded, result)
            assertFalse(deleteCalled)
        }

    @Test
    fun accountSwitchDuringClassificationSupersedesBeforeDns() =
        runBlocking {
            var active = true
            var resolverCalled = false

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://relay.example"),
                    classify = { endpoints ->
                        active = false
                        allowEveryRelay(endpoints)
                    },
                    resolve = {
                        resolverCalled = true
                        arrayOf(publicAddress())
                    },
                    accountStillActive = { active },
                    delete = { error("must not delete") },
                )

            assertEquals(KeyPackageDeletionResult.Superseded, result)
            assertFalse(resolverCalled)
        }

    @Test
    fun accountSwitchDuringNativeCallSuppressesSuccessPresentation() =
        runBlocking {
            var active = true

            val result =
                deleteKeyPackageThroughSafeSourceRelays(
                    sourceRelays = listOf("wss://relay.example"),
                    classify = ::allowEveryRelay,
                    resolve = { arrayOf(publicAddress()) },
                    accountStillActive = { active },
                    delete = { active = false },
                )

            assertEquals(KeyPackageDeletionResult.Superseded, result)
        }

    private fun publicAddress(): InetAddress = address(8, 8, 8, 8)

    private fun loopbackAddress(): InetAddress = address(127, 0, 0, 1)

    private fun address(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ): InetAddress = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    private fun ipv6(vararg bytes: Int): InetAddress = InetAddress.getByAddress(ByteArray(16) { bytes[it].toByte() })

    private fun activeAppState(context: Context) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = listOf(activeAccount(ACCOUNT_A)),
            activeAccountRef = ACCOUNT_A,
        )

    private fun allowEveryRelay(endpoints: List<String>): List<RelayEndpointClassificationFfi> =
        endpoints.map { endpoint ->
            RelayEndpointClassificationFfi(
                endpoint = endpoint,
                normalizedEndpoint = endpoint.trim().lowercase(),
                policy = RelayEndpointPolicyFfi.ALLOWED,
            )
        }

    private fun classifyTestRelays(endpoints: List<String>): List<RelayEndpointClassificationFfi> =
        endpoints.map { endpoint ->
            val normalized = endpoint.trim().lowercase()
            val policy =
                when {
                    !normalized.startsWith("wss://") -> RelayEndpointPolicyFfi.INVALID
                    normalized.contains("127.0.0.1") -> RelayEndpointPolicyFfi.UNSAFE
                    else -> RelayEndpointPolicyFfi.ALLOWED
                }
            RelayEndpointClassificationFfi(
                endpoint = endpoint,
                normalizedEndpoint = normalized.takeIf { policy == RelayEndpointPolicyFfi.ALLOWED },
                policy = policy,
            )
        }

    private fun activeAccount(label: String) =
        AccountSummaryFfi(
            label = label,
            accountIdHex = "12".repeat(32),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private companion object {
        const val ACCOUNT_A = "personal"
        const val ACCOUNT_B = "work"
        const val EVENT_ID = "abababababababababababababababababababababababababababababababab"
    }
}
