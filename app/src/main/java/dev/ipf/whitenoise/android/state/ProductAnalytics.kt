package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ProductEventFfi
import dev.ipf.marmotkit.ProductEventModeFfi
import dev.ipf.marmotkit.ProductEventPropertyFfi
import dev.ipf.marmotkit.ProductEventSchemaFfi
import dev.ipf.marmotkit.ProductPropertyKindFfi
import dev.ipf.marmotkit.ProductPropertySchemaFfi

/** Closed host vocabulary: callers cannot attach identifiers, content, or arbitrary properties. */
internal enum class ProductObservation(
    private val event: String,
    private val property: String,
    private val value: String,
) {
    ONBOARDING("app_screen_viewed", "screen", "onboarding"),
    INBOX("app_screen_viewed", "screen", "inbox"),
    CONVERSATION("app_screen_viewed", "screen", "conversation"),
    SETTINGS("app_screen_viewed", "screen", "settings"),
    PRIVACY("app_settings", "section", "privacy"),
    COMPOSE("app_compose", "action", "open"),
    NOTIFICATION_GRANTED("app_notification_permission", "outcome", "granted"),
    NOTIFICATION_DENIED("app_notification_permission", "outcome", "denied"),
    NOTIFICATION_ENTRY("app_android_entry", "source", "notification"),
    PROFILE_ENTRY("app_android_entry", "source", "profile"),
    SHARE_ENTRY("app_android_entry", "source", "share"),
    ;

    /** Builds only MDK-approved properties from the finite catalogue. */
    fun event(): ProductEventFfi = ProductEventFfi(event, listOf(ProductEventPropertyFfi(property, value)))
}

/**
 * Extends Danny's timing scope with Android entry attribution. The new registry fingerprint also
 * requires a fresh MDK receipt from users who granted through the former relay-only disclosure.
 */
internal val androidProductRegistry: List<ProductEventSchemaFfi> =
    MarmotTraceSection.hostTimingRegistry +
        ProductEventSchemaFfi(
            name = "app_android_entry",
            mode = ProductEventModeFfi.AGGREGATE,
            properties =
                listOf(
                    ProductPropertySchemaFfi(
                        "source",
                        ProductPropertyKindFfi.ENUM,
                        listOf("notification", "profile", "share"),
                    ),
                ),
        )

/** Memory-only admission gate; MDK owns consent receipts, sessions, aggregation and delivery. */
internal class ProductObservationGate {
    private var generation = 0L
    private var enabled = false

    /** A ticket represents permission at the start of an observation, never retroactive permission. */
    @Synchronized
    fun ticket(): Long? = generation.takeIf { enabled }

    /** Changes admission and rejects all work begun under the preceding consent/runtime/account scope. */
    @Synchronized
    fun reset(allow: Boolean = false) {
        generation += 1
        enabled = allow
    }

    /** Retires old account/lifecycle observations while retaining current consent for future work. */
    @Synchronized
    fun invalidate() {
        generation += 1
    }

    /** Runs only the memory-only native recorder under the admission lock, never an exporter flush. */
    @Synchronized
    fun record(
        ticket: Long?,
        block: () -> Unit,
    ) {
        if (ticket != null && enabled && ticket == generation) block()
    }
}
