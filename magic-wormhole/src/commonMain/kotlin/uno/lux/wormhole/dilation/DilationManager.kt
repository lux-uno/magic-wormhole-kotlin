// Dilation's mailbox-level version/role negotiation and, once a connection is selected, the
// reliable multiplexed record layer on top of it: following magic-wormhole's
// _dilation/manager.py, _dilation/roles.py, _dilation/inbound.py and _dilation/outbound.py (MIT).
// Reconnection (the rest of manager.py's state machine) is not implemented yet.
package uno.lux.wormhole.dilation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uno.lux.wormhole.DILATION_KEY_PURPOSE
import uno.lux.wormhole.DILATION_VERSION
import uno.lux.wormhole.WormholeProtocolException
import uno.lux.wormhole.WormholeSession
import uno.lux.wormhole.crypto.randomBytes
import uno.lux.wormhole.transit.TransitNetwork

internal enum class DilationRole { LEADER, FOLLOWER }

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

/**
 * Negotiates Dilation's leader/follower role over the mailbox, following the `please` exchange
 * in magic-wormhole's `Manager`/`Dilator`. [side] is a Dilation-specific random value, independent
 * of the mailbox's own side.
 */
internal class DilationManager(
    private val session: WormholeSession,
    private val side: String = randomBytes(8).toHexString(),
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

    private lateinit var connection: DilationConnection
    private var nextSubchannelId = 0
    private var nextOutboundSeqnum = 0
    private var highestInboundAcked = -1
    private val outboundQueue = ArrayDeque<DilationRecord>()
    private val outboundMutex = Mutex()
    private val inboundEvents = Channel<DilationInboundEvent>(Channel.UNLIMITED)

    /** OPEN/DATA/CLOSE events from the peer, in order, deduplicated. */
    val events: ReceiveChannel<DilationInboundEvent> get() = inboundEvents

    /**
     * Exchanges connection hints (`dilate-1`) and races an L2 connection over [network], following
     * [DilationConnector]. [role] must already be known (call [start] first). Once connected, starts
     * a background reader (launched in [scope]) that ACKs and delivers inbound records and retires
     * acknowledged outbound ones; [openSubchannel]/[sendData]/[closeSubchannel] send new ones.
     */
    suspend fun connect(
        network: TransitNetwork,
        relay: DilationHint.Direct?,
        scope: CoroutineScope,
    ) {
        val connector = DilationConnector(role, dilationKey, side, network, relay)
        val hints = connector.start()
        session.sendDilation(connectionHintsMessage(hints))
        val peerHints = parseConnectionHintsMessage(session.receiveDilation())
        connection = connector.connect(peerHints)
        nextSubchannelId = if (role == DilationRole.LEADER) 1 else 2
        scope.launch { readLoop() }
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

    private suspend fun readLoop() {
        while (true) {
            val record =
                try {
                    connection.receiveRecord()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    break
                }
            when (record) {
                is DilationRecord.Ack -> retireAcked(record.respSeqnum)

                is DilationRecord.Ping -> writeUnqueued(DilationRecord.Pong(record.pingId))

                is DilationRecord.Pong -> Unit

                // no liveness timer yet
                is DilationRecord.Kcm -> Unit

                // shouldn't happen post-selection; ignore
                is DilationRecord.Open, is DilationRecord.Data, is DilationRecord.Close -> handleInbound(record)
            }
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
