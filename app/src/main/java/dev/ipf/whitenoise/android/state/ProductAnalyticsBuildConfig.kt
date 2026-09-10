@file:Suppress("MatchingDeclarationName")

package dev.ipf.whitenoise.android.state

import android.os.Build
import dev.ipf.marmotkit.ProductAnalyticsMetadataFfi
import dev.ipf.marmotkit.ProductAnalyticsRuntimeConfigFfi
import dev.ipf.whitenoise.android.BuildConfig

private const val PRODUCT_OS_MAJOR_MAX_LENGTH = 3

/** Resolves only this build environment's destination and supplies the expanded Android consent scope. */
internal fun androidProductAnalyticsRuntimeConfig(): ProductAnalyticsRuntimeConfigFfi =
    ProductAnalyticsRuntimeConfigFfi(
        eventsEndpoint = BuildConfig.WHITENOISE_PRODUCT_EVENTS_ENDPOINT.ifBlank { null },
        appKey = BuildConfig.WHITENOISE_PRODUCT_APP_KEY.ifBlank { null },
        metadata =
            ProductAnalyticsMetadataFfi(
                appVersion = BuildConfig.VERSION_NAME.substringBefore('-'),
                osFamily = "android",
                osMajorVersion =
                    Build.VERSION.RELEASE
                        .substringBefore('.')
                        .filter(Char::isDigit)
                        .take(PRODUCT_OS_MAJOR_MAX_LENGTH),
                deviceClass = "other",
                hostSurface = "native",
                environment =
                    when (BuildConfig.WHITENOISE_DEPLOYMENT_ENVIRONMENT) {
                        "production" -> "production"
                        "staging" -> "staging"
                        else -> "development"
                    },
                isDebug = BuildConfig.DEBUG,
            ),
        registry = androidProductRegistry,
        allowLoopback = false,
        operator = BuildConfig.WHITENOISE_PRODUCT_OPERATOR.ifBlank { "white_noise" },
    )
