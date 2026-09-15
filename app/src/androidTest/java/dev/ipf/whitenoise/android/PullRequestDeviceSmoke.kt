package dev.ipf.whitenoise.android

/**
 * Marks a device test class the pull-request instrumented job runs; pushes to master run every
 * class. The job filters on this annotation because AGP forwards a comma-separated
 * `testInstrumentationRunnerArguments.class` list truncated at the first comma, so only the
 * first listed class ever reached `am instrument`.
 *
 * Read-aloud highlight placement classes belong here: their geometry is measured from real font
 * metrics, which the Robolectric suite only simulates.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class PullRequestDeviceSmoke
