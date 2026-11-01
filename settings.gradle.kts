pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
