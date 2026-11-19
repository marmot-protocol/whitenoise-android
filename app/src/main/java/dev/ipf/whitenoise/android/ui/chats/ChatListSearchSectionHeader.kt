package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Section header above the search result groups. */
@Composable
@Suppress("FunctionNaming")
internal fun ChatListSearchSectionHeader(
    title: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { heading() }
                .testTag(testTag)
                .padding(16.dp),
        style = MaterialTheme.typography.titleSmall,
    )
}
