import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(17)
}

ktlint {
    version.set(libs.versions.ktlint.get())
    filter {
        exclude("**/fuzz-production-sources/**")
        exclude("**/build/generated/**")
    }
}

// Synced app parser copies are linted in :app; only fuzz-owned main sources matter here.
tasks.named("ktlintMainSourceSetCheck").configure {
    enabled = false
}

val appProductionRoot = rootProject.file("app/src/main/java")

val fuzzProductionIncludes =
    listOf(
        "dev/ipf/whitenoise/android/updates/NostrEvent.kt",
        "dev/ipf/whitenoise/android/updates/BIP340.kt",
        "dev/ipf/whitenoise/android/updates/ZapstoreRelayFrames.kt",
        "dev/ipf/whitenoise/android/core/ProfileLink.kt",
        "dev/ipf/whitenoise/android/core/RecipientReference.kt",
        "dev/ipf/whitenoise/android/amber/Nip55SignerPure.kt",
    )

val syncFuzzProductionSources =
    tasks.register<Sync>("syncFuzzProductionSources") {
        from(appProductionRoot) {
            include(fuzzProductionIncludes)
        }
        into(layout.buildDirectory.dir("generated/fuzz-production-sources"))
    }

sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir(syncFuzzProductionSources.map { it.destinationDir })
        }
    }
}

