package dev.ipf.whitenoise.android.state

/** Transient presentation of completed native steps; never an alternate membership store. */
enum class ChatDepartureStage { CHECKING, ADMIN_GRANTED, ADMIN_DEMOTED, LEFT, CLEANUP_PENDING, COMPLETED }
