package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.appModuleFile
import java.io.File

/**
 * Lists `ConversationDictationController.kt` followed by its `ConversationDictationController<Topic>.kt`
 * extension files and merge declarations, so source gates keep seeing the whole controller.
 */
internal fun conversationDictationControllerFiles(): List<File> {
    val audio = appModuleFile("src/main/java/dev/ipf/whitenoise/android/audio")
    val topics =
        audio
            .listFiles { file -> file.name.matches(Regex("""ConversationDictation(?:Controller\w+|Merge)\.kt""")) }
            .orEmpty()
            .sortedBy(File::getName)
    return listOf(File(audio, "ConversationDictationController.kt")) + topics
}

/** Reads the whole dictation controller: the class file and its topic extension files. */
internal fun conversationDictationControllerSource(): String {
    val files = conversationDictationControllerFiles()
    return files.joinToString("\n") { it.readText() }
}

/** Reads the Android adapter and its platform topic declarations. */
internal fun androidConversationDictationPlatformSource(): String {
    val audio = appModuleFile("src/main/java/dev/ipf/whitenoise/android/audio")
    val topics =
        audio
            .listFiles { file ->
                file.name.matches(
                    Regex("""(?:AndroidConversationDictationPlatform\w+|ConversationDictationCallerAudioProbe)\.kt"""),
                )
            }.orEmpty()
            .sortedBy(File::getName)
    val files = listOf(File(audio, "AndroidConversationDictationPlatform.kt")) + topics
    return files.joinToString("\n") { it.readText() }
}
