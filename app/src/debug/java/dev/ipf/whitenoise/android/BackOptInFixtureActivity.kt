package dev.ipf.whitenoise.android

import androidx.activity.ComponentActivity

/**
 * A device-test host that opts in to `OnBackInvokedCallback` the way MainActivity does, with the activity-level
 * `android:enableOnBackInvokedCallback`. Android honours that from 14 and ignores it on 13, so Back reaches a fixture
 * here as it reaches the app on every version, unlike the plain ComponentActivity fixture, which never opts in.
 */
class BackOptInFixtureActivity : ComponentActivity()
