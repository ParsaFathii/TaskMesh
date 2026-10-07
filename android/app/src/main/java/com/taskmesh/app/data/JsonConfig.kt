package com.taskmesh.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Single configured [Json] instance shared by the Retrofit converter and any
 * ad-hoc (de)serialization the app performs.
 *
 *  - `ignoreUnknownKeys`: the backend may add fields; older clients keep working.
 *  - `encodeDefaults`: request bodies always carry explicit values.
 *  - `coerceInputValues`: a `null` for a property with a default decodes to the
 *    default instead of throwing (defensive against sloppy server values).
 */
object JsonConfig {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /** Parses arbitrary user-typed JSON (job payload editor); null when invalid. */
    fun parseElement(text: String): JsonElement? {
        if (text.isBlank()) {
            return null
        }
        return try {
            json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Pretty rendering for payload/result inspection. */
    fun renderPretty(element: JsonElement?): String {
        if (element == null || element is JsonNull) {
            return "null"
        }
        return element.toString()
    }
}
