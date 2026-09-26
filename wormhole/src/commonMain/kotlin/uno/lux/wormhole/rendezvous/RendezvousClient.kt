package uno.lux.wormhole.rendezvous

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uno.lux.wormhole.ServerConnectionException
import uno.lux.wormhole.WormholeException
import uno.lux.wormhole.WormholeServerException
import uno.lux.wormhole.crypto.randomBytes

/** A text-message connection to a mailbox server (a WebSocket in production). */
internal interface RendezvousConnection {
    /** Messages from the server. Closed when the connection ends. */
    val incoming: ReceiveChannel<String>

    suspend fun send(text: String)

    suspend fun close()
}

internal fun interface RendezvousTransport {
    suspend fun connect(url: String): RendezvousConnection
}

/**
 * Client for the mailbox server protocol. One instance = one connection = one side.
 * Messages that this side sent itself (the server echoes them) are filtered out of [receive].
 */
internal class RendezvousClient private constructor(
    private val connection: RendezvousConnection,
    private val appId: String,
    scope: CoroutineScope,
) {
    val side: String = randomBytes(5).toHexString()

    private val welcome = CompletableDeferred<ServerMessage.Welcome>()
    private val replies = Channel<ServerMessage>(Channel.UNLIMITED)
    private val messages = Channel<ServerMessage.Message>(Channel.UNLIMITED)
    private var shuttingDown = false
    private val reader: Job = scope.launch { readLoop() }

    private suspend fun readLoop() {
        var failure: WormholeException? = null
        try {
            for (text in connection.incoming) {
                when (val m = ServerMessage.parse(text)) {
                    is ServerMessage.Welcome -> {
                        welcome.complete(m)
                    }

                    is ServerMessage.Message -> {
                        if (m.side != side) messages.send(m)
                    }

                    is ServerMessage.Error -> {
                        failure = WormholeServerException(m.error)
                        break
                    }

                    ServerMessage.Ack, is ServerMessage.Pong, is ServerMessage.Unknown -> {}

                    else -> {
                        replies.send(m)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failure = ServerConnectionException("Connection to the wormhole server failed", e)
        }
        val error =
            failure
                ?: if (shuttingDown) null else ServerConnectionException("Connection to the wormhole server was lost")
        welcome.completeExceptionally(error ?: ServerConnectionException("Connection closed"))
        replies.close(error)
        messages.close(error)
    }

    private suspend fun send(message: ClientMessage) {
        try {
            connection.send(message.toJson(randomBytes(2).toHexString()))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw ServerConnectionException("Could not send to the wormhole server", e)
        }
    }

    private suspend inline fun <reified T : ServerMessage> awaitReply(): T {
        try {
            while (true) {
                val m = replies.receive()
                if (m is T) return m
            }
        } catch (e: ClosedReceiveChannelException) {
            throw ServerConnectionException("Connection to the wormhole server was lost", e)
        }
    }

    /** Waits for the server's welcome and binds this side to [appId]. */
    suspend fun bind() {
        val w =
            try {
                welcome.await()
            } catch (e: ClosedReceiveChannelException) {
                throw ServerConnectionException("Connection to the wormhole server was lost", e)
            }
        w.error?.let { throw WormholeServerException(it) }
        send(ClientMessage.Bind(appId, side, CLIENT_VERSION))
    }

    suspend fun allocate(): String {
        send(ClientMessage.Allocate)
        return awaitReply<ServerMessage.Allocated>().nameplate
    }

    suspend fun claim(nameplate: String): String {
        send(ClientMessage.Claim(nameplate))
        return awaitReply<ServerMessage.Claimed>().mailbox
    }

    suspend fun open(mailbox: String) = send(ClientMessage.Open(mailbox))

    suspend fun add(
        phase: String,
        body: ByteArray,
    ) = send(ClientMessage.Add(phase, body))

    /** Returns the next mailbox message from the other side. */
    suspend fun receive(): ServerMessage.Message =
        try {
            messages.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw ServerConnectionException("Connection to the wormhole server was lost", e)
        }

    suspend fun release(nameplate: String) {
        send(ClientMessage.Release(nameplate))
        withTimeoutOrNull(REPLY_TIMEOUT_MS) { awaitReply<ServerMessage.Released>() }
    }

    /** Closes the mailbox with [mood] and then the connection. Best effort; never throws. */
    suspend fun close(
        mailbox: String,
        mood: String,
    ) {
        withContext(NonCancellable) {
            try {
                send(ClientMessage.Close(mailbox, mood))
                withTimeoutOrNull(REPLY_TIMEOUT_MS) { awaitReply<ServerMessage.Closed>() }
            } catch (_: WormholeException) {
            }
            shutdown()
        }
    }

    /** Closes the connection without closing the mailbox. */
    suspend fun shutdown() {
        shuttingDown = true
        withContext(NonCancellable) {
            try {
                connection.close()
            } catch (_: Exception) {
            }
            reader.cancel()
        }
    }

    companion object {
        val CLIENT_VERSION = listOf("kotlin", "magic-wormhole-kotlin 0.1.0")
        private const val REPLY_TIMEOUT_MS = 5_000L

        suspend fun connect(
            transport: RendezvousTransport,
            url: String,
            appId: String,
            scope: CoroutineScope,
        ): RendezvousClient {
            val connection =
                try {
                    transport.connect(url)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    throw ServerConnectionException("Could not connect to the wormhole server at $url", e)
                }
            return RendezvousClient(connection, appId, scope)
        }
    }
}
