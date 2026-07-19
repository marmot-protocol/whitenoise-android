package dev.ipf.whitenoise.android.amber

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Receives the system chooser's trusted component selection for NIP-55 login. */
class AmberSignerChoiceReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val requestId = intent.getStringExtra(AmberSignerRelay.EXTRA_CHOOSER_REQUEST_ID)
        val component = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        AmberSignerRelay.recordHandledSignerPackage(requestId, component?.packageName)
    }
}
