// Transit hint formats from magic-wormhole's transit.py (MIT).
package uno.lux.wormhole.transit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A TCP endpoint. */
internal data class DirectHint(
    val hostname: String,
    val port: Int,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("type", "direct-tcp-v1")
            put("priority", 0.0)
            put("hostname", hostname)
            put("port", port)
        }

    companion object {
        /** Parses `tcp:host:port`, the format of `--transit-helper`. */
        fun parseRelayUrl(url: String): DirectHint {
            val parts = url.split(":")
            require(parts.size == 3 && parts[0] == "tcp") { "Transit relay must look like tcp:host:port, got \"$url\"" }
            val port = requireNotNull(parts[2].toIntOrNull()) { "Invalid transit relay port in \"$url\"" }
            return DirectHint(parts[1], port)
        }

        fun fromJson(o: JsonObject): DirectHint? {
            if (o.string("type") != "direct-tcp-v1") return null
            val host = o.string("hostname") ?: return null
            val port = (o["port"] as? JsonPrimitive)?.intOrNull ?: return null
            return DirectHint(host, port)
        }
    }
}

/** Where the other side can be reached. */
internal data class TransitHints(
    val direct: List<DirectHint>,
    val relays: List<DirectHint>,
) {
    companion object {
        /** Parses the value of a `{"transit": ...}` message. Unknown hint types are ignored. */
        fun parse(transit: JsonObject): TransitHints {
            val direct = mutableListOf<DirectHint>()
            val relays = mutableListOf<DirectHint>()
            for (hint in (transit["hints-v1"] as? JsonArray).orEmpty()) {
                val o = hint as? JsonObject ?: continue
                when (o.string("type")) {
                    "direct-tcp-v1" -> {
                        DirectHint.fromJson(o)?.let(direct::add)
                    }

                    "relay-v1" -> {
                        (o["hints"] as? JsonArray)
                            .orEmpty()
                            .mapNotNull { (it as? JsonObject)?.let(DirectHint::fromJson) }
                            .let(relays::addAll)
                    }
                }
            }
            return TransitHints(direct, relays)
        }

        fun toTransitMessage(
            direct: List<DirectHint>,
            relay: DirectHint?,
        ): JsonObject =
            buildJsonObject {
                put(
                    "abilities-v1",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "direct-tcp-v1") })
                        add(buildJsonObject { put("type", "relay-v1") })
                    },
                )
                put(
                    "hints-v1",
                    buildJsonArray {
                        direct.forEach { add(it.toJson()) }
                        if (relay != null) {
                            add(
                                buildJsonObject {
                                    put("type", "relay-v1")
                                    put("hints", buildJsonArray { add(relay.toJson()) })
                                },
                            )
                        }
                    },
                )
            }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
