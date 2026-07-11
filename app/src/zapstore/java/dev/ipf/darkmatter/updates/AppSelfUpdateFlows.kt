package dev.ipf.darkmatter.updates

import android.content.Context

object AppSelfUpdateFlows {
    fun create(appContext: Context): AppSelfUpdateFlow = ZapstoreAppSelfUpdateFlow(appContext)
}
