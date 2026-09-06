import com.android.build.api.attributes.ProductFlavorAttr
import groovy.json.JsonSlurper
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kover)
    alias(libs.plugins.oss.licenses)
}

// Dev, preview, and explicitly unsigned reproducible builds remain usable
// without Firebase credentials. Production packaging is guarded below so a
// signed shipping artifact cannot silently omit native-push resources.
val googleServicesConfigFile = file("google-services.json")
if (googleServicesConfigFile.exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

val marmotKitVersionProperties =
    Properties().apply {
        val file = project.file("src/main/marmotkit/MARMOT_VERSION")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun requiredMarmotKitProperty(key: String): String =
    marmotKitVersionProperties.getProperty(key)
        ?: throw GradleException("Missing MarmotKit lock property '$key'")

val marmotKitArtifactSha = requiredMarmotKitProperty("artifact-sha256")
val marmotKitArchiveRoot = requiredMarmotKitProperty("archive-root")
val marmotKitCacheRoot =
    providers
        .gradleProperty("whitenoise.marmotkit.cacheDir")
        .orNull
        ?.let(rootProject::file)
        ?: providers
            .environmentVariable("WHITENOISE_MARMOTKIT_CACHE_DIR")
            .orNull
            ?.let(rootProject::file)
        ?: gradle.gradleUserHomeDir.resolve("caches/whitenoise/marmotkit")
val marmotKitPreparedDir = marmotKitCacheRoot.resolve(marmotKitArtifactSha).resolve(marmotKitArchiveRoot)
val marmotKitArtifactOverride =
    providers
        .gradleProperty("whitenoise.marmotkit.artifactFile")
        .orNull
        ?.let(rootProject::file)
        ?: providers
            .environmentVariable("WHITENOISE_MARMOTKIT_ARTIFACT_FILE")
            .orNull
            ?.let(rootProject::file)
val marmotKitPreparationLauncher =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        listOf(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            rootProject.file("scripts/prepare-marmotkit-artifact.ps1").absolutePath,
        )
    } else {
        listOf(rootProject.file("scripts/prepare-marmotkit-artifact.sh").absolutePath)
    }

val prepareMarmotKitArtifact =
    tasks.register<Exec>("prepareMarmotKitArtifact") {
        group = "build setup"
        description = "Download and verify the immutable MarmotKit Android artifact"
        inputs.file(project.file("src/main/marmotkit/MARMOT_VERSION"))
        inputs.file(rootProject.file("scripts/prepare_marmotkit_artifact.py"))
        inputs.file(rootProject.file("scripts/prepare-marmotkit-artifact.sh"))
        inputs.file(rootProject.file("scripts/prepare-marmotkit-artifact.ps1"))
        outputs.dir(marmotKitPreparedDir)
        marmotKitArtifactOverride?.let(inputs::file)
        environment("PYTHONDONTWRITEBYTECODE", "1")
        commandLine(*marmotKitPreparationLauncher.toTypedArray())
        args(
            rootProject.file("scripts/prepare_marmotkit_artifact.py"),
            "--lock",
            project.file("src/main/marmotkit/MARMOT_VERSION"),
            "--cache-root",
            marmotKitCacheRoot,
        )
        marmotKitArtifactOverride?.let { args("--artifact", it) }
        if (gradle.startParameter.isOffline) args("--offline")
    }
val stageMarmotKitApiSignature =
    tasks.register<Sync>("stageMarmotKitApiSignature") {
        group = "verification"
        description = "Stage the inspectable MarmotKit API signature for review and CI"
        dependsOn(prepareMarmotKitArtifact)
        from(marmotKitPreparedDir.resolve("marmotkit-api-signature.txt"))
        into(layout.buildDirectory.dir("reports/marmotkit"))
    }

fun signingProperty(vararg keys: String): String? =
    keys
        .asSequence()
        .mapNotNull { key -> localProperties.getProperty(key) ?: System.getenv(key) }
        .firstOrNull()

// Values resolved here are compiled verbatim into BuildConfig and are trivially
// recoverable from any shipped or CI-archived APK — R8 cannot hide a string
// constant. Never provision long-lived or privileged secrets through these keys.
// The *_AUTH_TOKEN fields must only ever carry ingest-only, low-privilege,
// rotatable tokens (or stay empty, which makes telemetry/audit no-op). The
// service-side hardening for this constraint is tracked in #224.
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

// Endpoints and Goggles credentials are shared; OTLP tokens and push identities
// are flavor-specific. Preview deliberately receives none of these inputs.
fun environmentRuntimeConfigProperty(
    environment: String,
    suffix: String,
    defaultValue: String = "",
    includeGlobalFallbacks: Boolean = false,
    extraKeys: List<String> = emptyList(),
): String {
    val environmentPrefix = environment.uppercase()
    val keys =
        buildList {
            add("WHITENOISE_${environmentPrefix}_$suffix")
            if (includeGlobalFallbacks) {
                add("WHITENOISE_$suffix")
            }
            addAll(extraKeys)
        }
    return runtimeConfigProperty(keys, defaultValue)
}

data class ReleaseSigning(
    val keystorePath: String?,
    val keystorePassword: String?,
    val keyAlias: String?,
    val keyPassword: String?,
)

fun ReleaseSigning.isConfigured(): Boolean =
    !keystorePath.isNullOrBlank() &&
        !keystorePassword.isNullOrBlank() &&
        !keyAlias.isNullOrBlank() &&
        !keyPassword.isNullOrBlank() &&
        file(keystorePath!!).exists()

val productionReleaseSigning =
    ReleaseSigning(
        keystorePath =
            signingProperty(
                "WHITENOISE_PRODUCTION_KEYSTORE_PATH",
                "WHITENOISE_KEYSTORE_PATH",
            ),
        keystorePassword =
            signingProperty(
                "WHITENOISE_PRODUCTION_KEYSTORE_PASSWORD",
                "WHITENOISE_KEYSTORE_PASSWORD",
            ),
        keyAlias =
            signingProperty(
                "WHITENOISE_PRODUCTION_KEY_ALIAS",
                "WHITENOISE_KEY_ALIAS",
            ),
        keyPassword =
            signingProperty(
                "WHITENOISE_PRODUCTION_KEY_PASSWORD",
                "WHITENOISE_KEY_PASSWORD",
            ),
    )
val stagingReleaseSigning =
    ReleaseSigning(
        keystorePath = signingProperty("WHITENOISE_STAGING_KEYSTORE_PATH"),
        keystorePassword = signingProperty("WHITENOISE_STAGING_KEYSTORE_PASSWORD"),
        keyAlias = signingProperty("WHITENOISE_STAGING_KEY_ALIAS"),
        keyPassword = signingProperty("WHITENOISE_STAGING_KEY_PASSWORD"),
    )
val hasProductionReleaseSigning = productionReleaseSigning.isConfigured()
val hasStagingReleaseSigning = stagingReleaseSigning.isConfigured()

// Escape hatch for the unsigned-release guard below. Off by default: a release
// build without signing must fail rather than emit an uninstallable artifact.
val allowUnsignedRelease =
    runtimeConfigProperty("WHITENOISE_ALLOW_UNSIGNED_RELEASE", "false")
        .equals("true", ignoreCase = true)
val allowUnconfiguredProductionFirebaseForReleaseRuntimeTest =
    providers
        .gradleProperty("whitenoise.allowUnconfiguredProductionFirebaseForReleaseRuntimeTest")
        .map(String::toBooleanStrict)
        .getOrElse(false)
val productionPushServerPubkeyHex =
    environmentRuntimeConfigProperty(
        environment = "production",
        suffix = "PUSH_SERVER_PUBKEY_HEX",
        includeGlobalFallbacks = true,
    )
val productionPushRelayHint =
    runtimeConfigProperty("WHITENOISE_PUSH_RELAY_HINT", "wss://relay.eu.whitenoise.chat")

// PR preview inputs. The default "stable" channel deliberately keeps one
// applicationId so every PR preview updates the same app and retains its data.
// The "isolated" channel remains available when a reviewer needs side-by-side
// installs or a clean data directory. Both are signed later by a privileged,
// base-branch-only workflow; Gradle never receives the preview signing key.
val prNumber: String? = System.getenv("PR_NUMBER")?.takeIf { it.isNotBlank() }
val prPreviewChannel: String? = System.getenv("PR_PREVIEW_CHANNEL")?.takeIf { it.isNotBlank() }
// Android accepts an update whose versionCode equals the installed version.
// A fixed preview-only code therefore lets a tester move between any two PR
// builds without uninstalling and losing the preview app's data.
val prPreviewVersionCode = 2_000_000_000
val buildShortSha =
    System.getenv("PREVIEW_HEAD_SHA")?.take(7)
        ?: System.getenv("GITHUB_SHA")?.take(7)
        ?: System.getenv("GIT_COMMIT")?.take(7)
        ?: "local"

android {
    namespace = "dev.ipf.whitenoise.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.ipf.whitenoise.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 12
        versionName = "2026.9.5"
        manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
        manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "ENABLE_PERFORMANCE_TEST_SELECTORS", "false")
        buildConfigField("boolean", "WHITENOISE_AUDIT_RUNTIME_REQUIRED", "false")
        buildConfigField("String", "WHITENOISE_AUDIT_DATA_MODE", "".asBuildConfigString())
        // Production release must resolve the local WNPerf facility to a
        // compile-time false constant. Non-production flavors and debug builds
        // override this below; runtime collection still requires explicit opt-in.
        buildConfigField("boolean", "ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS", "false")
        buildConfigField(
            "String",
            "MDK_SHORT_SHA",
            (marmotKitVersionProperties.getProperty("mdk-short-sha") ?: "unknown").asBuildConfigString(),
        )
    }

    flavorDimensions += "environment"
    flavorDimensions += "distribution"

    signingConfigs {
        if (hasProductionReleaseSigning) {
            create("productionRelease") {
                storeFile = file(productionReleaseSigning.keystorePath!!)
                storePassword = productionReleaseSigning.keystorePassword
                keyAlias = productionReleaseSigning.keyAlias
                keyPassword = productionReleaseSigning.keyPassword
            }
        }
        if (hasStagingReleaseSigning) {
            create("stagingRelease") {
                storeFile = file(stagingReleaseSigning.keystorePath!!)
                storePassword = stagingReleaseSigning.keystorePassword
                keyAlias = stagingReleaseSigning.keyAlias
                keyPassword = stagingReleaseSigning.keyPassword
            }
        }
    }

    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appName"] = "White Noise Dev"
            manifestPlaceholders["deepLinkScheme"] = "whitenoise-dev"
            buildConfigField("String", "WHITENOISE_DEEP_LINK_SCHEME", "whitenoise-dev".asBuildConfigString())
            buildConfigField(
                "String",
                "WHITENOISE_OTLP_ENDPOINT",
                runtimeConfigProperty("WHITENOISE_OTLP_ENDPOINT").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_OTLP_AUTH_TOKEN",
                environmentRuntimeConfigProperty(
                    environment = "dev",
                    suffix = "OTLP_AUTH_TOKEN",
                    extraKeys = listOf("OTLP_TOKEN_WHITENOISE_ANDROID_DEV"),
                ).asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_AUDIT_LOG_ENDPOINT",
                environmentRuntimeConfigProperty("dev", "AUDIT_LOG_ENDPOINT").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_AUDIT_LOG_AUTH_TOKEN",
                environmentRuntimeConfigProperty("dev", "AUDIT_LOG_AUTH_TOKEN").asBuildConfigString(),
            )
            buildConfigField("String", "WHITENOISE_DEPLOYMENT_ENVIRONMENT", "dev".asBuildConfigString())
            buildConfigField("boolean", "ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS", "true")
            // Compatibility metadata required by MarmotKit. Tenant routing is
            // selected by the OTLP bearer token, not this fixed resource value.
            buildConfigField(
                "String",
                "WHITENOISE_TELEMETRY_TENANT",
                "whitenoise-android-dev".asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_PUSH_SERVER_PUBKEY_HEX",
                environmentRuntimeConfigProperty("dev", "PUSH_SERVER_PUBKEY_HEX").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_PUSH_RELAY_HINT",
                environmentRuntimeConfigProperty("dev", "PUSH_RELAY_HINT").asBuildConfigString(),
            )
        }

        create("preview") {
            dimension = "environment"
            val previewIdentity = prNumber ?: "local"
            val previewChannel = prPreviewChannel ?: "stable"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_preview"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_preview"
            manifestPlaceholders["deepLinkScheme"] = "whitenoise-preview"
            buildConfigField("String", "WHITENOISE_DEEP_LINK_SCHEME", "whitenoise-preview".asBuildConfigString())
            buildConfigField("String", "WHITENOISE_OTLP_ENDPOINT", "".asBuildConfigString())
            buildConfigField("String", "WHITENOISE_OTLP_AUTH_TOKEN", "".asBuildConfigString())
            buildConfigField("String", "WHITENOISE_AUDIT_LOG_ENDPOINT", "".asBuildConfigString())
            buildConfigField("String", "WHITENOISE_AUDIT_LOG_AUTH_TOKEN", "".asBuildConfigString())
            buildConfigField("String", "WHITENOISE_DEPLOYMENT_ENVIRONMENT", "preview".asBuildConfigString())
            buildConfigField("boolean", "ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS", "true")
            buildConfigField("String", "WHITENOISE_TELEMETRY_TENANT", "whitenoise-android-preview".asBuildConfigString())
            buildConfigField("String", "WHITENOISE_PUSH_SERVER_PUBKEY_HEX", "".asBuildConfigString())
            buildConfigField("String", "WHITENOISE_PUSH_RELAY_HINT", "".asBuildConfigString())
            versionCode = prPreviewVersionCode
            when (previewChannel) {
                "stable" -> {
                    applicationIdSuffix = ".preview"
                    versionNameSuffix = "-preview-pr$previewIdentity-$buildShortSha"
                    manifestPlaceholders["appName"] = "White Noise PR"
                }
                "isolated" -> {
                    applicationIdSuffix = ".preview.pr$previewIdentity"
                    versionNameSuffix = "-preview-pr$previewIdentity-$buildShortSha-isolated"
                    manifestPlaceholders["appName"] = "PR $previewIdentity Isolated"
                    val isolatedDeepLinkScheme = "whitenoise-preview-pr$previewIdentity"
                    manifestPlaceholders["deepLinkScheme"] = isolatedDeepLinkScheme
                    buildConfigField(
                        "String",
                        "WHITENOISE_DEEP_LINK_SCHEME",
                        isolatedDeepLinkScheme.asBuildConfigString(),
                    )
                }
                else -> error("PR_PREVIEW_CHANNEL must be 'stable' or 'isolated'")
            }
        }

        create("production") {
            dimension = "environment"
            if (hasProductionReleaseSigning) {
                signingConfig = signingConfigs.getByName("productionRelease")
            }
            manifestPlaceholders["appName"] = "White Noise"
            manifestPlaceholders["deepLinkScheme"] = "whitenoise"
            buildConfigField("String", "WHITENOISE_DEEP_LINK_SCHEME", "whitenoise".asBuildConfigString())

            buildConfigField(
                "String",
                "WHITENOISE_OTLP_ENDPOINT",
                runtimeConfigProperty("WHITENOISE_OTLP_ENDPOINT").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_OTLP_AUTH_TOKEN",
                environmentRuntimeConfigProperty(
                    environment = "production",
                    suffix = "OTLP_AUTH_TOKEN",
                    extraKeys = listOf("OTLP_TOKEN_WHITENOISE_ANDROID"),
                ).asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_AUDIT_LOG_ENDPOINT",
                runtimeConfigProperty("WHITENOISE_AUDIT_LOG_ENDPOINT").asBuildConfigString(),
            )
            // Deliberately no OTLP fallback: the audit-log tracker (Goggles) is a
            // separate service from the OTLP metrics collector. If the dedicated
            // audit token is unset, leave it empty so uploads skip rather than
            // authenticating against the wrong API with the OTLP token.
            buildConfigField(
                "String",
                "WHITENOISE_AUDIT_LOG_AUTH_TOKEN",
                runtimeConfigProperty("WHITENOISE_AUDIT_LOG_AUTH_TOKEN").asBuildConfigString(),
            )
            buildConfigField("String", "WHITENOISE_DEPLOYMENT_ENVIRONMENT", "production".asBuildConfigString())
            // Compatibility metadata required by MarmotKit. Tenant routing is
            // selected by the OTLP bearer token, not this fixed resource value.
            buildConfigField(
                "String",
                "WHITENOISE_TELEMETRY_TENANT",
                "whitenoise-android".asBuildConfigString(),
            )
            // Push gateway configuration. The pubkey identifies the MIP-05 push
            // server that takes FCM tokens, encrypts notifications, and hands them
            // to the shared relay hint below for delivery. The pubkey is
            // provisioned per environment via local.properties (or the environment). The
            // production packaging guard rejects missing or malformed values.
            buildConfigField(
                "String",
                "WHITENOISE_PUSH_SERVER_PUBKEY_HEX",
                productionPushServerPubkeyHex.asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_PUSH_RELAY_HINT",
                productionPushRelayHint.asBuildConfigString(),
            )
        }

        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            // Every master merge produces a staging APK. Keep the production
            // release version stable while making the installed staging build
            // identifiable from Settings and Android package metadata.
            versionNameSuffix = "-staging-$buildShortSha"
            if (hasStagingReleaseSigning) {
                signingConfig = signingConfigs.getByName("stagingRelease")
            }
            manifestPlaceholders["appName"] = "White Noise Staging"
            manifestPlaceholders["deepLinkScheme"] = "whitenoise-staging"
            buildConfigField("String", "WHITENOISE_DEEP_LINK_SCHEME", "whitenoise-staging".asBuildConfigString())
            buildConfigField(
                "String",
                "WHITENOISE_OTLP_ENDPOINT",
                runtimeConfigProperty("WHITENOISE_OTLP_ENDPOINT").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_OTLP_AUTH_TOKEN",
                environmentRuntimeConfigProperty(
                    environment = "staging",
                    suffix = "OTLP_AUTH_TOKEN",
                    extraKeys = listOf("OTLP_TOKEN_WHITENOISE_ANDROID_STAGING"),
                ).asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_AUDIT_LOG_ENDPOINT",
                runtimeConfigProperty("WHITENOISE_AUDIT_LOG_ENDPOINT").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_AUDIT_LOG_AUTH_TOKEN",
                runtimeConfigProperty("WHITENOISE_AUDIT_LOG_AUTH_TOKEN").asBuildConfigString(),
            )
            buildConfigField(
                "boolean",
                "WHITENOISE_AUDIT_RUNTIME_REQUIRED",
                (runtimeConfigProperty("WHITENOISE_AUDIT_RUNTIME_REQUIRED") == "true").toString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_AUDIT_DATA_MODE",
                runtimeConfigProperty("WHITENOISE_AUDIT_DATA_MODE").asBuildConfigString(),
            )
            buildConfigField("String", "WHITENOISE_DEPLOYMENT_ENVIRONMENT", "staging".asBuildConfigString())
            buildConfigField("boolean", "ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS", "true")
            // Compatibility metadata required by MarmotKit. Tenant routing is
            // selected by the OTLP bearer token, not this fixed resource value.
            buildConfigField(
                "String",
                "WHITENOISE_TELEMETRY_TENANT",
                "whitenoise-android-staging".asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_PUSH_SERVER_PUBKEY_HEX",
                environmentRuntimeConfigProperty("staging", "PUSH_SERVER_PUBKEY_HEX").asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "WHITENOISE_PUSH_RELAY_HINT",
                runtimeConfigProperty("WHITENOISE_PUSH_RELAY_HINT").asBuildConfigString(),
            )
        }

        // Distribution channel, orthogonal to environment — zapstore enables the
        // verified direct-APK self-updater (installer source set + Zapstore
        // manifest permissions), play omits it and routes updates to the listing.
        create("zapstore") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
        }
    }

    buildTypes {
        debug {
            // Debug builds keep each flavor's applicationId so the local
            // google-services.json clients still match. Use the staging release
            // APK for side-by-side device use.
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never fall back to the debug keystore: the Android debug key is
            // public, so a release APK signed with it is trivially forgeable.
            // When signing is absent, the release packaging tasks fail (see the
            // guard below) instead of producing an unsigned artifact.
        }
        // Created by the Baseline Profile plugin from `release`. Keep both
        // performance-only variants installable without weakening the signing
        // contract of a production or staging release APK. They intentionally
        // reuse the dev package so an authenticated dev fixture survives swaps
        // between the debug and release-like benchmark APKs.
        create("benchmarkRelease") {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "ENABLE_PERFORMANCE_TEST_SELECTORS", "true")
        }
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "ENABLE_PERFORMANCE_TEST_SELECTORS", "true")
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
    testOptions {
        // Robolectric-backed screenshot tests render real composables that call
        // stringResource(), so the JVM unit-test classpath must carry the
        // app's merged Android resources.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    sourceSets {
        getByName("main") {
            @Suppress("DEPRECATION")
            kotlin.srcDir(marmotKitPreparedDir.resolve("kotlin"))
            @Suppress("DEPRECATION")
            jniLibs.srcDir(marmotKitPreparedDir.resolve("jniLibs"))
        }
        getByName("test") {
            kotlin.directories.add("src/testSupport/kotlin")
        }
        getByName("androidTest") {
            kotlin.directories.add("src/testSupport/kotlin")
        }
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

tasks.named("preBuild").configure {
    dependsOn(prepareMarmotKitArtifact, stageMarmotKitApiSignature)
}

// AGP exposes the prepared Kotlin directory to source-aware quality plugins.
// Declare its producer explicitly so a combined lint/build invocation is valid
// under Gradle 9's task dependency checks even though ktlint excludes the files.
tasks.matching { it.name.startsWith("runKtlint") }.configureEach {
    dependsOn(prepareMarmotKitArtifact)
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        val environment =
            variantBuilder.productFlavors
                .firstOrNull { it.first == "environment" }
                ?.second
        val enabled =
            when (environment) {
                "dev" ->
                    variantBuilder.buildType in
                        setOf(
                            "debug",
                            "benchmarkRelease",
                            "nonMinifiedRelease",
                        )
                "preview" -> variantBuilder.buildType == "release"
                "production", "staging" -> variantBuilder.buildType == "release"
                else -> true
            }
        variantBuilder.enable = enabled
    }

    // Embed short commit SHA + build date into every release APK filename so
    // multiple CI builds against master don't produce identically named APKs.
    // Local builds without GITHUB_SHA fall back to "local".
    // See issue #992.
    val buildDate = LocalDate.now(ZoneOffset.UTC).toString()

    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            val currentName = output.outputFileName.get()
            val stem = currentName.removeSuffix(".apk")
            val suffix = if (currentName.endsWith(".apk")) ".apk" else ""
            output.outputFileName.set("$stem-$buildDate-$buildShortSha$suffix")
        }
    }

    // Mirror the release-build naming convention onto dev debug APKs during
    // per-PR preview builds so testers can tell PR previews apart on disk in
    // the same way they can tell master-branch staging APKs apart.
    if (prNumber != null) {
        onVariants(selector().withBuildType("debug").withFlavor("environment" to "dev")) { variant ->
            variant.outputs.forEach { output ->
                val currentName = output.outputFileName.get()
                val stem = currentName.removeSuffix(".apk")
                val suffix = if (currentName.endsWith(".apk")) ".apk" else ""
                output.outputFileName.set("whitenoise-pr$prNumber-$stem-$buildDate-$buildShortSha$suffix")
            }
        }
    }
}

