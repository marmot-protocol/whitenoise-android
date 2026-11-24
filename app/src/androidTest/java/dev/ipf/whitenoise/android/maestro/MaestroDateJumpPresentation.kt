package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ui.medialibrary.MediaDateJumpAction
import dev.ipf.whitenoise.android.ui.medialibrary.MediaMonthSection
import dev.ipf.whitenoise.android.ui.medialibrary.SharedMediaTile
import dev.ipf.whitenoise.android.ui.medialibrary.mediaDayForMedia
import dev.ipf.whitenoise.android.ui.medialibrary.monthKeyForMedia

/** Opens the production calendar over three loaded days; Show reports which day was handed to the grid. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroDateJumpPresentation(fixture: MaestroPresentationFixture) {
    val tiles = FIXTURE_TIMES.mapIndexed { index, at -> fixtureTile("fixture-$index", at) }
    val sections =
        tiles
            .groupBy { monthKeyForMedia(it.recordedAt) }
            .map { (month, items) -> MediaMonthSection(month, items) }
    val newest = mediaDayForMedia(FIXTURE_TIMES.first())
    Row {
        MediaDateJumpAction(sections, gridState = null, ready = true) { day ->
            fixture.finish(if (day == newest) "date-selected" else "unexpected-date")
        }
    }
}

private fun fixtureTile(
    id: String,
    at: ULong,
) = SharedMediaTile(
    messageIdHex = id,
    attachmentIndex = 0,
    reference =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "11".repeat(32),
            plaintextSha256 = "22".repeat(32),
            nonceHex = "33".repeat(12),
            fileName = "$id.png",
            mediaType = "image/png",
            version = EncryptedMediaVersionFfi.V2,
            sourceEpoch = 1u,
            dim = null,
            thumbhash = null,
        ),
    mine = true,
    recordedAt = at,
    sender = "fixture",
    isVideo = false,
)

/** Mid-month noon UTC keeps each day stable in every emulator time zone. */
private val FIXTURE_TIMES = listOf(1_700_049_600UL, 1_694_779_200UL, 1_652_616_000UL)
