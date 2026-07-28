package dev.ipf.whitenoise.android.ui.profile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileBannerLocalizationTest {
    @Test
    fun bannerPickerStringsExistInEverySupportedLocale() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .firstOrNull(File::isDirectory) ?: error("Missing Android resources")
        val names =
            listOf(
                "profile_banner_edit",
                "profile_banner_sheet_title",
                "profile_banner_placeholder",
                "profile_banner_uploading",
                "profile_banner_choose_photo",
                "profile_banner_url_label",
                "profile_banner_remove",
                "profile_banner_apply",
                "toast_couldnt_upload_profile_banner",
            )

        resDir
            .listFiles { file -> file.isDirectory && file.name.startsWith("values-") }
            .orEmpty()
            .map { File(it, "strings.xml") }
            .filter(File::isFile)
            .forEach { stringsFile ->
                val strings = stringsFile.readText()
                names.forEach { name ->
                    assertTrue(
                        "${stringsFile.parentFile?.name} is missing $name",
                        Regex("""<string\s+name=["']${Regex.escape(name)}["']""").containsMatchIn(strings),
                    )
                }
            }
    }
}
