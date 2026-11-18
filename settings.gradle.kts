pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's byte-for-byte copy of Maven Central, tried first. Shared CI runners hit Central's per-address
        // limit whenever an artifact is not cached yet — HTTP 429, then 403 — and the build fails. Strict checksum
        // verification covers every repository, and Central stays the fallback for anything not yet synced.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "GoogleMavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal {
            content {
                // These portal markers differ from the reviewed Maven Central POMs.
                excludeModule("dev.detekt", "dev.detekt.gradle.plugin")
                excludeModule("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.jvm.gradle.plugin")
                excludeModule("org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.compose.gradle.plugin")
                excludeModule("org.jetbrains.kotlinx.kover", "org.jetbrains.kotlinx.kover.gradle.plugin")
            }
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // The same Maven Central copy as for plugins above, tried before Central itself.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "GoogleMavenCentralMirror" }
        mavenCentral()
    }
}

rootProject.name = "whitenoise-android"
include(":app")
include(":benchmark")
include(":cryptoBenchmark")
include(":fuzz")

// Install before project evaluation so plugin and tool classpaths receive the same floors.
apply(from = "gradle/dependency-inputs.settings.gradle")
apply(from = "gradle/dependency-security.settings.gradle")
