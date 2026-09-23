import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
}

val verifierSources = layout.buildDirectory.dir("generated/verifierSources")
val stageVerifierSources = tasks.register<Sync>("stageVerifierSources") {
    from(rootProject.file("app/src/main/java/dev/ipf/whitenoise/android/core/nostr/BIP340.kt"))
    from(rootProject.file("app/src/main/java/dev/ipf/whitenoise/android/core/nostr/NostrEvent.kt"))
    into(verifierSources.map { it.dir("dev/ipf/whitenoise/android/core/nostr") })
}

android {
    namespace = "dev.ipf.whitenoise.android.crypto.benchmark"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        applicationId = "dev.ipf.whitenoise.crypto.benchmark"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("benchmark-test-rules.pro")
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles("benchmark-rules.pro")
        }
    }
    testBuildType = "release"
    sourceSets.getByName("main").kotlin.directories.add(verifierSources.get().asFile.path)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.matching { it.name.startsWith("compile") }.configureEach {
    dependsOn(stageVerifierSources)
}

dependencies {
    implementation(libs.secp256k1.android)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(libs.androidx.tracing)
}
