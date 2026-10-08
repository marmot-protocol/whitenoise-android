import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
}

val verifierSources = layout.buildDirectory.dir("generated/verifierSources")
val stageVerifierSources = tasks.register<Sync>("stageVerifierSources") {
    from(rootProject.file("app/src/main/java/dev/ipf/whitenoise/android/core/nostr/NostrEvent.kt"))
    from(rootProject.file("app/src/main/java/dev/ipf/whitenoise/android/core/nostr/NostrEventVerifier.kt"))
    into(verifierSources.map { it.dir("dev/ipf/whitenoise/android/core/nostr") })
}

// Reuse :app's artifact resolution and checksum-verified preparation task.
evaluationDependsOn(":app")
val marmotKitPreparedDir = project(":app").extra["marmotKitPreparedDir"] as File

android {
    namespace = "dev.ipf.whitenoise.android.crypto.benchmark"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 1
            }
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
            // Match the app's optimized release defaults, including the runtime
            // annotations that JNA uses to recover UniFFI structure field order.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "benchmark-rules.pro",
            )
        }
    }
    testBuildType = "release"
    sourceSets.getByName("main").apply {
        kotlin.directories.add(verifierSources.get().asFile.path)
        kotlin.directories.add(marmotKitPreparedDir.resolve("kotlin").path)
        jniLibs.directories.add(marmotKitPreparedDir.resolve("jniLibs").path)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.named("preBuild").configure {
    dependsOn(stageVerifierSources, ":app:prepareMarmotKitArtifact")
}

dependencies {
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.annotation:annotation:1.9.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(libs.androidx.tracing)
}
