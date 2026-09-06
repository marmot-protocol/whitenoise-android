package dev.ipf.whitenoise.android.state

import android.net.DnsResolver
import android.net.InetAddresses
import android.os.CancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Starts an asynchronous platform query; the cancellation signal owns its complete lifetime. */
internal typealias KeyPackageDeletionDnsQuery =
    (String, CancellationSignal, DnsResolver.Callback<List<InetAddress>>) -> Unit

/**
 * Resolves deletion sources without blocking an IO worker in getAllByName.
 * Numeric literals use Android's strict non-DNS parser; malformed IPv6 cannot become a hostname query.
 * The caller owns the per-host and total deadlines, which cancel the actual platform query here.
 */
internal suspend fun resolveKeyPackageDeletionHost(
    host: String,
    query: KeyPackageDeletionDnsQuery = ::startKeyPackageDeletionDnsQuery,
): Array<InetAddress>? =
    when {
        InetAddresses.isNumericAddress(host) -> arrayOf(InetAddresses.parseNumericAddress(host))
        ':' in host -> null
        else -> awaitKeyPackageDeletionDnsAnswer(host, query)
    }

/** Bridges answer/error/cancellation races without accepting duplicate or late callbacks. */
private suspend fun awaitKeyPackageDeletionDnsAnswer(
    host: String,
    query: KeyPackageDeletionDnsQuery,
): Array<InetAddress>? =
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        val completed = AtomicBoolean(false)
        continuation.invokeOnCancellation {
            completed.set(true)
            signal.cancel()
        }

        fun finish(answer: Array<InetAddress>?) {
            if (completed.compareAndSet(false, true)) continuation.resume(answer)
        }

        val callback =
            object : DnsResolver.Callback<List<InetAddress>> {
                /** Only successful DNS replies can contribute public-address candidates. */
                override fun onAnswer(
                    answer: List<InetAddress>,
                    rcode: Int,
                ) {
                    finish(answer.takeIf { rcode == 0 && it.isNotEmpty() }?.toTypedArray())
                }

                /** Platform resolution errors remain recoverable without revealing the hostname. */
                override fun onError(error: DnsResolver.DnsException) {
                    finish(null)
                }
            }
        try {
            if (continuation.isActive) query(host, signal, callback)
        } catch (failure: CancellationException) {
            continuation.cancel(failure)
        } catch (_: Exception) {
            finish(null)
        }
    }

/** Requests both address families supported by the current default network (API29, minSdk30). */
private fun startKeyPackageDeletionDnsQuery(
    host: String,
    signal: CancellationSignal,
    callback: DnsResolver.Callback<List<InetAddress>>,
) {
    DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_EMPTY, DNS_CALLBACK_EXECUTOR, signal, callback)
}

private val DNS_CALLBACK_EXECUTOR = Executor { task -> task.run() }
