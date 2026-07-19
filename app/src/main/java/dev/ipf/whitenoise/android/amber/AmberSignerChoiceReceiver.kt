package dev.ipf.whitenoise.android.amber

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.chooser.ChooserResult

/** Receives the system chooser's trusted component selection for NIP-55 login. */
class AmberSignerChoiceReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val requestId = intent.getStringExtra(AmberSignerRelay.EXTRA_CHOOSER_REQUEST_ID)
        val component = chosenSignerComponent(intent)
        AmberSignerRelay.recordHandledSignerPackage(requestId, component?.packageName)
    }
}

internal fun chosenSignerComponent(intent: Intent): ComponentName? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val result = intent.getParcelableExtra(Intent.EXTRA_CHOOSER_RESULT, ChooserResult::class.java)
        if (result?.type == ChooserResult.CHOOSER_RESULT_SELECTED_COMPONENT) {
            return result.selectedComponent
        }
    }
    return intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
}
