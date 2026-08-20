// Set a security floor without retaining modules AGP no longer requests.
buildscript {
    val bouncyCastleVersion = providers.gradleProperty("bouncycastle.version").get()

    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion") {
                because("CVE-2025-14813 is fixed in Bouncy Castle 1.84")
            }
            classpath("org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion") {
                because("keep Bouncy Castle build modules on one security-fixed release")
            }
            classpath("org.bouncycastle:bcutil-jdk18on:$bouncyCastleVersion") {
                because("keep Bouncy Castle build modules on one security-fixed release")
            }
        }
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.oss.licenses) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.kover) apply false
}
