package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ProductEventModeFfi
import dev.ipf.marmotkit.ProductEventSchemaFfi
import dev.ipf.marmotkit.ProductPropertyKindFfi
import dev.ipf.marmotkit.ProductPropertySchemaFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * Golden fingerprint of the host event registry Android hands MDK (#2820).
 *
 * MDK's `ProductAnalyticsRuntimeConfig::registry_revision` (marmot-app `product_analytics/mod.rs` at the
 * pinned MarmotKit commit) sorts this registry by event name, sorts each event's properties by name and
 * each enum's choices, serializes it with `serde_json`, and hashes it together with MDK's own catalogue
 * constants. A granted receipt whose stored registry revision differs is reported as
 * `ACCEPTANCE_REQUIRED`, so any change to the Android contribution re-prompts every user who granted.
 *
 * Android cannot reproduce MDK's catalogue constants, so this test digests the Android contribution in
 * exactly the serde shape MDK hashes. A changed digest therefore always means a changed MDK registry
 * revision. The reverse does not hold — a MarmotKit bump can change the revision on its own.
 */
class ProductRegistryFingerprintTest {
    /** Fails on any registry change, naming the consent consequence so it is never updated in passing. */
    @Test
    fun androidRegistryFingerprintIsUnchanged() {
        val actual = registryFingerprint(androidProductRegistry)
        assertEquals(
            "The Android product registry changed (fingerprint $actual). MDK hashes this registry into the " +
                "usage-diagnostics registry revision, so shipping this change re-prompts EVERY user who " +
                "granted diagnostics on the previous release. Only update ANDROID_REGISTRY_FINGERPRINT when " +
                "that renewal is intended and the release notes/consent copy explain what changed (#2820).",
            ANDROID_REGISTRY_FINGERPRINT,
            actual,
        )
    }

    /** Declaration order is not part of the revision, matching MDK's sorting before it hashes. */
    @Test
    fun fingerprintIgnoresDeclarationOrderLikeMdk() {
        val reordered =
            androidProductRegistry.reversed().map { event ->
                event.copy(
                    properties =
                        event.properties.reversed().map { property ->
                            property.copy(choices = property.choices.reversed())
                        },
                )
            }
        assertEquals(registryFingerprint(androidProductRegistry), registryFingerprint(reordered))
    }

    /** Adding a host schema, the cause traced for the post-9.21 re-prompt, changes the fingerprint. */
    @Test
    fun addedHostSchemaChangesTheFingerprint() {
        val expanded =
            androidProductRegistry +
                ProductEventSchemaFfi(
                    name = "app_example_added",
                    mode = ProductEventModeFfi.AGGREGATE,
                    properties = listOf(ProductPropertySchemaFfi("outcome", ProductPropertyKindFfi.BOOLEAN, emptyList())),
                )
        assertNotEquals(registryFingerprint(androidProductRegistry), registryFingerprint(expanded))
    }

    /** The canonical encoding matches serde's externally tagged enums and compact output. */
    @Test
    fun canonicalJsonMatchesSerdeShape() {
        val schema =
            ProductEventSchemaFfi(
                name = "app_b",
                mode = ProductEventModeFfi.JOURNEY,
                properties =
                    listOf(
                        ProductPropertySchemaFfi("z", ProductPropertyKindFfi.ENUM, listOf("y", "x")),
                        ProductPropertySchemaFfi("a", ProductPropertyKindFfi.COUNT_BUCKET, emptyList()),
                    ),
            )
        assertEquals(
            """[{"name":"app_b","mode":"Journey","properties":[{"name":"a","rule":"CountBucket"},""" +
                """{"name":"z","rule":{"Enum":["x","y"]}}]}]""",
            canonicalRegistryJson(listOf(schema)),
        )
    }

    private companion object {
        /** SHA-256 of [canonicalRegistryJson] for the shipped registry; see the class KDoc before changing. */
        const val ANDROID_REGISTRY_FINGERPRINT = "9eacb377b10963a6e1319f12edbf9dfd41a209f1dd588825801900180f36cbdd"
    }
}

/** SHA-256 hex digest of the registry in MDK's canonical serde shape. */
internal fun registryFingerprint(registry: List<ProductEventSchemaFfi>): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(canonicalRegistryJson(registry).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Mirrors MDK's sort-then-`serde_json` encoding of `Vec<ProductEventSchema>`: unit variants as strings,
 * `Enum` as an externally tagged object, compact separators. Registry labels are validated lowercase
 * ASCII, so no escaping is needed and Kotlin's string order equals Rust's byte order.
 */
internal fun canonicalRegistryJson(registry: List<ProductEventSchemaFfi>): String =
    registry.sortedBy { it.name }.joinToString(",", "[", "]") { event ->
        val properties =
            event.properties.sortedBy { it.name }.joinToString(",", "[", "]") { property ->
                """{"name":"${property.name}","rule":${propertyRule(property)}}"""
            }
        """{"name":"${event.name}","mode":"${eventMode(event.mode)}","properties":$properties}"""
    }

/** The serde name of MDK's `ProductEventMode` variant. */
private fun eventMode(mode: ProductEventModeFfi): String =
    when (mode) {
        ProductEventModeFfi.JOURNEY -> "Journey"
        ProductEventModeFfi.AGGREGATE -> "Aggregate"
    }

/** The serde encoding of MDK's `ProductPropertyRule` for one host property. */
private fun propertyRule(property: ProductPropertySchemaFfi): String =
    when (property.kind) {
        ProductPropertyKindFfi.ENUM -> property.choices.sorted().joinToString(",", """{"Enum":[""", "]}") { "\"$it\"" }
        ProductPropertyKindFfi.BOOLEAN -> "\"Boolean\""
        ProductPropertyKindFfi.COUNT_BUCKET -> "\"CountBucket\""
        ProductPropertyKindFfi.DURATION_BUCKET -> "\"DurationBucket\""
    }
