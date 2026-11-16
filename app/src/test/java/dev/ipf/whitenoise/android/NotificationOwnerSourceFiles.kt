@file:Suppress("MaxLineLength")

package dev.ipf.whitenoise.android

import java.io.File

/** Lists `<owner>.kt` followed by its `<owner><Topic>.kt` extension files in one main package. */
private fun splitOwnerFiles(
    packagePath: String,
    owner: String,
): List<File> {
    val directory = appModuleFile("src/main/java/dev/ipf/whitenoise/android/$packagePath")
    val topics =
        directory
            .listFiles { file -> file.name.matches(Regex("""$owner\w+\.kt""")) }
            .orEmpty()
            .sortedBy(File::getName)
    return listOf(File(directory, "$owner.kt")) + topics
}

/**
 * Lists `NotificationDeliveryController.kt` followed by its topic extension files,
 * so source gates keep seeing the whole controller after implementation clusters moved out.
 */
internal fun notificationDeliveryControllerFiles(): List<File> = splitOwnerFiles("state", "NotificationDeliveryController")

/** Reads the whole notification delivery controller: the class file and its topic extension files. */
internal fun notificationDeliveryControllerSource(): String = notificationDeliveryControllerFiles().joinToString("\n") { it.readText() }

/** Lists `LocalNotificationPresenter.kt` followed by its topic extension files. */
internal fun localNotificationPresenterFiles(): List<File> = splitOwnerFiles("notifications", "LocalNotificationPresenter")

/** Reads the whole local notification presenter: the class file and its topic extension files. */
internal fun localNotificationPresenterSource(): String = localNotificationPresenterFiles().joinToString("\n") { it.readText() }
