package dev.ipf.whitenoise.android.maestro

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.share.ExternalShareDispatchActivity

/** The provider and sender execute in the real test APK UID, outside the instrumented app. */
internal fun launchMaestroRuntimeActivity(
    context: Context,
    fixture: String,
): ActivityScenario<MainActivity> {
    val scenario = ActivityScenario.launch<MainActivity>(maestroRuntimeLaunchIntent(context, fixture))
    if (!fixture.startsWith("share-external-")) return scenario
    val external = InstrumentationRegistry.getInstrumentation().context
    val streams = maestroExternalShareNames(fixture).toTypedArray()
    external.startActivity(
        Intent(external, ExternalShareDispatchActivity::class.java)
            .putExtra(ExternalShareDispatchActivity.EXTRA_TARGET_PACKAGE, context.packageName)
            .putExtra(ExternalShareDispatchActivity.EXTRA_STREAM_NAMES, streams)
            .putExtra(ExternalShareDispatchActivity.EXTRA_SHARE_TEXT, maestroExpectedShareText(fixture))
            .putExtra(ExternalShareDispatchActivity.EXTRA_GRANT_READ, !fixture.contains("denied"))
            .putExtra(ExternalShareDispatchActivity.EXTRA_EMPTY_STREAM, fixture.contains("empty"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    return scenario
}

internal fun maestroExternalShareNames(fixture: String): List<String> =
    when (fixture) {
        "share-external-multiple" -> listOf("maestro-document.md", "maestro-table.csv")
        "share-external-duplicate" -> listOf("maestro-document.md", "maestro-document.md")
        "share-external-log" -> listOf("maestro-log.log")
        "share-external-calendar" -> listOf("maestro-calendar.ics")
        "share-external-font" -> listOf("maestro-font.ttf")
        "share-external-model" -> listOf("maestro-model.gltf")
        "share-external-unknown" -> listOf("maestro-unknown.fixture")
        "share-external-mixed-types" ->
            listOf(
                "maestro-document.md",
                "maestro-table.csv",
                "maestro-log.log",
                "maestro-calendar.ics",
                "maestro-font.ttf",
                "maestro-model.gltf",
                "maestro-unknown.fixture",
            )
        "share-external-document", "share-external-caption", "share-external-expired-grant",
        "share-external-denied", "share-external-denied-caption", "share-external-empty",
        "share-external-empty-caption",
        -> listOf("maestro-document.md")
        else -> error("Unknown external-share fixture")
    }

internal fun maestroExpectedShareText(fixture: String): String? =
    when (fixture) {
        "share-partial", "share-text", "share-external-caption", "share-external-denied-caption",
        "share-external-empty-caption",
        -> MAESTRO_SHARE_TEXT
        else -> null
    }
