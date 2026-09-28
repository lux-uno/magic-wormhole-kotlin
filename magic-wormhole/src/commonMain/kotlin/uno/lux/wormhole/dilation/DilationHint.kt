// Dilation's connection-hint wire format, following magic-wormhole's _dilation/connector.py and
// _hints.py (MIT). This is a different JSON shape from transit/Hints.kt, not a reuse of it.
package uno.lux.wormhole.dilation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface DilationHint {
    data class Direct(
        val hostname: String,
        val port: Int,
        val priority: Double = 0.0,
    ) : DilationHint

    data class Relay(
        val hints: List<Direct>,
    ) : DilationHint
}

internal fun DilationHint.toJson(): JsonObject =
    when (this) {
        is DilationHint.Direct -> {
            buildJsonObject {
                put("type", "direct-tcp-v1")
                put("priority", priority)
                put("hostname", hostname)
                put("port", port)
            }
        }

        is DilationHint.Relay -> {
            buildJsonObject {
                put("type", "relay-v1")
                put("hints", buildJsonArray { hints.forEach { add(it.toJson()) } })
            }
        }
    }

internal fun parseDilationHint(json: JsonObject): DilationHint? =
    when ((json["type"] as? JsonPrimitive)?.content) {
        "direct-tcp-v1" -> {
            parseDirectHint(json)
        }

        "relay-v1" -> {
            (json["hints"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(::parseDirectHint) }
                ?.let { DilationHint.Relay(it) }
        }

        else -> {
            null
        }
    }

private fun parseDirectHint(json: JsonObject): DilationHint.Direct? {
    val hostname = (json["hostname"] as? JsonPrimitive)?.content ?: return null
    val port = (json["port"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
    val priority = (json["priority"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
    return DilationHint.Direct(hostname, port, priority)
}

/** `{"type": "connection-hints", "hints": [...]}`. */
internal fun connectionHintsMessage(hints: List<DilationHint>): JsonObject =
    buildJsonObject {
        put("type", "connection-hints")
        put("hints", buildJsonArray { hints.forEach { add(it.toJson()) } })
    }

internal fun parseConnectionHintsMessage(message: JsonObject): List<DilationHint> =
    (message["hints"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseDilationHint) } ?: emptyList()
