import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage

// Temporary source backport for #2647. See third_party/compose-ui/README.md for removal.
val patched = Attribute.of("dev.ipf.whitenoise.compose-rectlist-patched", Boolean::class.javaObjectType)
val kotlinPlatform = Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java)

val originalCompose = configurations.create("composeRectListOriginal") {
    isCanBeConsumed = false
    attributes {
        attribute(patched, false)
        attribute(kotlinPlatform, "androidJvm")
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    }
}
val originalSources = configurations.create("composeRectListSources") {
    isCanBeConsumed = false
    isTransitive = false
}
val patchCompiler = configurations.create("composeRectListCompiler") {
    isCanBeConsumed = false
}
val composeCompilerPlugin = configurations.create("composeRectListComposeCompiler") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add(originalCompose.name, "androidx.compose.ui:ui-android:1.12.1")
    add(originalSources.name, "androidx.compose.ui:ui-android:1.12.1:sources@jar")
    add(patchCompiler.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
    add(composeCompilerPlugin.name, "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:2.4.20")
}

val sdkDirectory = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(provider {
        val properties = java.util.Properties()
        val local = rootProject.file("local.properties")
        if (local.isFile) local.inputStream().use(properties::load)
        properties.getProperty("sdk.dir") ?: error("Set ANDROID_HOME to build the Compose source backport")
    })
val platformJar = files(sdkDirectory.map { "$it/platforms/android-37/android.jar" })
val patchFile = layout.projectDirectory.file("third_party/compose-ui/fd550bed793.patch")

subprojects {
    dependencies {
        artifactTypes.maybeCreate("aar").attributes.attribute(patched, false)
        registerTransform(ComposeUiBackport::class) {
            from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar").attribute(patched, false)
            to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar").attribute(patched, true)
            parameters {
                compilerClasspath.from(patchCompiler)
                composeCompiler.from(composeCompilerPlugin)
                originalClasspath.from(originalCompose)
                sources.set(layout.file(provider { originalSources.singleFile }))
                patch.set(patchFile)
                androidJar.from(platformJar)
            }
        }
    }
    configurations.configureEach {
        // Apply to compile, runtime, unit tests and instrumented tests in every distribution.
        attributes.attribute(patched, true)
    }
}
