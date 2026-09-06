package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Adds followed and selection state to rows that expose either relationship. */
internal fun Modifier.recipientRelationshipSemantics(
    followedStateDescription: String?,
    selectionState: Boolean?,
): Modifier {
    if (followedStateDescription == null && selectionState == null) return this
    return semantics(mergeDescendants = true) {
        followedStateDescription?.let { stateDescription = it }
        selectionState?.let { selected = it }
    }
}

/** Draws a decorative person-and-check marker with light and dark boundary contrast. */
@Composable
@Suppress("FunctionNaming")
internal fun FollowedPersonBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(2.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
                .padding(1.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .testTag(FOLLOWED_PERSON_BADGE_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.HowToReg,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(13.dp),
        )
    }
}

internal const val FOLLOWED_PERSON_BADGE_TEST_TAG = "recipient-followed-person-badge"
