package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ChatHistoryTransfer
import dev.ipf.whitenoise.android.state.ImportedHistorySource
import dev.ipf.whitenoise.android.ui.conversation.ChatHistoryTransferNotice
import dev.ipf.whitenoise.android.ui.conversation.ImportedHistoryBoundary
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The line where imported history ends and the notice while earlier messages are still arriving. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h260dp-mdpi")
class ImportedHistoryScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /** Receiving, then the label for a named device, in the light theme. */
    @Test
    fun receivingAndDeviceLight() {
        val source = ImportedHistorySource.Device("Tablet")
        capture("imported_history_receiving_device_light", ChatHistoryTransfer.Receiving, source)
    }

    /** Retrying, then the label for an unnamed device, in the dark theme. */
    @Test
    fun retryingAndAnotherDeviceDark() {
        val name = "imported_history_retrying_another_device_dark"
        capture(name, ChatHistoryTransfer.Retrying, ImportedHistorySource.AnotherDevice, dark = true)
    }

    /** Retrying and the backup label wrap in RTL at double font scale on true-black surfaces. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h520dp-mdpi")
    fun retryingAndBackupAmoledRtlLargeFont() {
        val name = "imported_history_retrying_backup_amoled_rtl_large_font"
        capture(
            name = name,
            transfer = ChatHistoryTransfer.Retrying,
            source = ImportedHistorySource.Backup,
            dark = true,
            amoled = true,
            rtl = true,
        )
    }

    /** Records the notice for [transfer] above the label for [source] as [name]. */
    @Suppress("LongParameterList") // One capture names its content, theme and direction.
    private fun capture(
        name: String,
        transfer: ChatHistoryTransfer,
        source: ImportedHistorySource,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface {
                        Column {
                            ChatHistoryTransferNotice(transfer)
                            ImportedHistoryBoundary(source)
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
