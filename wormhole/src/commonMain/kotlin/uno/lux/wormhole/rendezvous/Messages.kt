// Message formats of the Magic Wormhole mailbox (rendezvous) server protocol:
// https://magic-wormhole.readthedocs.io/en/latest/server-protocol.html
package uno.lux.wormhole.rendezvous

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Messages sent from a client to the mailbox server. */
internal sealed interface ClientMessage {
    val type: String

    data class Bind(val appId: String, val side: String, val clientVersion: List<String>) : ClientMessage {
        override val type = "bind"
    }

    data object Allocate : ClientMessage {
        override val type = "allocate"
    }

    data class Claim(val nameplate: String) : ClientMessage {
        override val type = "claim"
    }

    data class Release(val nameplate: String) : ClientMessage {
        override val type = "release"
    }

    data class Open(val mailbox: String) : ClientMessage {
        override val type = "open"
    }

    class Add(val phase: String, val body: ByteArray) : ClientMessage {
        override val type = "add"
        override fun toString() = "Add(phase=$phase, ${body.size} bytes)"
    }

    data class Close(val mailbox: String, val mood: String) : ClientMessage {
        override val type = "close"
    }

    data class Ping(val ping: Int) : ClientMessage {
        override val type = "ping"
    }

    fun toJson(id: String): String = buildJsonObject {
        put("type", type)
        when (val m = this@ClientMessage) {
            is Bind -> {
                put("appid", m.appId)
                put("side", m.side)
                put("client_version", JsonArray(m.clientVersion.map(::JsonPrimitive)))
            }
            Allocate -> Unit
            is Claim -> put("nameplate", m.nameplate)
            is Release -> put("nameplate", m.nameplate)
            is Open -> put("mailbox", m.mailbox)
            is Add -> {
                put("phase", m.phase)
                put("body", m.body.toHexString())
            }
            is Close -> {
                put("mailbox", m.mailbox)
                put("mood", m.mood)
            }
            is Ping -> put("ping", m.ping)
        }
        put("id", id)
    }.toString()

    companion object {
        /** Parses a client message. Used by test servers. Returns null for unknown types. */
        fun parse(text: String): ClientMessage? {
            val o = Json.parseToJsonElement(text).jsonObject
            return when (o.string("type")) {
                "bind" -> Bind(
                    o.string("appid").orEmpty(),
                    o.string("side").orEmpty(),
                    o["client_version"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                )
                "allocate" -> Allocate
                "claim" -> Claim(o.string("nameplate").orEmpty())
                "release" -> Release(o.string("nameplate").orEmpty())
                "open" -> Open(o.string("mailbox").orEmpty())
                "add" -> Add(o.string("phase").orEmpty(), o.string("body").orEmpty().hexToByteArray())
                "close" -> Close(o.string("mailbox").orEmpty(), o.string("mood").orEmpty())
                "ping" -> Ping(o["ping"]?.jsonPrimitive?.intOrNull ?: 0)
                else -> null
            }
        }
    }
}

/** Messages sent from the mailbox server to a client. */
internal sealed interface ServerMessage {
    data class Welcome(val motd: String?, val error: String?) : ServerMessage
    data object Ack : ServerMessage
    data class Allocated(val nameplate: String) : ServerMessage
    data class Claimed(val mailbox: String) : ServerMessage
    data object Released : ServerMessage
    data object Closed : ServerMessage
    data class Pong(val pong: Int?) : ServerMessage
    data class Error(val error: String) : ServerMessage
    data class Unknown(val type: String) : ServerMessage

    class Message(val side: String, val phase: String, val body: ByteArray) : ServerMessage {
        override fun toString() = "Message(side=$side, phase=$phase, ${body.size} bytes)"
    }

    companion object {
        fun parse(text: String): ServerMessage {
            val o = Json.parseToJsonElement(text).jsonObject
            return when (val type = o.string("type")) {
                "welcome" -> {
                    val w = o["welcome"] as? JsonObject
                    Welcome(motd = w?.string("motd"), error = w?.string("error"))
                }
                "ack" -> Ack
                "allocated" -> Allocated(o.string("nameplate").orEmpty())
                "claimed" -> Claimed(o.string("mailbox").orEmpty())
                "released" -> Released
                "closed" -> Closed
                "pong" -> Pong(o["pong"]?.jsonPrimitive?.intOrNull)
                "error" -> Error(o.string("error") ?: "unknown server error")
                "message" -> Message(
                    side = o.string("side").orEmpty(),
                    phase = o.string("phase").orEmpty(),
                    body = o.string("body").orEmpty().hexToByteArray(),
                )
                else -> Unknown(type.orEmpty())
            }
        }

        /** Serializes a server message. Used by test servers. */
        fun toJson(message: ServerMessage): String = buildJsonObject {
            when (message) {
                is Welcome -> {
                    put("type", "welcome")
                    put("welcome", buildJsonObject {
                        message.motd?.let { put("motd", it) }
                        message.error?.let { put("error", it) }
                    })
                }
                Ack -> put("type", "ack")
                is Allocated -> { put("type", "allocated"); put("nameplate", message.nameplate) }
                is Claimed -> { put("type", "claimed"); put("mailbox", message.mailbox) }
                Released -> put("type", "released")
                Closed -> put("type", "closed")
                is Pong -> { put("type", "pong"); message.pong?.let { put("pong", it) } }
                is Error -> { put("type", "error"); put("error", message.error) }
                is Unknown -> put("type", message.type)
                is Message -> {
                    put("type", "message")
                    put("side", message.side)
                    put("phase", message.phase)
                    put("body", message.body.toHexString())
                }
            }
        }.toString()
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
