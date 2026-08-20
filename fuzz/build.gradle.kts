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
        "dev/ipf/whitenoise/android/core/nostr/NostrEvent.kt",
        "dev/ipf/whitenoise/android/core/nostr/BIP340.kt",
        "dev/ipf/whitenoise/android/core/nostr/NostrRelayFrames.kt",
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
    useJUnitPlatform {
        excludeTags("fuzz-triage-selfcheck")
    }
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

val fuzzReplayCorpusDir = layout.buildDirectory.dir("fuzz-replay-corpus")
val appFuzzSyntheticCorpusDir = layout.buildDirectory.dir("app-fuzz-synthetic-corpus")
val appFuzzSyntheticCorpusRoot = appFuzzSyntheticCorpusDir.map { it.dir("fuzz-synthetic-corpus") }
val fuzzReplayOverlayProbe =
    "dev/ipf/whitenoise/android/fuzz/ZapstoreProtocolFuzzTestInputs/" +
        "fuzzZapstoreProtocol/overlay_replay_probe.input"

val syncFuzzReplayCorpus =
    tasks.register<Sync>("syncFuzzReplayCorpus") {
        from(layout.projectDirectory.dir("src/test/resources")) {
            exclude("dev/ipf/whitenoise/android/fuzz/*FuzzTestInputs/**")
        }
        fuzzCampaignTargets.forEach { target ->
            val classSimpleName = target.testClass.substringAfterLast('.')
            val destination =
                "dev/ipf/whitenoise/android/fuzz/" +
                    "${classSimpleName}Inputs/${target.methodName}"
            from(layout.projectDirectory.dir(target.seedCorpusPath)) {
                into(destination)
            }
            from(layout.projectDirectory.dir("regression-corpus/${target.taskName}")) {
                into(destination)
            }
        }
        val replayInputsDir = project.findProperty("fuzzReplayInputsDir")?.toString()
        if (replayInputsDir != null) {
            from(rootProject.file(replayInputsDir))
        }
        into(fuzzReplayCorpusDir)
    }

val syncAppFuzzSyntheticCorpus =
    tasks.register<Sync>("syncAppFuzzSyntheticCorpus") {
        fuzzCampaignTargets.forEach { target ->
            from(layout.projectDirectory.dir(target.seedCorpusPath)) {
                into(target.taskName)
            }
            from(layout.projectDirectory.dir("regression-corpus/${target.taskName}")) {
                into(target.taskName)
            }
        }
        into(appFuzzSyntheticCorpusRoot)
    }

tasks.named<Test>("test") {
    dependsOn(syncFuzzReplayCorpus)
}

tasks.register<Test>("replayFuzzRegression") {
    group = "verification"
    description = "Replay checked-in fuzz regression corpora without unbounded fuzzing"
    dependsOn(tasks.testClasses, syncFuzzReplayCorpus)
    val replayCorpusOutput = syncFuzzReplayCorpus.get().destinationDir
    val testResourcesOutput =
        sourceSets.test
            .get()
            .output.resourcesDir
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath =
        sourceSets.test
            .get()
            .runtimeClasspath
            .filter { file -> file != testResourcesOutput }
            .plus(files(replayCorpusOutput))
    useJUnitPlatform {
        includeTags("jazzer")
        if (project.findProperty("fuzzReplayInputsDir") == null) {
            excludeTags("fuzz-triage-selfcheck")
        }
    }
    timeout.set(Duration.ofMinutes(5))
    doLast {
        val xmlDir =
            reports.junitXml.outputLocation
                .get()
                .asFile
        val zapstoreReports =
            xmlDir
                .walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.name == "TEST-dev.ipf.whitenoise.android.fuzz.ZapstoreProtocolFuzzTest.xml"
                }.toList()
        if (zapstoreReports.isEmpty()) {
            error(
                "replayFuzzRegression did not produce " +
                    "TEST-dev.ipf.whitenoise.android.fuzz.ZapstoreProtocolFuzzTest.xml",
            )
        }
        val overlayProbeListed =
            zapstoreReports.any { xml ->
                xml.readText().contains("overlay_replay_probe.input")
            }
        if (!overlayProbeListed) {
            error(
                "replayFuzzRegression did not execute regression overlay probe " +
                    fuzzReplayOverlayProbe,
            )
        }
    }
}

