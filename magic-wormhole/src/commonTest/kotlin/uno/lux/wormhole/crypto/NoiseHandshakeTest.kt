package uno.lux.wormhole.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NoiseHandshakeTest {
    @Test
    fun matchesIndependentPythonImplementation() {
        for (v in NoiseHandshakeVectors.all) {
            val initiator =
                NoiseHandshake(NoiseRole.INITIATOR, v.psk.hexToByteArray()) {
                    v.initiatorEphemeralPrivate.hexToByteArray()
                }
            val responder =
                NoiseHandshake(NoiseRole.RESPONDER, v.psk.hexToByteArray()) {
                    v.responderEphemeralPrivate.hexToByteArray()
                }

            val message1 = initiator.writeMessage()
            assertContentEquals(v.message1.hexToByteArray(), message1, "${v.label} message1")
            responder.readMessage(message1)

            val message2 = responder.writeMessage()
            assertContentEquals(v.message2.hexToByteArray(), message2, "${v.label} message2")
            initiator.readMessage(message2)

            assertTrue(initiator.isComplete)
            assertTrue(responder.isComplete)
            assertEquals(v.handshakeHash, initiator.handshakeHash().toHexString(), "${v.label} handshake hash")
            assertEquals(v.handshakeHash, responder.handshakeHash().toHexString(), "${v.label} handshake hash")

            val (initiatorSend, _) = initiator.split()
            val (_, responderReceive) = responder.split()

            for (i in v.transportPlaintexts.indices) {
                val plaintext = v.transportPlaintexts[i].hexToByteArray()
                val expectedCiphertext = v.transportCiphertexts[i].hexToByteArray()
                val ciphertext = initiatorSend.encrypt(ByteArray(0), plaintext)
                assertContentEquals(expectedCiphertext, ciphertext, "${v.label} transport[$i]")
                assertContentEquals(
                    plaintext,
                    responderReceive.decrypt(ByteArray(0), ciphertext),
                    "${v.label} transport[$i]",
                )
            }
        }
    }

    @Test
    fun wrongPskFailsHandshake() {
        val psk = randomBytes(32)
        val wrongPsk = randomBytes(32)
        val initiator = NoiseHandshake(NoiseRole.INITIATOR, psk)
        val responder = NoiseHandshake(NoiseRole.RESPONDER, wrongPsk)

        val message1 = initiator.writeMessage()
        assertFailsWith<DecryptionException> { responder.readMessage(message1) }
    }

    @Test
    fun fullHandshakeWithRandomKeysAgreesOnTransportKeys() {
        val psk = randomBytes(32)
        val initiator = NoiseHandshake(NoiseRole.INITIATOR, psk)
        val responder = NoiseHandshake(NoiseRole.RESPONDER, psk)

        responder.readMessage(initiator.writeMessage())
        initiator.readMessage(responder.writeMessage())

        val (initiatorSend, initiatorReceive) = initiator.split()
        val (responderSend, responderReceive) = responder.split()

        val fromInitiator = initiatorSend.encrypt(ByteArray(0), "hello".encodeToByteArray())
        assertContentEquals("hello".encodeToByteArray(), responderReceive.decrypt(ByteArray(0), fromInitiator))

        val fromResponder = responderSend.encrypt(ByteArray(0), "world".encodeToByteArray())
        assertContentEquals("world".encodeToByteArray(), initiatorReceive.decrypt(ByteArray(0), fromResponder))
    }
}
