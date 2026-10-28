package dev.ipf.whitenoise.android.perf3439

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.tracing.ComposeTracingInitializer
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import androidx.tracing.profiler.ConnectedProfilerTracingInitializer

/** Diagnostic-only ordering: Compose must capture the configured global wire tracer. */
@SuppressLint("RestrictedApi")
class Perf3439TraceInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        AppInitializer.getInstance(context).initializeComponent(ComposeTracingInitializer::class.java)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(ConnectedProfilerTracingInitializer::class.java)
}
