// Races Dilation L2 connection attempts and picks a winner, following magic-wormhole's
// _dilation/connector.py (MIT). Direct hints are dialled immediately; a relay is dialled too, with
// a head start for direct hints. The relay handshake line is byte-for-byte the same one
// transit/Transit.kt uses (same "transit_relay_token" purpose string), so it's reused here.
package uno.lux.wormhole.dilation

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeFully
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
import uno.lux.wormhole.WormholeProtocolException
import uno.lux.wormhole.crypto.NoiseHandshake
import uno.lux.wormhole.crypto.NoiseRole
import uno.lux.wormhole.transit.Handshakes
import uno.lux.wormhole.transit.TransitListener
import uno.lux.wormhole.transit.TransitNetwork
import uno.lux.wormhole.transit.TransitSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val PROLOGUE_LEADER = "Magic-Wormhole Dilation Handshake v1 Leader\n\n".encodeToByteArray()
private val PROLOGUE_FOLLOWER = "Magic-Wormhole Dilation Handshake v1 Follower\n\n".encodeToByteArray()

/**
 * Manages one generation of Dilation connection attempts. Call [start] to get our own hints to
 * advertise, then [connect] with the peer's hints. The first connection whose Noise handshake
 * completes and exchanges a KCM wins; every other candidate (mid-handshake or already viable) is
 * dropped.
 */
internal class DilationConnector(
    private val role: DilationRole,
    private val dilationKey: ByteArray,
    private val side: String,
    private val network: TransitNetwork,
    private val relay: DilationHint.Direct?,
    private val relayDelay: Duration = 2.seconds,
    private val handshakeTimeout: Duration = 60.seconds,
) {
    private var listener: TransitListener? = null

    /** Opens a local listener if possible and returns the hints to advertise to the peer. */
    suspend fun start(): List<DilationHint> {
        val l =
            try {
                network.listen()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        listener = l
        val direct = if (l == null) emptyList() else network.localAddresses().map { DilationHint.Direct(it, l.port) }
        val relayHint = relay?.let { DilationHint.Relay(listOf(it)) }
        return direct + listOfNotNull(relayHint)
    }

    suspend fun connect(peerHints: List<DilationHint>): DilationConnection =
        coroutineScope {
            val race = ConnectionRace()
            listener?.let { launch { race.acceptConnections(it) } }
            val peerDirect = peerHints.filterIsInstance<DilationHint.Direct>()
            val peerRelays = peerHints.filterIsInstance<DilationHint.Relay>().flatMap { it.hints }
            val attempts = launchDirectAttempts(peerDirect, race) + launchRelayAttempts(peerDirect, peerRelays, race)
            launch { race.failWhenAllFail(attempts) }
            val winner =
                try {
                    race.winner.await()
                } finally {
                    listener?.close()
                }
            coroutineContext[Job]?.children?.forEach { it.cancel() }
            winner
        }

    fun close() {
        listener?.close()
    }

    private fun CoroutineScope.launchDirectAttempts(
        peerDirect: List<DilationHint.Direct>,
        race: ConnectionRace,
    ): List<Job> =
        peerDirect.distinct().map { hint ->
            launch { race.attempt({ network.connect(hint.hostname, hint.port) }, viaRelay = false) }
        }

    /** When a direct connection is possible, relays wait [relayDelay] to give it a head start. */
    private fun CoroutineScope.launchRelayAttempts(
        peerDirect: List<DilationHint.Direct>,
        peerRelayHints: List<DilationHint.Direct>,
        race: ConnectionRace,
    ): List<Job> {
        val directPossible = peerDirect.isNotEmpty() || listener != null
        return (listOfNotNull(relay) + peerRelayHints).distinct().map { hint ->
            launch {
                if (directPossible) delay(relayDelay)
                race.attempt({ network.connect(hint.hostname, hint.port) }, viaRelay = true)
            }
        }
    }

    /** Performs the relay handshake (if any), the prologue exchange, and the Noise handshake. */
    private suspend fun negotiate(
        socket: TransitSocket,
        viaRelay: Boolean,
    ): DilationConnection {
        if (viaRelay) {
            socket.send(Handshakes.relay(dilationKey, side))
            expect(socket.input, Handshakes.RELAY_OK)
        }
        val (outboundPrologue, inboundPrologue) =
            when (role) {
                DilationRole.LEADER -> PROLOGUE_LEADER to PROLOGUE_FOLLOWER
                DilationRole.FOLLOWER -> PROLOGUE_FOLLOWER to PROLOGUE_LEADER
            }
        socket.send(outboundPrologue)
        expect(socket.input, inboundPrologue)

        val noiseRole = if (role == DilationRole.LEADER) NoiseRole.INITIATOR else NoiseRole.RESPONDER
        val handshake = NoiseHandshake(noiseRole, dilationKey)
        val connection = DilationConnection(socket, handshake)
        if (noiseRole == NoiseRole.INITIATOR) {
            connection.writeHandshakeMessage()
            connection.readHandshakeMessage()
        } else {
            connection.readHandshakeMessage()
            connection.writeHandshakeMessage()
        }
        connection.completeHandshake()
        if (role == DilationRole.FOLLOWER) connection.sendKcm()
        return connection
    }

    private suspend fun expect(
        input: ByteReadChannel,
        expected: ByteArray,
    ) {
        val got = input.readByteArray(expected.size)
        if (!got.contentEquals(expected)) throw WormholeProtocolException("Bad Dilation handshake")
    }

    /** The connection attempts of one [connect] call. The first viable connection wins. */
    private inner class ConnectionRace {
        val winner = CompletableDeferred<DilationConnection>()
        private val decision = Mutex()
        private var lastError: Throwable? = null

        suspend fun attempt(
            open: suspend () -> TransitSocket,
            viaRelay: Boolean,
        ) {
            var connection: DilationConnection? = null
            try {
                val socket = open()
                connection =
                    withTimeout(handshakeTimeout) {
                        val c = negotiate(socket, viaRelay)
                        c.awaitKcm()
                        c
                    }
                if (!decide(connection)) connection.close()
            } catch (e: Throwable) {
                connection?.close()
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
            if (!winner.isCompleted && listener != null) delay(handshakeTimeout)
            winner.completeExceptionally(TransitException("Could not establish a Dilation connection", lastError))
        }

        /** The first candidate wins. Only the Leader confirms it by sending a KCM back. */
        private suspend fun decide(connection: DilationConnection): Boolean =
            decision.withLock {
                if (winner.isCompleted) {
                    false
                } else {
                    if (role == DilationRole.LEADER) connection.sendKcm()
                    winner.complete(connection)
                    true
                }
            }
    }
}

private suspend fun TransitSocket.send(bytes: ByteArray) {
    output.writeFully(bytes)
    output.flush()
}
