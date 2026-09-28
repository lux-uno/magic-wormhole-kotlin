// Dilation's mailbox-level version/role negotiation, the reliable multiplexed record layer, and
// reconnection: following magic-wormhole's _dilation/manager.py, _dilation/roles.py,
// _dilation/inbound.py and _dilation/outbound.py (MIT).
package uno.lux.wormhole.dilation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uno.lux.wormhole.DILATION_KEY_PURPOSE
import uno.lux.wormhole.DILATION_VERSION
import uno.lux.wormhole.WormholeProtocolException
import uno.lux.wormhole.WormholeSession
import uno.lux.wormhole.crypto.randomBytes
import uno.lux.wormhole.transit.TransitNetwork
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal enum class DilationRole { LEADER, FOLLOWER }

/** Mirrors `Manager`'s Automat states that matter once a connection is being established/used. */
internal enum class DilationManagerState { CONNECTING, CONNECTED, FLUSHING, LONELY, ABANDONING }

/** One event delivered to [DilationManager.events] for an OPEN/DATA/CLOSE the peer sent us. */
internal sealed interface DilationInboundEvent {
    data class Opened(
        val scid: Int,
        val subprotocol: String,
    ) : DilationInboundEvent

    data class DataReceived(
        val scid: Int,
        val payload: ByteArray,
    ) : DilationInboundEvent

    data class Closed(
        val scid: Int,
    ) : DilationInboundEvent
}

private enum class ConnectionLossReason { CONNECTION_DIED, PEER_RECONNECT_WHILE_CONNECTED }

/**
 * Negotiates Dilation's leader/follower role over the mailbox, then establishes and maintains a
 * reconnectable, multiplexed L2 connection. [side] is a Dilation-specific random value, independent
 * of the mailbox's own side.
 */
