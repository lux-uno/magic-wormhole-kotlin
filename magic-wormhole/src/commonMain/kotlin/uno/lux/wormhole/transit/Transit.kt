// The transit protocol (direct and relayed TCP connections with encrypted records), following
// magic-wormhole's transit.py (MIT) and wormhole-william's file_transport.go (MIT).
package uno.lux.wormhole.transit

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import uno.lux.wormhole.TransitException
import uno.lux.wormhole.crypto.DecryptionException
import uno.lux.wormhole.crypto.SecretBox
import uno.lux.wormhole.crypto.hkdfSha256
import uno.lux.wormhole.crypto.randomBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A bidirectional byte stream (a TCP connection in production). */
internal interface TransitSocket {
    val input: ByteReadChannel
    val output: ByteWriteChannel

    fun close()
}

internal interface TransitListener {
    val port: Int

    suspend fun accept(): TransitSocket

    fun close()
}

internal interface TransitNetwork {
    suspend fun connect(
        host: String,
        port: Int,
    ): TransitSocket

    /** Starts listening for direct connections, or returns null when this platform does not. */
    suspend fun listen(): TransitListener?

    /** IP addresses to advertise in direct hints. */
    fun localAddresses(): List<String>

    /** Releases resources after the transfer. */
    fun close() {}
}

internal object Handshakes {
    private fun derive(
        key: ByteArray,
        purpose: String,
    ) = hkdfSha256(key, ByteArray(0), purpose.encodeToByteArray(), 32)

    fun sender(key: ByteArray): ByteArray =
        "transit sender ${derive(key, "transit_sender").toHexString()} ready\n\n".encodeToByteArray()

    fun receiver(key: ByteArray): ByteArray =
        "transit receiver ${derive(key, "transit_receiver").toHexString()} ready\n\n".encodeToByteArray()

    fun relay(
        key: ByteArray,
        side: String,
    ): ByteArray =
        "please relay ${derive(key, "transit_relay_token").toHexString()} for side $side\n".encodeToByteArray()

    fun senderRecordKey(key: ByteArray) = derive(key, "transit_record_sender_key")

    fun receiverRecordKey(key: ByteArray) = derive(key, "transit_record_receiver_key")

    val GO = "go\n".encodeToByteArray()
    val NEVERMIND = "nevermind\n".encodeToByteArray()
    val RELAY_OK = "ok\n".encodeToByteArray()
}

/**
 * Encrypted, length-prefixed records over a [TransitSocket].
 * Each record is `length(4, big-endian) || nonce(24, big-endian counter) || secretbox`.
 */
internal class RecordPipe(
    private val socket: TransitSocket,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
) {
    private var sendNonce = 0L
    private var receiveNonce = 0L

    suspend fun send(record: ByteArray) {
        val nonce = nonceBytes(sendNonce++)
        val box = SecretBox.seal(record, nonce, sendKey)
        failingOnLostConnection {
            socket.output.writeInt(nonce.size + box.size)
            socket.output.writeFully(nonce)
            socket.output.writeFully(box)
            socket.output.flush()
        }
    }

    suspend fun receive(): ByteArray {
        val encrypted = failingOnLostConnection { readRecord() }
        val nonce = encrypted.copyOfRange(0, SecretBox.NONCE_SIZE)
        if (!nonce.contentEquals(nonceBytes(receiveNonce))) {
            throw TransitException("Received an out-of-order record")
        }
        receiveNonce++
        return try {
            SecretBox.open(encrypted.copyOfRange(SecretBox.NONCE_SIZE, encrypted.size), nonce, receiveKey)
        } catch (e: DecryptionException) {
            throw TransitException("Could not decrypt a record", e)
        }
    }

    private suspend fun readRecord(): ByteArray {
        val length = socket.input.readInt()
        if (length < SecretBox.NONCE_SIZE + SecretBox.MAC_SIZE || length > MAX_RECORD_SIZE) {
            throw TransitException("Invalid record length $length")
        }
        return socket.input.readByteArray(length)
    }

    /** Runs [block], and turns I/O errors into a [TransitException]. */
    private inline fun <T> failingOnLostConnection(block: () -> T): T =
        try {
            block()
        } catch (e: TransitException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransitException("Connection to the other side was lost", e)
        }

    fun close() = socket.close()

    private fun nonceBytes(counter: Long): ByteArray {
        val n = ByteArray(SecretBox.NONCE_SIZE)
        var c = counter
        for (i in n.indices.reversed()) {
            n[i] = c.toByte()
            c = c ushr 8
        }
        return n
    }

    companion object {
        const val MAX_RECORD_SIZE = 64 * 1024 * 1024
    }
}

/**
 * One side of a transit negotiation. Call [start] to get our `transit` message, send it to the
 * peer, then [connect] with the peer's hints. Direct hints are tried first; relays after
 * [relayDelay]. The first connection that completes the handshake wins.
 */