dependencies {
    implementation(libs.org.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jazzer.junit)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(syncFuzzProductionSources)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

data class FuzzCampaignTarget(
    val taskName: String,
    val testClass: String,
    val methodName: String,
    val seedCorpusPath: String,
)

val fuzzCampaignTargets =
    listOf(
        FuzzCampaignTarget(
            taskName = "fuzzZapstoreProtocol",
            testClass = "dev.ipf.whitenoise.android.fuzz.ZapstoreProtocolFuzzTest",
            methodName = "fuzzZapstoreProtocol",
            seedCorpusPath =
                "src/test/resources/dev/ipf/whitenoise/android/fuzz/" +
                    "ZapstoreProtocolFuzzTestInputs/fuzzZapstoreProtocol",
        ),
        FuzzCampaignTarget(
            taskName = "fuzzIdentityReference",
            testClass = "dev.ipf.whitenoise.android.fuzz.IdentityReferenceFuzzTest",
            methodName = "fuzzIdentityReference",
            seedCorpusPath =
                "src/test/resources/dev/ipf/whitenoise/android/fuzz/" +
                    "IdentityReferenceFuzzTestInputs/fuzzIdentityReference",
        ),
        FuzzCampaignTarget(
            taskName = "fuzzNip55SignerProtocol",
            testClass = "dev.ipf.whitenoise.android.fuzz.Nip55SignerProtocolFuzzTest",
            methodName = "fuzzNip55SignerProtocol",
            seedCorpusPath =
                "src/test/resources/dev/ipf/whitenoise/android/fuzz/" +
                    "Nip55SignerProtocolFuzzTestInputs/fuzzNip55SignerProtocol",
        ),
    )

val syncFuzzRegressionCorpusOverlay =
    tasks.register<Sync>("syncFuzzRegressionCorpusOverlay") {
        fuzzCampaignTargets.forEach { target ->
            val classSimpleName = target.testClass.substringAfterLast('.')
            from(layout.projectDirectory.dir("regression-corpus/${target.taskName}")) {
                into(
                    "dev/ipf/whitenoise/android/fuzz/" +
                        "${classSimpleName}Inputs/${target.methodName}",
                )
            }
        }
        into(layout.buildDirectory.dir("fuzz-regression-overlay"))
    }

tasks.register<Test>("replayFuzzRegression") {
    group = "verification"
    description = "Replay checked-in fuzz regression corpora without unbounded fuzzing"
    dependsOn(tasks.testClasses, syncFuzzRegressionCorpusOverlay)
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath =
        sourceSets.test.get().runtimeClasspath +
        files(syncFuzzRegressionCorpusOverlay.get().destinationDir)
    val replayRoot = project.findProperty("fuzzReplayInputsDir")?.toString()
    if (replayRoot != null) {
        classpath = classpath + files(replayRoot)
    }
    useJUnitPlatform {
        includeTags("jazzer")
    }
    timeout.set(Duration.ofMinutes(5))
}

val fuzzJobsApplied = "-jobs=2"
val fuzzWorkersApplied = "-workers=2"
val fuzzMaxLen = "65536"

fun durationToSeconds(duration: String): Int {
    val match =
        Regex("""(?i)^(\d+)([smh])$""").matchEntire(duration.trim())
            ?: error("Unsupported fuzz duration: $duration")
    val value = match.groupValues[1].toLong()
    return when (match.groupValues[2].lowercase()) {
        "s" -> value.toInt()
        "m" -> (value * 60).toInt()
        "h" -> (value * 3600).toInt()
        else -> error("Unsupported fuzz duration unit: $duration")
    }.coerceAtLeast(1)
}

fun org.gradle.api.tasks.JavaExec.configureFuzzCampaign(target: FuzzCampaignTarget) {
    group = "fuzzing"
    description = "Bounded standalone Jazzer campaign for ${target.taskName}"
    dependsOn(tasks.testClasses)
    mainClass.set("com.code_intelligence.jazzer.Jazzer")
    classpath = sourceSets.test.get().runtimeClasspath
    val corpusDir =
        layout.buildDirectory
            .dir("cifuzz-corpus/${target.taskName}")
            .get()
            .asFile
    val evolvingCorpusDir = corpusDir.resolve("generated")
    val seedCorpusDir = layout.projectDirectory.dir(target.seedCorpusPath).asFile
    corpusDir.mkdirs()
    evolvingCorpusDir.mkdirs()
    workingDir = corpusDir
    val maxHeap = project.findProperty("fuzzMaxHeap")?.toString() ?: "2g"
    jvmArgs = listOf("-Xmx$maxHeap")
    val jazzerArgs =
        mutableListOf(
            "--target_class=${target.testClass}",
            "--target_method=${target.methodName}",
            "-max_len=$fuzzMaxLen",
            fuzzJobsApplied,
            fuzzWorkersApplied,
            evolvingCorpusDir.absolutePath,
            seedCorpusDir.absolutePath,
        )
    val totalRuns = project.findProperty("fuzzRuns")?.toString()?.takeIf { it.isNotBlank() }
    if (totalRuns != null) {
        jazzerArgs += "-runs=$totalRuns"
    } else {
        val maxDuration = project.findProperty("fuzzMaxDuration")?.toString() ?: "3m"
        jazzerArgs += "-max_total_time=${durationToSeconds(maxDuration)}"
    }
    args = jazzerArgs
}

fuzzCampaignTargets.forEach { target ->
    tasks.register<JavaExec>(target.taskName) {
        configureFuzzCampaign(target)
    }
}

tasks.register<Exec>("fuzzScheduledDryRun") {
    group = "fuzzing"
    description = "Sequential bounded fuzz campaigns for all phase-1 targets (scheduled CI style)"
    val script = rootProject.file("scripts/fuzz-run-campaign.sh")
    commandLine(script)
    environment(
        mapOf(
            "FUZZ_MAX_DURATION" to (project.findProperty("fuzzMaxDuration")?.toString() ?: "3m"),
            "FUZZ_MAX_HEAP" to (project.findProperty("fuzzMaxHeap")?.toString() ?: "2g"),
            "FUZZ_JOBS_APPLIED" to fuzzJobsApplied,
            "FUZZ_WORKERS_APPLIED" to fuzzWorkersApplied,
        ),
    )
    project.findProperty("fuzzRuns")?.toString()?.takeIf { it.isNotBlank() }?.let { runs ->
        environment("FUZZ_RUNS", runs)
    }
}
