package dev.ipf.whitenoise.android.amber

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.ipf.whitenoise.android.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * App-private relay correlation for NIP-55 signer prompts. Each relay Activity
 * instance stamps [EXTRA_REQUEST_ID] onto its result so [AmberActivityCoordinator]
 * can correlate cancellations even when the external signer returns null data.
 */
object AmberSignerRelay {
    const val EXTRA_REQUEST_ID = "dev.ipf.whitenoise.android.amber.SIGNER_RELAY_REQUEST_ID"
    const val EXTRA_SIGNER_INTENT = "dev.ipf.whitenoise.android.amber.SIGNER_RELAY_SIGNER_INTENT"
    const val EXTRA_LAUNCH_FAILED = "dev.ipf.whitenoise.android.amber.SIGNER_RELAY_LAUNCH_FAILED"
    const val EXTRA_HANDLED_SIGNER_PACKAGE = "dev.ipf.whitenoise.android.amber.SIGNER_RELAY_HANDLED_PACKAGE"
    const val EXTRA_CHOOSER_REQUEST_ID = "dev.ipf.whitenoise.android.amber.SIGNER_RELAY_CHOOSER_REQUEST_ID"

    private val handledPackageByRequestId = ConcurrentHashMap<String, HandledSignerRequest>()

    internal fun buildLaunchIntent(
        requestId: String,
        signerIntent: Intent,
    ): Intent =
        Intent().apply {
            setClassName(BuildConfig.APPLICATION_ID, AmberSignerRelayActivity::class.java.name)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_SIGNER_INTENT, signerIntent)
        }

    /** Preserve signer extras, then overwrite app-owned correlation and handler identity. */
    internal fun buildResultIntent(
        requestId: String?,
        signerData: Intent?,
        handledSignerPackage: String? = null,
    ): Intent =
        Intent().apply {
            signerData?.extras?.let { putExtras(it) }
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_LAUNCH_FAILED, false)
            putExtra(EXTRA_HANDLED_SIGNER_PACKAGE, handledSignerPackage)
        }

    internal fun prepareSignerLaunch(
        context: Context,
        requestId: String,
        signerIntent: Intent,
    ): Intent? {
        registerHandledSignerRequest(requestId)
        val explicitPackage = signerIntent.component?.packageName ?: signerIntent.`package`
        if (!explicitPackage.isNullOrBlank()) {
            if (!Nip55.isSignerPackageAvailable(context, explicitPackage)) return null
            recordHandledSignerPackage(requestId, explicitPackage)
            return signerIntent
        }

        val handlers =
            context.packageManager
                .queryIntentActivities(signerIntent, 0)
                .mapNotNull { resolved ->
                    val info = resolved.activityInfo ?: return@mapNotNull null
                    val packageName = info.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val className = info.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ComponentName(packageName, className)
                }.distinct()
        if (handlers.isEmpty()) return null
        if (handlers.size == 1) {
            val component = handlers.single()
            recordHandledSignerPackage(requestId, component.packageName)
            return Intent(signerIntent).setComponent(component)
        }

        val callback =
            PendingIntent.getBroadcast(
                context,
                requestId.hashCode(),
                Intent(context, AmberSignerChoiceReceiver::class.java).putExtra(EXTRA_CHOOSER_REQUEST_ID, requestId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
        return Intent.createChooser(signerIntent, null, callback.intentSender)
    }

    internal fun recordHandledSignerPackage(
        requestId: String?,
        packageName: String?,
    ) {
        if (!requestId.isNullOrBlank() && !packageName.isNullOrBlank()) {
            handledPackageByRequestId[requestId]?.record(packageName)
        }
    }

    internal fun registerHandledSignerRequest(requestId: String) {
        if (requestId.isNotBlank()) handledPackageByRequestId.putIfAbsent(requestId, HandledSignerRequest())
    }

    internal suspend fun awaitHandledSignerPackage(
        requestId: String?,
        timeoutMs: Long,
    ): String? {
        val id = requestId?.takeIf(String::isNotBlank) ?: return null
        val request = handledPackageByRequestId[id] ?: return null
        return withTimeoutOrNull(timeoutMs) {
            request.selected.await()
            request.packageName.get()
        }
    }

    internal fun consumeHandledSignerPackage(requestId: String?): String? =
        requestId
            ?.let(handledPackageByRequestId::remove)
            ?.packageName
            ?.get()

    private class HandledSignerRequest {
        val packageName = AtomicReference<String?>(null)
        val selected = CompletableDeferred<Unit>()

        fun record(packageName: String) {
            if (this.packageName.compareAndSet(null, packageName)) selected.complete(Unit)
        }
    }
}
