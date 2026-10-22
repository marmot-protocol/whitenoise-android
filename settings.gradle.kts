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
                // The portal marker differs from the reviewed Maven Central POM.
                excludeModule("dev.detekt", "dev.detekt.gradle.plugin")
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
