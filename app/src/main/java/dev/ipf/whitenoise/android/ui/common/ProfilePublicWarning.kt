package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/**
 * Static notice shown at the top of the Edit Profile screen warning the user
 * that their kind:0 metadata is broadcast unencrypted to relays. Copy mirrors
 * Whitenoise Flutter (`profileIsPublic` / `profilePublicDescription`) for
 * cross-client parity. See #380. Informational (tonal secondary container),
 * not an error — it's expected behaviour the user should simply be aware of.
 */
@Composable
internal fun ProfilePublicWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Public,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.profile_is_public),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.profile_public_description),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
