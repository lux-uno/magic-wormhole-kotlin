package uno.lux.wormhole

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import uno.lux.wormhole.code.Codes
import uno.lux.wormhole.rendezvous.RendezvousClient
import uno.lux.wormhole.rendezvous.RendezvousTransport
import uno.lux.wormhole.rendezvous.WebSocketTransport
import uno.lux.wormhole.transit.DirectHint
import uno.lux.wormhole.transit.TcpTransitNetwork
import uno.lux.wormhole.transit.TransitHints
import uno.lux.wormhole.transit.TransitNetwork

/** Default Magic Wormhole rendezvous (mailbox) server, as used by the current `wormhole` CLI. */
public const val DEFAULT_RENDEZVOUS_URL: String = "wss://relay.magic-wormhole.io/v1"

/** Default Magic Wormhole transit relay. */
public const val DEFAULT_TRANSIT_RELAY: String = "tcp:transit.magic-wormhole.io:4001"

/** Application ID used by the `wormhole` CLI and wormhole-william for text and file transfers. */
public const val DEFAULT_APP_ID: String = "lothar.com/wormhole/text-or-file-xfer"

/** Settings for a [Wormhole]. The defaults are compatible with the `wormhole` CLI. */
public data class WormholeConfig(
    val rendezvousUrl: String = DEFAULT_RENDEZVOUS_URL,
    /** Transit relay as `tcp:host:port`, or null to use direct connections only. */
    val transitRelay: String? = DEFAULT_TRANSIT_RELAY,
    val appId: String = DEFAULT_APP_ID,
    /** Number of words after the nameplate in generated codes. */
    val codeLength: Int = 2,
) {
    init {
        require(codeLength >= 1) { "codeLength must be at least 1" }
        transitRelay?.let(DirectHint::parseRelayUrl)
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

    /**
     * The sender offers a file. Call [accept] or [reject]; the flow waits until you do.
     * A directory arrives as a zip file named `<dirname>.zip` ([isDirectory] is true).
     */
    public class FileOffered internal constructor(
        public val name: String,
        public val size: Long,
        public val isDirectory: Boolean,
        private val decision: CompletableDeferred<RawSink?>,
    ) : ReceiveEvent {
        /** Receives the file into [sink]. The library closes [sink] when the transfer ends. */
        public fun accept(sink: RawSink) {
            decision.complete(sink)
        }

        /** Declines the file. The flow then completes. */
        public fun reject() {
            decision.complete(null)
        }

        override fun toString(): String = "FileOffered(name=$name, size=$size, isDirectory=$isDirectory)"
    }

    public data class Progress(val receivedBytes: Long, val totalBytes: Long) : ReceiveEvent

    /** The whole file was received and verified. This is the last event. */
    public data object FileReceived : ReceiveEvent
}

/**
 * Entry point of the library. Each call returns a cold [Flow]: the transfer starts when you
 * collect it and is cancelled when you stop collecting. Failures are thrown as
 * [WormholeException] subclasses.
 */
public class Wormhole internal constructor(
    private val config: WormholeConfig,
    private val rendezvousTransport: RendezvousTransport,
    private val transitNetwork: () -> TransitNetwork = { TcpTransitNetwork() },
) {
    public constructor(config: WormholeConfig = WormholeConfig()) : this(config, WebSocketTransport)

    private val relay: DirectHint? = config.transitRelay?.let(DirectHint::parseRelayUrl)

    /** Sends [text]. Emits [SendEvent.CodeAllocated], then [SendEvent.Completed]. */
    public fun sendText(text: String): Flow<SendEvent> = flow {
        coroutineScope {
            withSenderMailbox(this) { handle, session ->
                session.send(buildJsonObject { put("offer", buildJsonObject { put("message", text) }) })
                val answer = session.receive()
                answer.throwIfPeerError()
                val ack = (answer["answer"] as? JsonObject)?.stringValue("message_ack")
                if (ack != "ok") throw WormholeProtocolException("Unexpected answer to a text offer: $answer")
                handle.markHappy()
            }
            emit(SendEvent.Completed)
        }
    }

    /**
     * Sends [size] bytes from [source] as a file called [name]. Emits
     * [SendEvent.CodeAllocated], [SendEvent.Progress] updates, then [SendEvent.Completed].
     * The library closes [source] when the transfer ends.
     */
    public fun sendFile(name: String, size: Long, source: RawSource): Flow<SendEvent> = flow {
        try {
            coroutineScope {
                withSenderMailbox(this) { handle, session ->
                    val network = transitNetwork()
                    try {
                        FileTransfer.send(session, network, relay, name, size, source) { sent ->
                            emit(SendEvent.Progress(sent, size))
                        }
                        handle.markHappy()
                    } finally {
                        network.close()
                    }
                }
                emit(SendEvent.Completed)
            }
        } finally {
            source.close()
        }
    }

    /** Receives whatever the sender offers with [code]. */
    public fun receive(code: String): Flow<ReceiveEvent> = flow {
        val nameplate = Codes.nameplateOf(code)
        coroutineScope {
            withMailbox(this, nameplate = nameplate, code = code) { handle, session ->
                val (offer, senderTransit) = receiveOffer(session)
                offer.stringValue("message")?.let { text ->
                    session.send(buildJsonObject { put("answer", buildJsonObject { put("message_ack", "ok") }) })
                    handle.markHappy()
                    emit(ReceiveEvent.TextReceived(text))
                    return@withMailbox
                }
                val file = offer["file"] as? JsonObject
                val directory = offer["directory"] as? JsonObject
                val (name, size) = when {
                    file != null -> file.stringValue("filename") to file.longValue("filesize")
                    directory != null -> directory.stringValue("dirname")?.let { "$it.zip" } to directory.longValue("zipsize")
                    else -> null to null
                }
                if (name == null || size == null || size < 0) {
                    session.send(buildJsonObject { put("error", "unsupported offer") })
                    throw WormholeProtocolException("Unsupported offer: $offer")
                }

                val decision = CompletableDeferred<RawSink?>()
                emit(ReceiveEvent.FileOffered(name, size, isDirectory = directory != null, decision))
                val sink = decision.await()
                if (sink == null) {
                    session.send(buildJsonObject { put("error", "transfer rejected") })
                    handle.markHappy()
                    return@withMailbox
                }
                val network = transitNetwork()
                try {
                    FileTransfer.receive(session, network, relay, senderTransit, size, sink) { received ->
                        emit(ReceiveEvent.Progress(received, size))
                    }
                    handle.markHappy()
                } finally {
                    network.close()
                    sink.close()
                }
                emit(ReceiveEvent.FileReceived)
            }
        }
    }

    /** Reads phases until the sender's offer arrives. Also returns the sender's transit hints. */
    private suspend fun receiveOffer(session: WormholeSession): Pair<JsonObject, TransitHints?> {
        var transit: TransitHints? = null
        while (true) {
            val message = session.receive()
            message.throwIfPeerError()
            (message["transit"] as? JsonObject)?.let { transit = TransitHints.parse(it) }
            (message["offer"] as? JsonObject)?.let { return it to transit }
        }
    }

    // --- mailbox plumbing ---------------------------------------------------------------------

    /** Tracks the mood reported when the mailbox is closed. */
    private class Handle {
        var mood = "errory"

        fun markHappy() {
            mood = "happy"
        }
    }

    private suspend fun FlowCollector<SendEvent>.withSenderMailbox(
        scope: CoroutineScope,
        block: suspend (Handle, WormholeSession) -> Unit,
    ) {
        val client = RendezvousClient.connect(rendezvousTransport, config.rendezvousUrl, config.appId, scope)
        val handle = Handle()
        var mailbox: String? = null
        try {
            client.bind()
            val nameplate = client.allocate()
            mailbox = client.claim(nameplate)
            client.open(mailbox)
            val code = Codes.build(nameplate, config.codeLength)
            emit(SendEvent.CodeAllocated(code))
            val session = WormholeSession(client, config.appId)
            exchangeKeys(session, code, handle)
            client.release(nameplate)
            block(handle, session)
        } finally {
            closeClient(client, mailbox, handle.mood)
        }
    }

    private suspend fun withMailbox(
        scope: CoroutineScope,
        nameplate: String,
        code: String,
        block: suspend (Handle, WormholeSession) -> Unit,
    ) {
        val client = RendezvousClient.connect(rendezvousTransport, config.rendezvousUrl, config.appId, scope)
        val handle = Handle()
        var mailbox: String? = null
        try {
            client.bind()
            mailbox = client.claim(nameplate)
            client.open(mailbox)
            val session = WormholeSession(client, config.appId)
            exchangeKeys(session, code, handle)
            client.release(nameplate)
            block(handle, session)
        } finally {
            closeClient(client, mailbox, handle.mood)
        }
    }

    private suspend fun exchangeKeys(session: WormholeSession, code: String, handle: Handle) {
        try {
            session.exchangeKeys(code)
        } catch (e: WrongCodeException) {
            handle.mood = "scary"
            throw e
        }
    }

    private suspend fun closeClient(client: RendezvousClient, mailbox: String?, mood: String) {
        if (mailbox != null) client.close(mailbox, mood) else client.shutdown()
    }
}

internal fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.longValue(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

/** Throws when the other side sent `{"error": "..."}`. */
internal fun JsonObject.throwIfPeerError() {
    val error = this["error"] ?: return
    val text = (error as? JsonPrimitive)?.contentOrNull ?: error.toString()
    if (text == "transfer rejected") throw TransferRejectedException()
    throw PeerErrorException(text)
}
