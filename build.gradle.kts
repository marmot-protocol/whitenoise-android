// Set a security floor without retaining modules AGP no longer requests.
buildscript {
    val bouncyCastleVersion = providers.gradleProperty("bouncycastle.version").get()

    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion") {
                because("Bouncy Castle 1.86 includes the 1.85 and 1.86 security fixes")
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
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.kover) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        // android-ci sets the worker count per runner; local and other workflow runs stay serial.
        maxParallelForks = providers.gradleProperty("ciTestForks").map(String::toInt).getOrElse(1)

        // android-ci splits the app unit suite across parallel jobs. Each shard runs the test
        // classes whose top-level class name hashes to its index, keeping nested classes with
        // their outer class. String.hashCode is specified, so every runner selects the same set.
        val shard = providers.gradleProperty("ciTestShard").map(String::toInt)
        val shardCount = providers.gradleProperty("ciTestShardCount").map(String::toInt)
        if (shard.isPresent && name.matches(Regex("test.*UnitTest"))) {
            val index = shard.get()
            val count = shardCount.get()
            require(count > 0 && index in 0 until count) { "ciTestShard $index is outside 0 until $count" }
            exclude { element ->
                val path = element.relativePath.pathString
                !element.isDirectory && path.endsWith(".class") &&
                    Math.floorMod(path.removeSuffix(".class").substringBefore('$').hashCode(), count) != index
            }
        }

        // Reuse compilation/analysis outputs, but execute assertions in each
        // fresh CI job, including screenshot comparisons against the baselines.
        outputs.doNotCacheIf("CI test assertions must execute") {
            providers.environmentVariable("CI").orNull == "true"
        }
    }
}
