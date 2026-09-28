// Dilation's mailbox-level version/role negotiation, following magic-wormhole's
// _dilation/manager.py and _dilation/roles.py (MIT). No L2 connection is made here yet.
package uno.lux.wormhole.dilation

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uno.lux.wormhole.DILATION_KEY_PURPOSE
import uno.lux.wormhole.DILATION_VERSION
import uno.lux.wormhole.WormholeProtocolException
import uno.lux.wormhole.WormholeSession
import uno.lux.wormhole.crypto.randomBytes

internal enum class DilationRole { LEADER, FOLLOWER }

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
}
