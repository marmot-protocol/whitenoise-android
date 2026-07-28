package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1600dp-mdpi")
class KeyPackagesContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun retainedLocalMaterialIsNotPresentedAsPublishedOrDeletable() {
        val published =
            keyPackage(
                keyPackageId = "stable-package-slot",
                keyPackageRefHex = "34".repeat(32),
                eventIdHex = "ab".repeat(32),
                relay = true,
            )
        val retained =
            keyPackage(
                keyPackageId = "stable-package-slot",
                keyPackageRefHex = "56".repeat(32),
                eventIdHex = "",
                relay = false,
            )
        var deleteTarget: AccountKeyPackageFfi? = null

        render(listOf(retained, published), onDelete = { deleteTarget = it })

        composeRule.onNodeWithText(IdentityFormatter.short(published.keyPackageId)).assertExists()
        composeRule.onNodeWithText(IdentityFormatter.short(published.keyPackageRefHex)).assertExists()
        composeRule.onNodeWithText(IdentityFormatter.short(retained.keyPackageRefHex)).assertDoesNotExist()
        composeRule
            .onAllNodesWithContentDescription(app.getString(R.string.delete_key_package))
            .assertCountEquals(1)
        composeRule.onNodeWithContentDescription(app.getString(R.string.delete_key_package)).performClick()
        composeRule.runOnIdle { assertSame(published, deleteTarget) }
    }

    @Test
    fun relayRecordWithoutAValidEventIdIsPresentedButNotDeletable() {
        val valid = keyPackage(keyPackageId = "valid-relay-package", eventIdHex = "cd".repeat(32), relay = true)
        val malformed = keyPackage(keyPackageId = "malformed-relay-package", eventIdHex = "", relay = true)
        var deleteTarget: AccountKeyPackageFfi? = null

        render(listOf(malformed, valid), onDelete = { deleteTarget = it })

        composeRule.onNodeWithText(IdentityFormatter.short(malformed.keyPackageId)).assertExists()
        composeRule
            .onAllNodesWithContentDescription(app.getString(R.string.delete_key_package))
            .assertCountEquals(1)
        composeRule.onNodeWithContentDescription(app.getString(R.string.delete_key_package)).performClick()
        composeRule.runOnIdle { assertSame(valid, deleteTarget) }
    }

    private fun render(
        packages: List<AccountKeyPackageFfi>,
        onDelete: (AccountKeyPackageFfi) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    KeyPackagesContent(
                        state =
                            keyPackagesState(
                                hasActiveAccount = true,
                                loaded = true,
                                loading = false,
                                working = false,
                                packageCount = packages.count { it.relay },
                            ),
                        packages = packages,
                        onBack = {},
                        onRefresh = {},
                        onRepublish = {},
                        onPublishNew = {},
                        onDelete = onDelete,
                    )
                }
            }
        }
    }

    private fun keyPackage(
        keyPackageId: String,
        keyPackageRefHex: String = "34".repeat(32),
        eventIdHex: String,
        relay: Boolean,
    ) = AccountKeyPackageFfi(
        accountRef = "account",
        accountIdHex = "12".repeat(32),
        keyPackageId = keyPackageId,
        keyPackageRefHex = keyPackageRefHex,
        eventIdHex = eventIdHex,
        publishedAt = 1_700_000_000uL,
        keyPackageBytes = 128uL,
        sourceRelays = if (relay) listOf("wss://relay.example") else emptyList(),
        local = true,
        relay = relay,
    )
}
