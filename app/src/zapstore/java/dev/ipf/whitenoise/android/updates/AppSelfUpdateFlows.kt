package dev.ipf.whitenoise.android.updates

import android.content.Context

object AppSelfUpdateFlows {
    fun create(appContext: Context): AppSelfUpdateFlow = ZapstoreAppSelfUpdateFlow(appContext)
}
