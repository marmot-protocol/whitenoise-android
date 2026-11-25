---
name: white-noise-android-design
description: Design or implement a White Noise Android screen or interaction with Material 3, or assess a proposed UI change when a design review is requested.
---

# White Noise Android design

Read the relevant entries in the [Android UI patterns guide](../../../docs/android-ui-patterns.md).
It is the shared design reference for agents and contributors. Google Material 3 Expressive
governs the design. White Noise’s default palette is monochrome.

For a design or implementation request, identify the person’s task, find the matching
Material or Android API, and inspect the existing composable and the action it calls.
Use the guide’s screenshot to see the pattern in context. Trace what selection, commit,
dismissal, and Back do where they matter. Explain any gap that needs a custom treatment.
Check other callers when changing a shared component.

For a requested design review, read the affected code and inspect the relevant
baseline or capture. For each finding give the screen, state, user consequence,
and evidence. Separate an observed defect from an open design decision.
A review-only request produces findings. The [repository instructions](../../../AGENTS.md)
govern implementation and validation.