// Compose compiler reports are opt-in because they add work and generate a
// large number of files. CI enables the property for an optimized staging
// compilation and publishes both the metrics and stability reports.
val enableComposeCompilerReports =
    providers
        .gradleProperty("whitenoise.enableComposeCompilerReports")
        .map(String::toBooleanStrict)
        .getOrElse(false)

composeCompiler {
    // MarmotKit's UniFFI records are `data class`es with `var` properties, so
    // the compiler infers them Unstable and every Android projection that
    // holds one inherits that. Under strong skipping an unstable parameter is
    // compared by identity, so a value-identical row rebuilt by the next
    // engine update recomposes instead of skipping. The app treats MDK records
    // as immutable values (rebuilt with `copy`, never mutated in place), so
    // declaring them stable restores structural-equality skipping.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("config/compose-stability.conf"))
    if (enableComposeCompilerReports) {
        metricsDestination = layout.buildDirectory.dir("compose-metrics")
        reportsDestination = layout.buildDirectory.dir("compose-reports")
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
    mergeIntoMain = true
    saveInSrc = true
    warnings {
        // Other environments intentionally expose only their supported release
        // variant; performance tooling is scoped to the safe dev package.
        disabledVariants = false
    }
}

// The producer intentionally has one safe dev/zapstore fixture variant. Point
// every release consumer's profile-only configuration at that artifact without
// adding broad flavor fallbacks to the app's runtime dependency graph.
val baselineProfileEnvironment = objects.named(ProductFlavorAttr::class.java, "dev")
val baselineProfileDistribution = objects.named(ProductFlavorAttr::class.java, "zapstore")
afterEvaluate {
    configurations
        .matching { it.name.endsWith("ReleaseBaselineProfile") }
        .configureEach {
            attributes {
                attribute(ProductFlavorAttr.of("environment"), baselineProfileEnvironment)
                attribute(ProductFlavorAttr.of("distribution"), baselineProfileDistribution)
            }
        }
}

// Task names carry the distribution flavor between the environment and the build
// type (e.g. packageStagingZapstoreRelease…), so match on the environment alone.
// This runs only on release package tasks, so the build type is already implied.
fun releaseSigningConfiguredForPackageTask(taskName: String): Boolean =
    when {
        taskName.contains("Production") -> hasProductionReleaseSigning
        taskName.contains("Staging") -> hasStagingReleaseSigning
        else -> false
    }

fun releaseSigningHintForPackageTask(taskName: String): String =
    when {
        taskName.contains("Production") ->
            "WHITENOISE_PRODUCTION_KEYSTORE_PATH/PASSWORD/KEY_ALIAS/KEY_PASSWORD " +
                "(or WHITENOISE_KEYSTORE_* fallback)"

        taskName.contains("Staging") ->
            "WHITENOISE_STAGING_KEYSTORE_PATH/PASSWORD/KEY_ALIAS/KEY_PASSWORD"

        else -> "release signing credentials"
    }

val productionApplicationId = "dev.ipf.whitenoise.android"
val mip05PubkeyRegex = Regex("^[0-9a-fA-F]{64}$")

fun isValidProductionPushRelayHint(rawRelayHint: String): Boolean {
    val relayUri = runCatching { URI(rawRelayHint) }.getOrNull() ?: return false
    return relayUri.scheme.equals("wss", ignoreCase = true) &&
        !relayUri.host.isNullOrBlank() &&
        relayUri.userInfo == null &&
        relayUri.fragment == null
}

fun configuredGoogleServicesPackageNames(): Set<String> {
    val root =
        try {
            JsonSlurper().parse(googleServicesConfigFile) as? Map<*, *>
        } catch (exception: Exception) {
            throw GradleException(
                "Unable to parse app/google-services.json for production release packaging.",
                exception,
            )
        }
    val clients = root?.get("client") as? Iterable<*> ?: emptyList<Any>()

    return clients
        .mapNotNull { client ->
            val clientInfo = (client as? Map<*, *>)?.get("client_info") as? Map<*, *>
            val androidClientInfo = clientInfo?.get("android_client_info") as? Map<*, *>
            androidClientInfo?.get("package_name") as? String
        }.filter(String::isNotBlank)
        .toSet()
}

fun mayOmitProductionReleaseConfig(): Boolean {
    val isUnsignedReproducibleBuild = allowUnsignedRelease && !hasProductionReleaseSigning
    val requestedTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(":") }
    val isDisposableReleaseRuntimeVerifier =
        allowUnconfiguredProductionFirebaseForReleaseRuntimeTest &&
            System.getenv("GITHUB_WORKFLOW") == "Android Release Runtime Verify" &&
            requestedTasks.isNotEmpty() &&
            requestedTasks.all { it == "assembleProductionZapstoreRelease" }

    return isUnsignedReproducibleBuild || isDisposableReleaseRuntimeVerifier
}

