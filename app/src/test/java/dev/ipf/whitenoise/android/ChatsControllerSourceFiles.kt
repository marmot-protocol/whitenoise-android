package dev.ipf.whitenoise.android

import java.io.File

/**
 * Lists `ChatsController.kt` followed by its `ChatsController<Topic>.kt` extension files,
 * so source gates keep seeing the whole controller after implementation clusters moved out.
 */
internal fun chatsControllerFiles(): List<File> {
    val state = appModuleFile("src/main/java/dev/ipf/whitenoise/android/state")
    val topics =
        state
            .listFiles { file -> file.name.matches(Regex("""ChatsController\w+\.kt""")) }
            .orEmpty()
            .sortedBy(File::getName)
    return listOf(File(state, "ChatsController.kt")) + topics
}

/** Reads the whole chat-list controller: the class file and its topic extension files. */
internal fun chatsControllerSource(): String = chatsControllerFiles().joinToString("\n") { it.readText() }
