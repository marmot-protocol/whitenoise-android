import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun signingProperty(key: String): String? = localProperties.getProperty(key) ?: System.getenv(key)

fun runtimeConfigProperty(
    keys: List<String>,
    defaultValue: String = "",
): String =
    keys
        .asSequence()
        .mapNotNull { key -> localProperties.getProperty(key) ?: System.getenv(key) }
        .firstOrNull()
        ?: defaultValue

fun runtimeConfigProperty(
    key: String,
    defaultValue: String = "",
): String = runtimeConfigProperty(listOf(key), defaultValue)

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val releaseKeystorePath = signingProperty("DARKMATTER_KEYSTORE_PATH")
val releaseKeystorePassword = signingProperty("DARKMATTER_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty("DARKMATTER_KEY_ALIAS")
val releaseKeyPassword = signingProperty("DARKMATTER_KEY_PASSWORD")
val hasReleaseSigning =
    !releaseKeystorePath.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank() &&
        file(releaseKeystorePath!!).exists()

android {
    namespace = "dev.ipf.darkmatter"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.ipf.darkmatter"
        minSdk = 34
        targetSdk = 36
        versionCode = 4
        versionName = "2026.6.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DARKMATTER_OTLP_ENDPOINT", runtimeConfigProperty("DARKMATTER_OTLP_ENDPOINT").asBuildConfigString())
        buildConfigField(
            "String",
            "DARKMATTER_OTLP_AUTH_TOKEN",
            runtimeConfigProperty(listOf("DARKMATTER_OTLP_AUTH_TOKEN", "OTLP_TOKEN_DARKMATTER_ANDROID")).asBuildConfigString(),
        )
        buildConfigField("String", "DARKMATTER_AUDIT_LOG_ENDPOINT", runtimeConfigProperty("DARKMATTER_AUDIT_LOG_ENDPOINT").asBuildConfigString())
        // Deliberately no OTLP fallback: the audit-log tracker (Goggles) is a
        // separate service from the OTLP metrics collector. If the dedicated
        // audit token is unset, leave it empty so uploads skip rather than
        // authenticating against the wrong API with the OTLP token.
        buildConfigField(
            "String",
            "DARKMATTER_AUDIT_LOG_AUTH_TOKEN",
            runtimeConfigProperty("DARKMATTER_AUDIT_LOG_AUTH_TOKEN").asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "DARKMATTER_DEPLOYMENT_ENVIRONMENT",
            runtimeConfigProperty("DARKMATTER_DEPLOYMENT_ENVIRONMENT", "production").asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "DARKMATTER_TELEMETRY_TENANT",
            runtimeConfigProperty("DARKMATTER_TELEMETRY_TENANT", "darkmatter-android").asBuildConfigString(),
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Side-by-side install with the release-signed APK Jeff distributes.
            // Different applicationId -> separate sandbox (data dir, keystore
            // entries, SharedPreferences) so the two installs never collide.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            excludes +=
                setOf(
                    "lib/armeabi/libjnidispatch.so",
                    "lib/mips/libjnidispatch.so",
                    "lib/mips64/libjnidispatch.so",
                )
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

ktlint {
    // Pin the ktlint engine from the version catalog so rule behavior is
    // stable across plugin upgrades.
    version.set(libs.versions.ktlint.get())
    // Enable ktlint's Android rule set (this is an AGP application module).
    android.set(true)
    ignoreFailures.set(false)
    filter {
        // Never lint/format the UniFFI-generated bindings or the vendored
        // keyring stub — they are regenerated wholesale and must stay
        // byte-stable with the matching native libs. Normalize separators so
        // the matches also hold on Windows paths.
        fun normalized(path: String) = path.replace('\\', '/')
        exclude { normalized(it.file.path).contains("/marmotkit/") }
        exclude { normalized(it.file.path).contains("marmot_uniffi.kt") }
        exclude { normalized(it.file.path).contains("/io/crates/") }
        exclude { normalized(it.file.path).contains("Keyring.kt") }
        // Generated outputs (BuildConfig, etc.).
        exclude { normalized(it.file.path).contains("/build/") }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.emoji2.emojipicker)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