val verifyProductionFirebaseConfig =
    tasks.register("verifyProductionFirebaseConfig") {
        group = "verification"
        description = "Require Firebase resources for production release packaging"

        doLast {
            if (mayOmitProductionReleaseConfig()) {
                logger.warn("Production Firebase validation skipped for a non-publishable verification build.")
                return@doLast
            }
            if (!googleServicesConfigFile.isFile) {
                throw GradleException(
                    "Production release packaging requires app/google-services.json " +
                        "with an Android client for $productionApplicationId.",
                )
            }
            if (productionApplicationId !in configuredGoogleServicesPackageNames()) {
                throw GradleException(
                    "app/google-services.json does not contain an Android client for " +
                        "$productionApplicationId; refusing production release packaging.",
                )
            }
        }
    }

val verifyProductionPushConfig =
    tasks.register("verifyProductionPushConfig") {
        group = "verification"
        description = "Require a valid MIP-05 server identity for production release packaging"

        doLast {
            if (mayOmitProductionReleaseConfig()) {
                logger.warn("Production push validation skipped for a non-publishable verification build.")
                return@doLast
            }
            if (!mip05PubkeyRegex.matches(productionPushServerPubkeyHex)) {
                throw GradleException(
                    "Production release packaging requires " +
                        "WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX " +
                        "(or WHITENOISE_PUSH_SERVER_PUBKEY_HEX) to be exactly 64 hexadecimal characters.",
                )
            }
            if (!isValidProductionPushRelayHint(productionPushRelayHint)) {
                throw GradleException(
                    "Production release packaging requires " +
                        "WHITENOISE_PUSH_RELAY_HINT to be a valid wss:// URI.",
                )
            }
        }
    }

