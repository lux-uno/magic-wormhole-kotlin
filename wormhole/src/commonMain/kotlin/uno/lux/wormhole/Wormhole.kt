package uno.lux.wormhole

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import uno.lux.wormhole.code.Codes
import uno.lux.wormhole.rendezvous.RendezvousClient
import uno.lux.wormhole.rendezvous.RendezvousTransport
import uno.lux.wormhole.rendezvous.WebSocketTransport

/** Default Magic Wormhole rendezvous (mailbox) server. */
public const val DEFAULT_RENDEZVOUS_URL: String = "ws://relay.magic-wormhole.io:4000/v1"

/** Default Magic Wormhole transit relay. */
public const val DEFAULT_TRANSIT_RELAY: String = "tcp:transit.magic-wormhole.io:4001"

/** Application ID used by the `wormhole` CLI and wormhole-william for text and file transfers. */
public const val DEFAULT_APP_ID: String = "lothar.com/wormhole/text-or-file-xfer"

/** Settings for a [Wormhole]. The defaults are compatible with the `wormhole` CLI. */
public data class WormholeConfig(
    val rendezvousUrl: String = DEFAULT_RENDEZVOUS_URL,
    val transitRelay: String = DEFAULT_TRANSIT_RELAY,
    val appId: String = DEFAULT_APP_ID,
    /** Number of words after the nameplate in generated codes. */
    val codeLength: Int = 2,
) {
    init {
        require(codeLength >= 1) { "codeLength must be at least 1" }
    }
}

/** Progress of a send. */
public sealed interface SendEvent {
    /** Give this code to the receiver. */
    public data class CodeAllocated(val code: String) : SendEvent

    public data class Progress(val sentBytes: Long, val totalBytes: Long) : SendEvent

    /** The receiver got everything. This is the last event. */
    public data object Completed : SendEvent
}

/** Progress of a receive. */
public sealed interface ReceiveEvent {
    /** A text message arrived. This is the last event. */
    public data class TextReceived(val text: String) : ReceiveEvent
}

/**
 * Entry point of the library. Each call returns a cold [Flow]: the transfer starts when you
 * collect it and is cancelled when you stop collecting. Failures are thrown as
 * [WormholeException] subclasses.
 */
public class Wormhole internal constructor(
    private val config: WormholeConfig,
    private val rendezvousTransport: RendezvousTransport,
) {
    public constructor(config: WormholeConfig = WormholeConfig()) : this(config, WebSocketTransport)

    /** Sends [text]. Emits [SendEvent.CodeAllocated], then [SendEvent.Completed]. */
    public fun sendText(text: String): Flow<SendEvent> = flow {
        coroutineScope {
            withSenderMailbox(this) { client, session, _ ->
                session.send(buildJsonObject { put("offer", buildJsonObject { put("message", text) }) })
                val answer = session.receive()
                answer.throwIfPeerError()
                val ack = (answer["answer"] as? JsonObject)?.stringValue("message_ack")
                if (ack != "ok") throw WormholeProtocolException("Unexpected answer to a text offer: $answer")
                client.markHappy()
            }
            emit(SendEvent.Completed)
        }
    }

    /** Receives whatever the sender offers with [code]. */
    public fun receive(code: String): Flow<ReceiveEvent> = flow {
        val nameplate = Codes.nameplateOf(code)
        coroutineScope {
            val result = withMailbox(this, nameplate = nameplate, code = code) { client, session ->
                val offer = receiveOffer(session)
                val text = offer.stringValue("message")
                if (text == null) {
                    session.send(buildJsonObject { put("error", "unsupported offer") })
                    throw WormholeProtocolException("Unsupported offer: $offer")
                }
                session.send(buildJsonObject { put("answer", buildJsonObject { put("message_ack", "ok") }) })
                client.markHappy()
                ReceiveEvent.TextReceived(text)
            }
            emit(result)
        }
    }

    /** Reads phases until the sender's offer arrives. */
    private suspend fun receiveOffer(session: WormholeSession): JsonObject {
        while (true) {
            val message = session.receive()
            message.throwIfPeerError()
            (message["offer"] as? JsonObject)?.let { return it }
        }
    }

    // --- mailbox plumbing ---------------------------------------------------------------------

    /** Tracks the mood reported when the mailbox is closed. */
    private class Mood {
        var value = "errory"
    }

    private class ClientHandle(val client: RendezvousClient, val mood: Mood) {
        fun markHappy() {
            mood.value = "happy"
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<SendEvent>.withSenderMailbox(
        scope: CoroutineScope,
        block: suspend (ClientHandle, WormholeSession, String) -> Unit,
    ) {
        val client = RendezvousClient.connect(rendezvousTransport, config.rendezvousUrl, config.appId, scope)
        val handle = ClientHandle(client, Mood())
        var mailbox: String? = null
        try {
            client.bind()
            val nameplate = client.allocate()
            mailbox = client.claim(nameplate)
            client.open(mailbox)
            val code = Codes.build(nameplate, config.codeLength)
            emit(SendEvent.CodeAllocated(code))
            val session = WormholeSession(client, config.appId)
            exchangeKeysMarkingMood(session, code, handle)
            client.release(nameplate)
            block(handle, session, code)
        } finally {
            closeClient(client, mailbox, handle.mood.value)
        }
    }

    private suspend fun <T> withMailbox(
        scope: CoroutineScope,
        nameplate: String,
        code: String,
        block: suspend (ClientHandle, WormholeSession) -> T,
    ): T {
        val client = RendezvousClient.connect(rendezvousTransport, config.rendezvousUrl, config.appId, scope)
        val handle = ClientHandle(client, Mood())
        var mailbox: String? = null
        try {
            client.bind()
            mailbox = client.claim(nameplate)
            client.open(mailbox)
            val session = WormholeSession(client, config.appId)
            exchangeKeysMarkingMood(session, code, handle)
            client.release(nameplate)
            return block(handle, session)
        } finally {
            closeClient(client, mailbox, handle.mood.value)
        }
    }

    private suspend fun exchangeKeysMarkingMood(session: WormholeSession, code: String, handle: ClientHandle) {
        try {
            session.exchangeKeys(code)
        } catch (e: WrongCodeException) {
            handle.mood.value = "scary"
            throw e
        }
    }

    private suspend fun closeClient(client: RendezvousClient, mailbox: String?, mood: String) {
        if (mailbox != null) client.close(mailbox, mood) else client.shutdown()
    }
}

private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Throws when the other side sent `{"error": "..."}`. */
internal fun JsonObject.throwIfPeerError() {
    val error = this["error"] ?: return
    val text = (error as? JsonPrimitive)?.contentOrNull ?: error.toString()
    if (text == "transfer rejected") throw TransferRejectedException()
    throw PeerErrorException(text)
}