internal class DilationManager(
    private val session: WormholeSession,
    private val side: String = randomBytes(8).toHexString(),
    private val pingInterval: Duration = 30.seconds,
) {
    private var _role: DilationRole? = null
    private var _peerSide: String? = null

    val role: DilationRole get() = checkNotNull(_role) { "start() has not completed yet" }
    val peerSide: String get() = checkNotNull(_peerSide) { "start() has not completed yet" }

    /** `dilation-v1`, HKDF-derived from the main key: the PSK every Noise handshake attempt uses. */
    val dilationKey: ByteArray by lazy { session.deriveKey(DILATION_KEY_PURPOSE) }

    /**
     * Sends this side's `please` and waits for the peer's, deciding which side leads. The peer must
     * have advertised [DILATION_VERSION] support in its "version" message (see
     * [WormholeSession.peerSupportsDilation]) before this is called, or it will wait forever.
     */
    suspend fun start() {
        session.sendDilation(
            buildJsonObject {
                put("type", "please")
                put("side", side)
                put("use-version", DILATION_VERSION)
            },
        )
        val message = session.receiveDilation()
        if ((message["type"] as? JsonPrimitive)?.content != "please") {
            throw WormholeProtocolException("Expected a Dilation 'please' message, got $message")
        }
        val theirVersion = (message["use-version"] as? JsonPrimitive)?.content
        if (theirVersion != DILATION_VERSION) {
            throw WormholeProtocolException("Peer does not support Dilation version $DILATION_VERSION")
        }
        val theirSide =
            (message["side"] as? JsonPrimitive)?.content
                ?: throw WormholeProtocolException("Dilation 'please' message is missing 'side'")
        if (theirSide == side) {
            throw WormholeProtocolException("Dilation side collision with peer")
        }
        _peerSide = theirSide
        _role = if (side > theirSide) DilationRole.LEADER else DilationRole.FOLLOWER
    }

    private lateinit var network: TransitNetwork
    private var relay: DilationHint.Direct? = null
    private lateinit var connection: DilationConnection
    private var nextSubchannelId = 0
    private var nextOutboundSeqnum = 0
    private var highestInboundAcked = -1
    private var trafficSeen = true
    private var generation = 0
    private val outboundQueue = ArrayDeque<DilationRecord>()
    private val outboundMutex = Mutex()
    private val inboundEvents = Channel<DilationInboundEvent>(Channel.UNLIMITED)
    private val dilateInbox = Channel<JsonObject>(Channel.UNLIMITED)

    private var _state = DilationManagerState.CONNECTING
    val state: DilationManagerState get() = _state

    /** OPEN/DATA/CLOSE events from the peer, in order, deduplicated. */
    val events: ReceiveChannel<DilationInboundEvent> get() = inboundEvents

    /**
     * Establishes the first L2 connection over [network] (racing hints via [DilationConnector]) and
     * then keeps it alive in the background (launched in [scope]): reconnecting, following
     * `Manager`'s Leader/Follower reconnection rules, whenever the connection is lost.
     */
    suspend fun connect(
        network: TransitNetwork,
        relay: DilationHint.Direct?,
        scope: CoroutineScope,
    ) {
        this.network = network
        this.relay = relay
        scope.launch { dilateReaderLoop() }
        establishConnection()
        scope.launch { lifecycleLoop() }
    }

    /** Sends OPEN for a fresh subchannel id (odd for the Leader, even for the Follower) and returns it. */
    suspend fun openSubchannel(subprotocol: String): Int {
        val scid = nextSubchannelId
        nextSubchannelId += 2
        sendQueued(DilationRecord.Open(scid, nextOutboundSeqnum++, subprotocol))
        return scid
    }

    suspend fun sendData(
        scid: Int,
        payload: ByteArray,
    ) = sendQueued(DilationRecord.Data(scid, nextOutboundSeqnum++, payload))

    suspend fun closeSubchannel(scid: Int) = sendQueued(DilationRecord.Close(scid, nextOutboundSeqnum++))

    internal fun outboundQueueSizeForTests(): Int = outboundQueue.size

    internal fun highestInboundAckedForTests(): Int = highestInboundAcked

    /**
     * Increments once per successfully established connection. Tests use this (rather than [state])
     * to detect a reconnect, since [state] can already read `CONNECTED` right after a drop that
     * hasn't been noticed by this manager yet.
     */
    internal fun generationForTests(): Int = generation

    /** Forces the current connection closed, as if the network had dropped it, for tests. */
    internal fun dropConnectionForTests() = connection.close()

    /** The lifetime of one connection generation: run it until lost, then reconnect, forever. */
    private suspend fun lifecycleLoop() {
        while (true) {
            val reason = runUntilConnectionLost()
            handleConnectionLoss(reason)
            establishConnection()
        }
    }

    /** Reads every `dilate-N` message into [dilateInbox], single-consumer for the manager's lifetime. */
    private suspend fun dilateReaderLoop() {
        while (true) {
            val message =
                try {
                    session.receiveDilation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return
                }
            dilateInbox.send(message)
        }
    }

    private suspend fun establishConnection() {
        val connector = DilationConnector(role, dilationKey, side, network, relay)
        val hints = connector.start()
        session.sendDilation(connectionHintsMessage(hints))
        val peerHints = parseConnectionHintsMessage(dilateInbox.receive())
        connection = connector.connect(peerHints)
        if (nextSubchannelId == 0) nextSubchannelId = if (role == DilationRole.LEADER) 1 else 2
        trafficSeen = true
        _state = DilationManagerState.CONNECTED
        generation++
        resendUnretiredQueue()
    }

    /**
     * Runs the read loop (and, for the Leader, the ping/pong liveness timer) until the connection is
     * lost, or — Follower only — until the Leader sends an unsolicited `reconnect` while we're still
     * connected (`Manager`'s `CONNECTED -(rx_RECONNECT)-> ABANDONING`).
     */
    private suspend fun runUntilConnectionLost(): ConnectionLossReason =
        coroutineScope {
            val outcome = CompletableDeferred<ConnectionLossReason>()
            val loopJob =
                launch {
                    readLoop()
                    outcome.complete(ConnectionLossReason.CONNECTION_DIED)
                }
            val liveness = if (role == DilationRole.LEADER) launch { leaderLivenessLoop() } else null
            val reconnectWatcher =
                if (role == DilationRole.FOLLOWER) {
                    launch {
                        val message = dilateInbox.receive()
                        if ((message["type"] as? JsonPrimitive)?.content != "reconnect") {
                            throw WormholeProtocolException("Unexpected Dilation message while connected: $message")
                        }
                        outcome.complete(ConnectionLossReason.PEER_RECONNECT_WHILE_CONNECTED)
                    }
                } else {
                    null
                }
            val reason = outcome.await()
            liveness?.cancel()
            reconnectWatcher?.cancel()
            if (reason == ConnectionLossReason.PEER_RECONNECT_WHILE_CONNECTED) loopJob.cancelAndJoin()
            reason
        }

    private suspend fun handleConnectionLoss(reason: ConnectionLossReason) {
        when (role) {
            DilationRole.LEADER -> {
                _state = DilationManagerState.FLUSHING
                session.sendDilation(buildJsonObject { put("type", "reconnect") })
                expectDilateType("reconnecting")
            }

            DilationRole.FOLLOWER -> {
                when (reason) {
                    ConnectionLossReason.CONNECTION_DIED -> {
                        _state = DilationManagerState.LONELY
                        expectDilateType("reconnect")
                        session.sendDilation(buildJsonObject { put("type", "reconnecting") })
                    }

                    ConnectionLossReason.PEER_RECONNECT_WHILE_CONNECTED -> {
                        _state = DilationManagerState.ABANDONING
                        connection.close()
                        session.sendDilation(buildJsonObject { put("type", "reconnecting") })
                    }
                }
            }
        }
        _state = DilationManagerState.CONNECTING
    }

    private suspend fun expectDilateType(expected: String) {
        val message = dilateInbox.receive()
        if ((message["type"] as? JsonPrimitive)?.content != expected) {
            throw WormholeProtocolException("Expected Dilation '$expected', got $message")
        }
    }

    /** Only the Leader runs this: ping every [pingInterval] of silence, give up after one more. */
    private suspend fun leaderLivenessLoop() {
        while (true) {
            delay(pingInterval)
            if (!trafficSeen) {
                connection.close()
                return
            }
            trafficSeen = false
            writeUnqueued(DilationRecord.Ping(randomPingId()))
        }
    }

    private fun randomPingId(): Int {
        val bytes = randomBytes(4)
        return ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
    }

    private suspend fun sendQueued(record: DilationRecord) =
        outboundMutex.withLock {
            outboundQueue.addLast(record)
            connection.sendRecord(record)
        }

    private suspend fun writeUnqueued(record: DilationRecord) = outboundMutex.withLock { connection.sendRecord(record) }

    private suspend fun retireAcked(respSeqnum: Int) =
        outboundMutex.withLock {
            while (outboundQueue.isNotEmpty() && (outboundQueue.first().seqnumOrNull ?: Int.MAX_VALUE) <= respSeqnum) {
                outboundQueue.removeFirst()
            }
        }

    /** Resends every not-yet-acked OPEN/DATA/CLOSE, in order, on a freshly selected connection. */
    private suspend fun resendUnretiredQueue() =
        outboundMutex.withLock {
            for (record in outboundQueue) connection.sendRecord(record)
        }

    /**
     * Runs until the connection fails in either direction. A record can be read successfully and
     * then fail to ACK (the peer closed its write side but ours isn't dead yet, or vice versa) —
     * that must end the loop exactly like a failed read, so the whole iteration body is guarded,
     * not just [DilationConnection.receiveRecord].
     */
    private suspend fun readLoop() {
        while (true) {
            try {
                readOneRecord()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
        }
    }

    private suspend fun readOneRecord() {
        val record = connection.receiveRecord()
        trafficSeen = true
        when (record) {
            is DilationRecord.Ack -> retireAcked(record.respSeqnum)

            is DilationRecord.Ping -> writeUnqueued(DilationRecord.Pong(record.pingId))

            is DilationRecord.Pong -> Unit

            is DilationRecord.Kcm -> Unit

            // shouldn't happen post-selection; ignore
            is DilationRecord.Open, is DilationRecord.Data, is DilationRecord.Close -> handleInbound(record)
        }
    }

    private suspend fun handleInbound(record: DilationRecord) {
        val scid = requireNotNull(record.scidOrNull)
        val seqnum = requireNotNull(record.seqnumOrNull)
        writeUnqueued(DilationRecord.Ack(seqnum)) // every OPEN/DATA/CLOSE is ACKed, even if stale
        if (seqnum <= highestInboundAcked) return // already delivered; drop the duplicate
        highestInboundAcked = maxOf(highestInboundAcked, seqnum)
        when (record) {
            is DilationRecord.Open -> inboundEvents.send(DilationInboundEvent.Opened(scid, record.subprotocol))
            is DilationRecord.Data -> inboundEvents.send(DilationInboundEvent.DataReceived(scid, record.payload))
            is DilationRecord.Close -> inboundEvents.send(DilationInboundEvent.Closed(scid))
            else -> Unit
        }
    }
}