// Attach the guard to production packaging entry points and their terminal
// tasks. Manifest/resource inspection stays credential-free, while every
// production APK/AAB graph requires validation. The runtime-verifier exemption
// is restricted to its exact Zapstore assemble task, so it cannot authorize a
// Play publication.
tasks
    .matching {
        (
            it.name.startsWith("assembleProduction") ||
                it.name.startsWith("packageProduction") ||
                it.name.startsWith("bundleProduction")
        ) &&
            it.name.endsWith("Release")
    }.configureEach {
        dependsOn(verifyProductionFirebaseConfig, verifyProductionPushConfig)
    }
tasks
    .matching {
        it.name != "verifyProductionFirebaseConfig" &&
            it.name.contains("Production") &&
            it.name.contains("Release")
    }.configureEach {
        mustRunAfter(verifyProductionFirebaseConfig)
    }

// Fail any release packaging task when signing isn't configured for that
// environment. Checked at execution time so debug builds are never affected; an
// unsigned release APK is uninstallable, so a build that "succeeds" while
// emitting one hides a release-blocking failure. Override with
// WHITENOISE_ALLOW_UNSIGNED_RELEASE=true.
tasks
    .matching {
        it.name.startsWith("package") &&
            it.name.endsWith("Release") &&
            !it.name.contains("BenchmarkRelease") &&
            !it.name.contains("NonMinifiedRelease")
    }.configureEach {
        doFirst {
            if (!releaseSigningConfiguredForPackageTask(name) && !allowUnsignedRelease) {
                throw GradleException(
                    "Release signing is not configured for $name (set ${releaseSigningHintForPackageTask(name)}). " +
                        "Refusing to produce an unsigned release artifact; " +
                        "set WHITENOISE_ALLOW_UNSIGNED_RELEASE=true to override.",
                )
            }
        }
    }