internal class Transit(
    private val role: Role,
    private val transitKey: ByteArray,
    private val network: TransitNetwork,
    private val relay: DirectHint?,
    private val relayDelay: Duration = 2.seconds,
    private val handshakeTimeout: Duration = 60.seconds,
) {
    enum class Role { SENDER, RECEIVER }

    private var listener: TransitListener? = null
    private val relaySide = randomBytes(8).toHexString()

    suspend fun start(): kotlinx.serialization.json.JsonObject {
        val l =
            try {
                network.listen()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        listener = l
        val direct = if (l == null) emptyList() else network.localAddresses().map { DirectHint(it, l.port) }
        return TransitHints.toTransitMessage(direct, relay)
    }

    suspend fun connect(peer: TransitHints): RecordPipe =
        coroutineScope {
            val race = ConnectionRace()
            listener?.let { launch { race.acceptConnections(it) } }
            val attempts = launchDirectAttempts(peer, race) + launchRelayAttempts(peer, race)
            launch { race.failWhenAllFail(attempts) }
            val socket =
                try {
                    race.winner.await()
                } finally {
                    listener?.close()
                }
            coroutineContext[Job]?.children?.forEach { it.cancel() }
            recordPipe(socket)
        }

    private fun CoroutineScope.launchDirectAttempts(
        peer: TransitHints,
        race: ConnectionRace,
    ): List<Job> =
        peer.direct.distinct().map { hint ->
            launch { race.attempt({ network.connect(hint.hostname, hint.port) }, viaRelay = false) }
        }

    /** When a direct connection is possible, relays wait [relayDelay] to give it a head start. */
    private fun CoroutineScope.launchRelayAttempts(
        peer: TransitHints,
        race: ConnectionRace,
    ): List<Job> {
        val directPossible = peer.direct.isNotEmpty() || listener != null
        return (listOfNotNull(relay) + peer.relays).distinct().map { hint ->
            launch {
                if (directPossible) delay(relayDelay)
                race.attempt({ network.connect(hint.hostname, hint.port) }, viaRelay = true)
            }
        }
    }

    private fun recordPipe(socket: TransitSocket): RecordPipe {
        val senderKey = Handshakes.senderRecordKey(transitKey)
        val receiverKey = Handshakes.receiverRecordKey(transitKey)
        return when (role) {
            Role.SENDER -> RecordPipe(socket, sendKey = senderKey, receiveKey = receiverKey)
            Role.RECEIVER -> RecordPipe(socket, sendKey = receiverKey, receiveKey = senderKey)
        }
    }

    /** The connection attempts of one [connect] call. The first socket through the handshake wins. */
    private inner class ConnectionRace {
        val winner = CompletableDeferred<TransitSocket>()
        private val decision = Mutex()
        private var lastError: Throwable? = null

        suspend fun attempt(
            open: suspend () -> TransitSocket,
            viaRelay: Boolean,
        ) {
            var socket: TransitSocket? = null
            try {
                socket = open()
                withTimeout(handshakeTimeout) { handshake(socket, viaRelay) }
                if (!decide(socket)) socket.close()
            } catch (e: Throwable) {
                socket?.close()
                if (e is kotlinx.coroutines.CancellationException && !winner.isCompleted) throw e
                lastError = e
            }
        }

        /** Tries each connection the peer makes to our listener, until one wins. */
        suspend fun acceptConnections(listener: TransitListener) =
            coroutineScope {
                while (!winner.isCompleted) {
                    val socket =
                        try {
                            listener.accept()
                        } catch (e: Exception) {
                            break
                        }
                    launch { attempt({ socket }, viaRelay = false) }
                }
            }

        suspend fun failWhenAllFail(attempts: List<Job>) {
            attempts.joinAll()
            // With a listener the peer may still connect to us; give it the handshake timeout.
            if (!winner.isCompleted && listener != null) delay(handshakeTimeout)
            winner.completeExceptionally(TransitException("Could not connect to the other side", lastError))
        }

        /** The sender picks the first socket and tells the receiver. Returns true if [socket] won. */
        private suspend fun decide(socket: TransitSocket): Boolean =
            when (role) {
                Role.SENDER -> {
                    decision.withLock {
                        if (winner.isCompleted) {
                            socket.send(Handshakes.NEVERMIND)
                            false
                        } else {
                            socket.send(Handshakes.GO)
                            winner.complete(socket)
                        }
                    }
                }

                Role.RECEIVER -> {
                    expect(socket.input, Handshakes.GO)
                    decision.withLock { winner.complete(socket) }
                }
            }
    }

    fun close() {
        listener?.close()
    }

    private suspend fun handshake(
        socket: TransitSocket,
        viaRelay: Boolean,
    ) {
        if (viaRelay) {
            socket.send(Handshakes.relay(transitKey, relaySide))
            expect(socket.input, Handshakes.RELAY_OK)
        }
        val (mine, theirs) =
            when (role) {
                Role.SENDER -> Handshakes.sender(transitKey) to Handshakes.receiver(transitKey)
                Role.RECEIVER -> Handshakes.receiver(transitKey) to Handshakes.sender(transitKey)
            }
        socket.send(mine)
        expect(socket.input, theirs)
    }

    private suspend fun expect(
        input: ByteReadChannel,
        expected: ByteArray,
    ) {
        val got = input.readByteArray(expected.size)
        if (!got.contentEquals(expected)) throw TransitException("Bad transit handshake")
    }
}

private suspend fun TransitSocket.send(bytes: ByteArray) {
    output.writeFully(bytes)
    output.flush()
}
