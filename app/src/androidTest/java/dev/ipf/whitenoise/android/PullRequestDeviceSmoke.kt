package dev.ipf.whitenoise.android

/**
 * Marks a device test class the pull-request instrumented job runs; pushes to master run every
 * unattended class. Both jobs exclude @ManualDeviceFixture tests. The PR job filters on this
 * annotation because AGP truncates comma-separated `testInstrumentationRunnerArguments.class`
 * values at the first comma, so only the first listed class ever reached `am instrument`.
 *
 * Read-aloud highlight placement classes belong here: their geometry is measured from real font
 * metrics, which the Robolectric suite only simulates.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class PullRequestDeviceSmoke
