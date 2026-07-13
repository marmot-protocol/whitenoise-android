package dev.ipf.whitenoise.android.amber

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible relay that launches the external NIP-55 signer and returns its
 * response to [MainActivity][dev.ipf.whitenoise.android.MainActivity] with a
 * stable client [AmberSignerRelay.EXTRA_REQUEST_ID], even when the signer
 * finishes without `setResult` (RESULT_CANCELED, null data).
 *
 * Each instance owns one request id for its lifetime so a late cancellation from
 * a prior, timed-out prompt still carries the old id and cannot satisfy the next
 * caller. Signer extras are forwarded unchanged; relay correlation uses a
 * separate app-private extra.
 */
class AmberSignerRelayActivity : ComponentActivity() {
    private lateinit var signerLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private var requestId: String? = null
    private var signerLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID)
        signerLaunched = savedInstanceState?.getBoolean(KEY_SIGNER_LAUNCHED) == true
        signerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                finishWithSignerResult(result.resultCode == RESULT_OK, result.data)
            }

        if (signerLaunched) return

        val signerIntent = intent.getParcelableExtra(AmberSignerRelay.EXTRA_SIGNER_INTENT, Intent::class.java)
        if (requestId == null || signerIntent == null) {
            finishLaunchFailure()
            return
        }

        try {
            signerLauncher.launch(signerIntent)
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
    }

    private fun finishLaunchFailure() {
        val relayResult =
            AmberSignerRelay.buildResultIntent(requestId, signerData = null).apply {
                putExtra(AmberSignerRelay.EXTRA_LAUNCH_FAILED, true)
            }
        setResult(RESULT_CANCELED, relayResult)
        finish()
    }

    private fun finishWithSignerResult(
        resultOk: Boolean,
        signerData: Intent?,
    ) {
        val relayResult = AmberSignerRelay.buildResultIntent(requestId, signerData)
        setResult(if (resultOk) RESULT_OK else RESULT_CANCELED, relayResult)
        finish()
    }

    companion object {
        private const val KEY_SIGNER_LAUNCHED = "signer_launched"
    }
}