tasks.register("replayAllFuzzRegression") {
    group = "verification"
    description = "Replay :fuzz Jazzer corpora and app-side synthetic corpus suites"
    dependsOn(
        tasks.named("replayFuzzRegression"),
        ":app:replayAppFuzzSyntheticCorpus",
    )
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
    val seedCorpusDir = layout.projectDirectory.dir(target.seedCorpusPath).asFile
    workingDir = corpusDir.get().asFile
    val maxHeap = project.findProperty("fuzzMaxHeap")?.toString() ?: "2g"
    jvmArgs = listOf("-Xmx$maxHeap")
    doFirst {
        val corpusRoot = corpusDir.get().asFile
        corpusRoot.mkdirs()
        val evolvingCorpusDir = corpusRoot.resolve("generated").apply { mkdirs() }
        val totalRuns = project.findProperty("fuzzRuns")?.toString()?.takeIf { it.isNotBlank() }
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
        if (totalRuns != null) {
            jazzerArgs += "-runs=$totalRuns"
        } else {
            val maxDuration = project.findProperty("fuzzMaxDuration")?.toString() ?: "3m"
            jazzerArgs += "-max_total_time=${durationToSeconds(maxDuration)}"
        }
        args = jazzerArgs
    }
}

val fuzzTriageSelfCheckTarget =
    FuzzCampaignTarget(
        taskName = "fuzzTriageSelfCheck",
        testClass = "dev.ipf.whitenoise.android.fuzz.FuzzTriageSelfCheck",
        methodName = "fuzzTriageSelfCheck",
        seedCorpusPath =
            "src/test/resources/dev/ipf/whitenoise/android/fuzz/" +
                "FuzzTriageSelfCheckInputs/fuzzTriageSelfCheck",
    )

(listOf(fuzzTriageSelfCheckTarget) + fuzzCampaignTargets).forEach { target ->
    tasks.register<JavaExec>(target.taskName) {
        configureFuzzCampaign(target)
    }
}

tasks.register<JavaExec>("fuzzMinimizeCrash") {
    group = "fuzzing"
    description = "Minimize a standalone Jazzer crash artifact for triage"
    dependsOn(tasks.testClasses)
    mainClass.set("com.code_intelligence.jazzer.Jazzer")
    classpath = sourceSets.test.get().runtimeClasspath
    val maxHeap = project.findProperty("fuzzMaxHeap")?.toString() ?: "2g"
    jvmArgs = listOf("-Xmx$maxHeap")
    doFirst {
        val minimizeTask =
            project.findProperty("fuzzMinimizeTask")?.toString()
                ?: error("Set -PfuzzMinimizeTask to a :fuzz JavaExec task name (for example fuzzIdentityReference)")
        val minimizeInput =
            project.findProperty("fuzzMinimizeInput")?.toString()
                ?: error("Set -PfuzzMinimizeInput to the crash artifact path")
        val minimizeOutputDir =
            project.findProperty("fuzzMinimizeOutputDir")?.toString()
                ?: error("Set -PfuzzMinimizeOutputDir to the minimization output directory")
        val target =
            (listOf(fuzzTriageSelfCheckTarget) + fuzzCampaignTargets)
                .firstOrNull { it.taskName == minimizeTask }
                ?: error("Unknown fuzz minimize task: $minimizeTask")
        val minimizeInputFile =
            rootProject
                .file(minimizeInput)
                .absoluteFile
        val outputDir =
            rootProject
                .file(minimizeOutputDir)
                .absoluteFile
                .apply { mkdirs() }
        workingDir = outputDir
        args =
            listOf(
                "--target_class=${target.testClass}",
                "--target_method=${target.methodName}",
                "-max_len=$fuzzMaxLen",
                "-max_total_time=30",
                "-minimize_crash=1",
                "-exact_artifact_path=${outputDir.resolve("minimized-crash")}",
                minimizeInputFile.absolutePath,
            )
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