kover {
    currentProject {
        createVariant("statePackageFloor") {
            add("devZapstoreDebug")
        }
    }
    reports {
        filters {
            excludes {
                // Keep coverage focused on app-owned code. These mirror the ktlint
                // exclusions below: prepared MarmotKit sources and Android's
                // generated BuildConfig class.
                classes(
                    "dev.ipf.marmotkit.*",
                    "io.crates.keyring.*",
                    "*.BuildConfig",
                )
            }
        }
        verify {
            // Baseline from origin/master @ ccc33f46169f01ee206d9365c13087cce19a9c8a via
            // `./gradlew :app:koverXmlReportDevZapstoreDebug` (aggregate LINE 68.2958%,
            // BRANCH 53.3771%; state-package LINE 63.3282%). Floors use the measured
            // integer percent with a small safety margin; raise only after a fresh
            // report on the same task.
            rule("aggregate line floor") {
                minBound(68, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
            }
            rule("aggregate branch floor") {
                bound {
                    minValue = 53
                    coverageUnits = CoverageUnit.BRANCH
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
        }
        variant("statePackageFloor") {
            filters {
                excludes {
                    classes(
                        "dev.ipf.marmotkit.*",
                        "io.crates.keyring.*",
                        "*.BuildConfig",
                    )
                }
                includes {
                    packages("dev.ipf.whitenoise.android.state")
                }
            }
            verify {
                rule("dev.ipf.whitenoise.android.state line floor") {
                    minBound(63, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}

detekt {
    // Cover every repository-owned app source set, including future flavors.
    // Prepared MarmotKit sources live outside src/ and are validated as part of
    // their immutable artifact instead.
    source.setFrom(
        fileTree("src") {
            include("**/*.kt")
        },
    )
    // Start from detekt's defaults and keep project-specific changes in one
    // checked-in file. The baseline freezes existing debt; new findings fail.
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/detekt-baseline.xml")
    parallel = true
}

ktlint {
    // Pin the ktlint engine from the version catalog so rule behavior is
    // stable across plugin upgrades.
    version.set(libs.versions.ktlint.get())
    // Enable ktlint's Android rule set (this is an AGP application module).
    android.set(true)
    ignoreFailures.set(false)
    filter {
        // Never lint/format prepared MarmotKit sources. They are verified as
        // immutable artifact bytes with their matching native libraries.
        // Normalize separators so the matches also hold on Windows paths.
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
    val bouncyCastleVersion = providers.gradleProperty("bouncycastle.version").get()

    constraints {
        testImplementation("org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion") {
            because("CVE-2025-14813 is fixed in Bouncy Castle 1.84")
        }
        testImplementation("org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion") {
            because("keep Bouncy Castle test modules on one security-fixed release")
        }
        testImplementation("org.bouncycastle:bcutil-jdk18on:$bouncyCastleVersion") {
            because("keep Bouncy Castle test modules on one security-fixed release")
        }
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)
    implementation(libs.osmdroid.android)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.emoji2.emojipicker)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.base)
    implementation(libs.play.services.oss.licenses)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.tracing)
    implementation(libs.okhttp)
    // One profile is generated from the authenticated dev/zapstore fixture and
    // merged into main for every release consumer. Select that producer
    // configuration explicitly so staging/play/production consumers do not
    // demand unsafe benchmark variants with their own package identities.
    baselineProfile(
        project(
            path = ":benchmark",
            configuration = "devZapstoreReleaseBaselineProfile",
        ),
    )
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json for JVM unit tests — the android.jar stubs throw on use.
    testImplementation(libs.org.json)
    // Roborazzi Compose screenshot tests run on the JVM via Robolectric, so the
    // Compose tooling + Roborazzi artifacts live on the unit-test classpath.
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

android.sourceSets.named("test") {
    resources.directories.add(rootProject.file("fuzz/build/app-fuzz-synthetic-corpus/fuzz-synthetic-corpus").path)
}

tasks.withType<Test>().configureEach {
    // Resource-backed Robolectric tests retain Android SDK sandboxes across the large unit suite.
    // Double Gradle's 512 MiB worker default while keeping one bounded, non-parallel process per task.
    maxHeapSize = "1g"
}

tasks.register<Test>("replayAppFuzzSyntheticCorpus") {
    group = "verification"
    description = "Replay checked-in synthetic fuzz corpora through named app unit suites"
    dependsOn(":fuzz:syncAppFuzzSyntheticCorpus")
}

tasks.matching { it.name.startsWith("process") && it.name.endsWith("UnitTestJavaRes") }.configureEach {
    dependsOn(":fuzz:syncAppFuzzSyntheticCorpus")
}

afterEvaluate {
    val referenceTest = tasks.named<Test>("testDevZapstoreDebugUnitTest")
    tasks.named<Test>("replayAppFuzzSyntheticCorpus").configure {
        val reference = referenceTest.get()
        testClassesDirs = reference.testClassesDirs
        classpath = reference.classpath
        reference.taskDependencies.getDependencies(reference).forEach { dependency ->
            dependsOn(dependency)
        }
        filter {
            includeTestsMatching("dev.ipf.whitenoise.android.updates.NostrEventVerifierTest")
            includeTestsMatching("dev.ipf.whitenoise.android.updates.ZapstoreEventsTest")
            includeTestsMatching("dev.ipf.whitenoise.android.updates.ZapstoreReleaseClientTest")
            includeTestsMatching("dev.ipf.whitenoise.android.core.ProfileLinkTest")
            includeTestsMatching("dev.ipf.whitenoise.android.core.RecipientReferenceTest")
            includeTestsMatching("dev.ipf.whitenoise.android.core.GroupSystemEventsTest")
            includeTestsMatching("dev.ipf.whitenoise.android.media.MediaReferenceSupportTest")
            includeTestsMatching("dev.ipf.whitenoise.android.amber.Nip55SignerParsingTest")
        }
    }
}

tasks.configureEach {
    if (name == "koverVerifyDevZapstoreDebug") {
        dependsOn("koverVerifyStatePackageFloor")
    }
}
