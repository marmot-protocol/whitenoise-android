plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "dev.ipf.whitenoise.android.benchmark"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.output.enable"] = "true"
    }

    flavorDimensions += "environment"
    flavorDimensions += "distribution"
    productFlavors {
        create("dev") {
            dimension = "environment"
        }
        create("zapstore") {
            dimension = "distribution"
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

baselineProfile {
    // Profile generation uses the authenticated physical-device fixture. An
    // ephemeral AOSP managed device cannot exercise MDK's real group journeys.
    useConnectedDevices = true
}

ktlint {
    version.set(libs.versions.ktlint.get())
    android.set(true)
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.uiautomator)
}
