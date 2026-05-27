# Dark Matter Android Agent Notes

## Source Of Truth

The Android app must not create its own persistent data cache for Dark Matter data.

Dark Matter's SQLite databases are the source of truth for accounts, groups, messages, profiles, relay state, key packages, notification settings, and other protocol data. Performance work should make reads from that source fast and well-shaped. Do not add a second Android-owned cache to hide slow database or binding access.

Allowed Android state:

- UI state, such as selected screens, dialog state, draft text, loading flags, and optimistic send state.
- Short-lived lifecycle state, such as active stream jobs and in-flight requests.
- Android platform preferences, such as theme, language, notification permission UI state, and foreground service preferences.

Avoid:

- Room, DataStore, SharedPreferences, files, or in-memory singleton maps as a duplicate source for Dark Matter protocol data.
- Long-lived Android caches for message timelines, chat summaries, profiles, key packages, or relay projections.
- Fixing slow screens by copying Dark Matter data into Android-owned storage.

Prefer:

- Changing Dark Matter APIs so Android can read exactly the data it needs from SQLite quickly.
- Moving binding and database calls off the main thread.
- Keeping stream subscriptions lifecycle-bound and explicitly closed.
- Returning pre-shaped projections from Dark Matter when a screen needs them.

If Android seems to need a cache for protocol data, stop and ask whether the data should instead be indexed, projected, or exposed differently by Dark Matter.

## Working Rules

- Keep Android changes small and tied to a user-visible behavior.
- Use the existing Kotlin, Compose, and Marmot binding patterns before adding new architecture.
- Do not run connected device tests that can wipe local app state unless the user asks for them.
- Prefer `rg` for searching.
- Before editing files, check the dirty worktree and preserve changes you did not make.
