package dev.ipf.whitenoise.android.amber

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Invisible relay that launches the external NIP-55 signer and returns its
 * response to [MainActivity][dev.ipf.whitenoise.android.MainActivity] with a
 * stable client [AmberSignerRelay.EXTRA_REQUEST_ID], even when the signer
 * finishes without `setResult` (RESULT_CANCELED, null data).
 *
 * Each instance owns one request id for its lifetime so a late cancellation from
 * a prior, timed-out prompt still carries the old id and cannot satisfy the next
 * caller. Signer extras are forwarded, then app-private correlation and the
 * package Android actually resolved are stamped over any signer-supplied values.
 */
class AmberSignerRelayActivity : ComponentActivity() {
    private lateinit var signerLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private var requestId: String? = null
    private var signerLaunched = false
    private var finishingSignerResult = false
    private val resultScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID)
        signerLaunched = savedInstanceState?.getBoolean(KEY_SIGNER_LAUNCHED) == true
        signerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                finishWithSignerResult(result.resultCode == RESULT_OK, result.data)
            }

        if (savedInstanceState?.getBoolean(KEY_SIGNER_RESULT_PENDING) == true) {
            // The signer result Intent is deliberately not persisted across
            // recreation: for decrypt operations it carries message plaintext,
            // and saved instance state outlives this translucent relay inside
            // system_server. A result that raced recreation degrades to a
            // cancel — callers already handle signer cancellation/timeout.
            finishWithSignerResult(resultOk = false, signerData = null)
            return
        }

        if (signerLaunched) return

        val signerIntent =
            IntentCompat.getParcelableExtra(
                intent,
                AmberSignerRelay.EXTRA_SIGNER_INTENT,
                Intent::class.java,
            )
        if (requestId == null || signerIntent == null) {
            finishLaunchFailure()
            return
        }

        val preparedIntent = AmberSignerRelay.prepareSignerLaunch(this, requestId!!, signerIntent)
        if (preparedIntent == null) {
            finishLaunchFailure()
            return
        }
        try {
            signerLauncher.launch(preparedIntent)
            signerLaunched = true
        } catch (_: Exception) {
            finishLaunchFailure()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (signerLaunched) {
            outState.putBoolean(KEY_SIGNER_LAUNCHED, true)
        }
        if (finishingSignerResult) {
            outState.putBoolean(KEY_SIGNER_RESULT_PENDING, true)
        }
    }

    override fun onDestroy() {
        resultScope.cancel()
        super.onDestroy()
    }

    private fun finishLaunchFailure() {
        val handledSignerPackage = AmberSignerRelay.consumeHandledSignerPackage(requestId)
        val relayResult =
            AmberSignerRelay
                .buildResultIntent(
                    requestId,
                    signerData = null,
                    handledSignerPackage = handledSignerPackage,
                ).apply {
                    putExtra(AmberSignerRelay.EXTRA_LAUNCH_FAILED, true)
                }
        setResult(RESULT_CANCELED, relayResult)
        finish()
    }

    private fun finishWithSignerResult(
        resultOk: Boolean,
        signerData: Intent?,
    ) {
        if (finishingSignerResult) return
        finishingSignerResult = true
        if (!resultOk) {
            completeSignerResult(resultOk, signerData, AmberSignerRelay.consumeHandledSignerPackage(requestId))
            return
        }
        resultScope.launch {
            val handledSignerPackage =
                AmberSignerRelay.awaitHandledSignerPackage(requestId, CHOOSER_SELECTION_TIMEOUT_MS)
            AmberSignerRelay.consumeHandledSignerPackage(requestId)
            completeSignerResult(resultOk, signerData, handledSignerPackage)
        }
    }

    private fun completeSignerResult(
        resultOk: Boolean,
        signerData: Intent?,
        handledSignerPackage: String?,
    ) {
        val relayResult = AmberSignerRelay.buildResultIntent(requestId, signerData, handledSignerPackage)
        setResult(if (resultOk) RESULT_OK else RESULT_CANCELED, relayResult)
        finish()
    }

    companion object {
        private const val KEY_SIGNER_LAUNCHED = "signer_launched"
        private const val KEY_SIGNER_RESULT_PENDING = "signer_result_pending"
        private const val CHOOSER_SELECTION_TIMEOUT_MS = 2_000L
    }
}
