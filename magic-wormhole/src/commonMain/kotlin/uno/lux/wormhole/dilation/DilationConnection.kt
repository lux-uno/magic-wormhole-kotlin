// The L2 wire protocol for one Dilation TCP connection: 4-byte-length-prefixed frames of Noise
// handshake messages, then Noise-encrypted records. Follows magic-wormhole's
// _dilation/connection.py and _dilation/encode.py (MIT).
package uno.lux.wormhole.dilation

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import uno.lux.wormhole.WormholeProtocolException
import uno.lux.wormhole.crypto.NoiseCipherState
import uno.lux.wormhole.crypto.NoiseHandshake
import uno.lux.wormhole.transit.TransitSocket

private const val MAX_FRAME_LENGTH = 64 * 1024 * 1024

/** Reads/writes the `length(4, big-endian) || payload` frames every Dilation message rides in. */
internal object DilationFramer {
    suspend fun readFrame(input: ByteReadChannel): ByteArray {
        val length = input.readInt()
        if (length < 0 || length > MAX_FRAME_LENGTH) {
            throw WormholeProtocolException("Invalid Dilation frame length $length")
        }
        return input.readByteArray(length)
    }

    suspend fun writeFrame(
        output: ByteWriteChannel,
        frame: ByteArray,
    ) {
        output.writeInt(frame.size)
        output.writeFully(frame)
        output.flush()
    }
}

/**
 * One negotiated L2 connection: a [TransitSocket] plus the Noise transport ciphers derived from
 * its handshake. Built by [DilationConnector]; [DilationConnector] alone decides which of possibly
 * several such connections wins and drives the KCM exchange during selection. Once selected,
 * [DilationManager] uses [sendRecord]/[receiveRecord] for the multiplexed traffic.
 */
internal class DilationConnection(
    private val socket: TransitSocket,
    private val handshake: NoiseHandshake,
) {
    private lateinit var sendCipher: NoiseCipherState
    private lateinit var receiveCipher: NoiseCipherState

    suspend fun writeHandshakeMessage() = DilationFramer.writeFrame(socket.output, handshake.writeMessage())

    suspend fun readHandshakeMessage() = handshake.readMessage(DilationFramer.readFrame(socket.input))

    /** Derives the transport ciphers once [handshake] has exchanged both messages. */
    fun completeHandshake() {
        val (send, receive) = handshake.split()
        sendCipher = send
        receiveCipher = receive
    }

    suspend fun sendRecord(record: DilationRecord) {
        val ciphertext = sendCipher.encrypt(ByteArray(0), DilationRecordCodec.encode(record))
        DilationFramer.writeFrame(socket.output, ciphertext)
    }

    suspend fun receiveRecord(): DilationRecord {
        val plaintext = receiveCipher.decrypt(ByteArray(0), DilationFramer.readFrame(socket.input))
        return DilationRecordCodec.decode(plaintext)
    }

    suspend fun sendKcm() = sendRecord(DilationRecord.Kcm)

    /** Blocks until a KCM arrives on this connection, or throws if something else does. */
    suspend fun awaitKcm() {
        val record = receiveRecord()
        if (record !is DilationRecord.Kcm) throw WormholeProtocolException("Expected a Dilation KCM, got $record")
    }

    fun close() = socket.close()
}
