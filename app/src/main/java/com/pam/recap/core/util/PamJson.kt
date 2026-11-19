package com.pam.recap.core.util

import kotlinx.serialization.json.Json

/**
 * One JSON configuration for stored sections and the optional analysis backend:
 * tolerant on input (provider output may add fields), explicit on output, and null-free so a
 * stored payload never grows an `"x": null` key.
 */
val PamJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
