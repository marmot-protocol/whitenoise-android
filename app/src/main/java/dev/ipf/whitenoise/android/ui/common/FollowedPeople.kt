package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.HowToReg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The people the active account follows, as canonical lowercase public-key hex. MDK owns the list; this is the
 * render-time snapshot of its network-free cached read. [None] stands for an unread or unavailable list, so a
 * missing answer never marks anyone as followed.
 */
@Immutable
internal class FollowedPeople(
    private val accountIdsHex: Set<String>,
) {
    /** Whether [accountIdHex] is followed; null, npub and non-canonical references are never matched. */
    fun contains(accountIdHex: String?): Boolean = accountIdHex != null && accountIdHex in accountIdsHex

    // Equal lists compare equal, so a re-read that finds nothing new does not recompose every avatar.
    override fun equals(other: Any?): Boolean = other is FollowedPeople && other.accountIdsHex == accountIdsHex

    override fun hashCode(): Int = accountIdsHex.hashCode()

    companion object {
        val None = FollowedPeople(emptySet())

        /** Keeps the account itself out, so its own avatar never carries the badge. */
        fun of(
            follows: Collection<String>,
            ownAccountIdHex: String?,
        ): FollowedPeople {
            val ids = follows.toHashSet()
            ownAccountIdHex?.let(ids::remove)
            return if (ids.isEmpty()) None else FollowedPeople(ids)
        }
    }
}

internal val LocalFollowedPeople = compositionLocalOf { FollowedPeople.None }

/** Supplies [LocalFollowedPeople] for the whole app from [rememberFollowedPeople]. */
@Composable
internal fun followedPeopleFor(appState: WhiteNoiseAppState): ProvidedValue<FollowedPeople> =
    LocalFollowedPeople provides rememberFollowedPeople(appState)

/**
 * Reads the active account's follow list from MDK's local cache whenever the account changes, the app returns to the
 * foreground, this app follows or unfollows someone, or profile data lands. A failed read keeps the same account's
 * last answer; another account starts from [FollowedPeople.None], so one account's follows never badge the next.
 */
@Composable
internal fun rememberFollowedPeople(appState: WhiteNoiseAppState): FollowedPeople {
    val accountRef = appState.activeAccountRef
    val ownAccountIdHex = appState.activeAccount?.accountIdHex
    var followed by remember(accountRef) { mutableStateOf(FollowedPeople.None) }
    var foregroundCount by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { foregroundCount++ }
    // The native runtime is readable only once the app is ready, and a restarted runtime is read afresh.
    val ready = appState.phase == AppPhase.Ready
    LaunchedEffect(accountRef, ownAccountIdHex, ready, appState.runtimeGeneration) {
        if (accountRef == null || !ready) return@LaunchedEffect
        var readRelationship: Long? = null
        var readForeground: Int? = null
        snapshotFlow {
            FollowedPeopleTrigger(
                appState.relationshipRevision,
                foregroundCount,
                appState.profileRevisionForCompose.profiles,
            )
        }.collectLatest { trigger ->
            // Profile batches land in bursts and are coalesced; a follow change or a return to the app reads at once,
            // and a burst that cancels such a read leaves it still owed.
            if (trigger.relationship == readRelationship && trigger.foreground == readForeground) {
                delay(FOLLOWED_PEOPLE_READ_DEBOUNCE_MILLIS)
            }
            val follows =
                try {
                    appState.marmotIo { accountFollows(accountRef) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@collectLatest
                }
            readRelationship = trigger.relationship
            readForeground = trigger.foreground
            followed = FollowedPeople.of(follows, ownAccountIdHex)
        }
    }
    return followed
}

/**
 * Lays [content], an avatar [avatarSize] across, under the followed-person badge when the active account follows
 * [accountIdHex]. The badge sits outside the avatar's circular clip, so callers that clip their avatar for a ripple
 * wrap that clipped node here rather than passing the account into it.
 */
@Composable
@Suppress("FunctionNaming")
internal fun FollowedPersonAvatarFrame(
    accountIdHex: String?,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val followed = LocalFollowedPeople.current.contains(accountIdHex)
    Box(modifier) {
        content()
        if (followed) {
            FollowedPersonBadge(Modifier.align(Alignment.BottomEnd), followedPersonBadgeSize(avatarSize))
        }
    }
}

/** Draws a decorative person-and-check marker with light and dark boundary contrast. */
@Composable
@Suppress("FunctionNaming")
internal fun FollowedPersonBadge(
    modifier: Modifier = Modifier,
    size: Dp = FOLLOWED_PERSON_BADGE_SIZE,
) {
    // At the 48 dp row size the rings are 2 dp and 1 dp; smaller avatars keep the same proportions.
    val outerRing = (size * OUTER_RING_RATIO).coerceAtLeast(1.dp)
    val innerRing = (size * INNER_RING_RATIO).coerceAtLeast(0.5.dp)
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(outerRing)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
                .padding(innerRing)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .testTag(FOLLOWED_PERSON_BADGE_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.HowToReg,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * ICON_RATIO),
        )
    }
}

/** Five twelfths of the avatar (20 dp on a 48 dp row), kept between a legible minimum and a modest maximum. */
internal fun followedPersonBadgeSize(avatarSize: Dp): Dp =
    (avatarSize * BADGE_TO_AVATAR_RATIO).coerceIn(MIN_BADGE_SIZE, MAX_BADGE_SIZE)

internal const val FOLLOWED_PERSON_BADGE_TEST_TAG = "recipient-followed-person-badge"
private val FOLLOWED_PERSON_BADGE_SIZE = 20.dp
private val MIN_BADGE_SIZE = 12.dp
private val MAX_BADGE_SIZE = 28.dp
private const val BADGE_TO_AVATAR_RATIO = 5f / 12f
private const val OUTER_RING_RATIO = 0.1f
private const val INNER_RING_RATIO = 0.05f
private const val ICON_RATIO = 0.65f
private const val FOLLOWED_PEOPLE_READ_DEBOUNCE_MILLIS = 250L

private data class FollowedPeopleTrigger(
    val relationship: Long,
    val foreground: Int,
    val profiles: Int,
)
