// Key exchange and encrypted phase messages, following magic-wormhole's _key.py and _receive.py
// (MIT) and wormhole-william's wormhole.go (MIT).
package uno.lux.wormhole

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import uno.lux.wormhole.crypto.DecryptionException
import uno.lux.wormhole.crypto.SecretBox
import uno.lux.wormhole.crypto.Spake2Symmetric
import uno.lux.wormhole.crypto.hkdfSha256
import uno.lux.wormhole.crypto.randomBytes
import uno.lux.wormhole.crypto.sha256
import uno.lux.wormhole.rendezvous.RendezvousClient

/**
 * The encrypted conversation between two sides on an open mailbox.
 * Call [exchangeKeys] first; then [send] and [receive] JSON messages in numbered phases.
 */
internal class WormholeSession(
    private val client: RendezvousClient,
    private val appId: String,
) {
    private var key: ByteArray? = null
    private var nextOutboundPhase = 0
    private var nextInboundPhase = 0
    private val buffered = mutableMapOf<String, ByteArray>()

    /** Runs SPAKE2 over the "pake" phase and verifies the key with the "version" phase. */
    suspend fun exchangeKeys(code: String) {
        val spake = Spake2Symmetric(code.encodeToByteArray(), appId.encodeToByteArray())
        val pakeBody = buildJsonObject { put("pake_v1", spake.start().toHexString()) }
        client.add("pake", pakeBody.toString().encodeToByteArray())
        key = finishPake(spake, receivePeerPake())
        exchangeVersions()
    }

    private suspend fun receivePeerPake(): ByteArray =
        try {
            val payload = parseJson(receivePhase("pake"))
            (payload["pake_v1"] as JsonPrimitive).content.hexToByteArray()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw WormholeProtocolException("Malformed PAKE message from the other side", e)
        }

    private fun finishPake(
        spake: Spake2Symmetric,
        peerPake: ByteArray,
    ): ByteArray =
        try {
            spake.finish(peerPake)
        } catch (e: IllegalArgumentException) {
            throw WormholeProtocolException("Invalid PAKE message from the other side", e)
        }

    /** Both sides send an encrypted "version" message. If the peer's does not decrypt, the codes differ. */
    private suspend fun exchangeVersions() {
        val versions = buildJsonObject { put("app_versions", buildJsonObject { }) }
        client.add("version", encrypt(client.side, "version", versions.toString().encodeToByteArray()))

        val (peerSide, peerVersion) = receiveMessage("version")
        try {
            decrypt(peerSide, "version", peerVersion)
        } catch (e: DecryptionException) {
            throw WrongCodeException()
        }
    }

    fun deriveKey(
        purpose: String,
        length: Int = SecretBox.KEY_SIZE,
    ): ByteArray = hkdfSha256(requireKey(), ByteArray(0), purpose.encodeToByteArray(), length)

    internal fun keyForTests(): ByteArray = requireKey()

    /** Encrypts and sends [message] as the next numbered phase. */
    suspend fun send(message: JsonObject) {
        val phase = (nextOutboundPhase++).toString()
        client.add(phase, encrypt(client.side, phase, message.toString().encodeToByteArray()))
    }

    /** Receives and decrypts the next numbered phase from the other side. */
    suspend fun receive(): JsonObject {
        val phase = (nextInboundPhase++).toString()
        val (side, body) = receiveMessage(phase)
        val plaintext =
            try {
                decrypt(side, phase, body)
            } catch (e: DecryptionException) {
                throw WormholeProtocolException("Could not decrypt message $phase from the other side", e)
            }
        return parseJson(plaintext)
    }

    private val sides = mutableMapOf<String, String>()

    private suspend fun receivePhase(phase: String): ByteArray = receiveMessage(phase).second

    /** Returns (side, body) of the peer's message for [phase], buffering messages of other phases. */
    private suspend fun receiveMessage(phase: String): Pair<String, ByteArray> {
        while (phase !in buffered) {
            val m = client.receive()
            if (m.phase !in buffered) {
                buffered[m.phase] = m.body
                sides[m.phase] = m.side
            }
        }
        return sides.remove(phase)!! to buffered.remove(phase)!!
    }

    private fun phaseKey(
        side: String,
        phase: String,
    ): ByteArray {
        val purpose =
            "wormhole:phase:".encodeToByteArray() +
                sha256(side.encodeToByteArray()) + sha256(phase.encodeToByteArray())
        return hkdfSha256(requireKey(), ByteArray(0), purpose, SecretBox.KEY_SIZE)
    }

    private fun encrypt(
        side: String,
        phase: String,
        plaintext: ByteArray,
    ): ByteArray {
        val nonce = randomBytes(SecretBox.NONCE_SIZE)
        return nonce + SecretBox.seal(plaintext, nonce, phaseKey(side, phase))
    }

    private fun decrypt(
        side: String,
        phase: String,
        body: ByteArray,
    ): ByteArray {
        if (body.size < SecretBox.NONCE_SIZE + SecretBox.MAC_SIZE) throw DecryptionException("Message too short")
        return SecretBox.open(
            body.copyOfRange(SecretBox.NONCE_SIZE, body.size),
            body.copyOfRange(0, SecretBox.NONCE_SIZE),
            phaseKey(side, phase),
        )
    }

    private fun requireKey(): ByteArray = checkNotNull(key) { "Keys have not been exchanged yet" }

    private fun parseJson(bytes: ByteArray): JsonObject =
        try {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (e: SerializationException) {
            throw WormholeProtocolException("Malformed message from the other side", e)
        } catch (e: IllegalArgumentException) {
            throw WormholeProtocolException("Malformed message from the other side", e)
        }
}
