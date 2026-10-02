# Smart-folder UI prototype

This Android-first experiment is for testing the rule editor before MDK owns the
query. It uses existing chat-list projections and stores only private folder
preferences. Do not add native reads, hydration, outbox caches or protocol state
to the matcher. [MDK #1964](https://github.com/marmot-protocol/mdk/issues/1964)
tracks the account-wide implementation, including evaluation before pagination.

## Rule contract

- Match all / Match any applies to one group. Group rules contains nested groups
  and exclusion; existing exclusions remain visible. Single-child groups hide
  the irrelevant match selector without changing its stored mode.
- Choices such as Has unread messages / No unread messages require known data.
  Remove filter stops checking that property. Unknown
  remains unknown under NOT and cannot grant automatic membership.
- Participants use the current roster and resolved peer, never stale display
  identities. People can match any, all, or none of the selected public keys.
- Unread includes manual Mark unread. Unread mentions are independent.
- Empty rules are manual-only. Empty nested groups must be completed or removed.
  Unsupported or invalid saved rules disable automatic matching as a whole.
- Included Chats bypass automatic rules. Rules are bounded to four levels, 64
  nodes, 64 people per condition and 256 characters per title keyword.

## Prototype limits

Advanced folders combine the already-loaded active and archived windows. Counts
and previews cover those rows only. Paging and native visible-anchor reporting
are disabled in this view after both windows return to their newest page; ordinary folders retain their existing window
behavior. Full-account discovery and sorting before paging remain MDK work.

The current SDK has no full outbox summary: a pending latest message proves
presence, but a delivered latest message does not prove there are no older
pending sends. Pending-send absence therefore stays unresolved. Missing native
draft previews also stay unresolved; attachment-only drafts count as present.

All read does not mean an agent has completed its turn. The presets intentionally
make no completion claim; add Participants to restrict one to a chosen agent.

Custom unread and mention conditions read native projected counters and manual
flags. The presets separately require an active, accepted conversation; keep that
condition to exclude terminal chats whose counters may retain earlier activity.

## Existing folders

New folders start in simple mode, retaining ordinary pagination for manual
folders created from chat selections or group details. Choosing All read, Unread
mentions or saving a first Add filter is an explicit opt-in to the loaded-window prototype. Opening or renaming an existing
folder preserves its legacy rules. Those rules
remain editable in a collapsed section. A preset or a completed first filter starts a
new draft; dismissing the picker or condition dialog changes nothing. Replacing an advanced tree or non-default simple rules requires confirmation;
cancelling keeps the draft unchanged. Public-key entry and negation are optional controls within
each condition. The previous legacy fields
remain in the stored rule for a future explicit rollback. Unsupported payloads
remain unchanged when only the name or manual inclusions are edited. Save publishes folder
metadata, manual inclusions and the rule payload together using the existing
account-scoped preference transaction. Cancel persists none of the draft. The Included Chats picker always lists both
loaded active and archived chats, independent of automatic rules.

## Compact editor

One Add filter sheet offers People, Read status and Unread mentions first; More
filters exposes the other fields. Selecting a field opens only its values.
Existing filters can be edited or removed; new filters offer Save and Cancel.
Presets are shown directly on empty/manual folders and move into the menu when
filters exist. Group rules keeps nesting and exclusion available without
repeating the Boolean editor on every row. Optional description starts collapsed
when empty. Manual exceptions follow the rules and preview, with their bypass
explanation in the picker. The preview always labels the loaded-window scope
and reports incomplete data beside its count.
