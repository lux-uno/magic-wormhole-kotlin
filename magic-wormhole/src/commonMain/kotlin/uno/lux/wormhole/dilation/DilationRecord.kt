// Dilation's per-message record types and their wire encoding, following magic-wormhole's
// _dilation/connection.py and encode.py (MIT). Field layout is `tag(1) [scid(4-BE) seqnum(4-BE)]
// [payload]`; scid/seqnum are big-endian, matching Python's `struct.pack(">L", n)`.
package uno.lux.wormhole.dilation

import uno.lux.wormhole.WormholeProtocolException

internal sealed interface DilationRecord {
    data object Kcm : DilationRecord

    data class Ping(
        val pingId: Int,
    ) : DilationRecord

    data class Pong(
        val pingId: Int,
    ) : DilationRecord

    data class Open(
        val scid: Int,
        val seqnum: Int,
        val subprotocol: String,
    ) : DilationRecord

    data class Data(
        val scid: Int,
        val seqnum: Int,
        val payload: ByteArray,
    ) : DilationRecord

    data class Close(
        val scid: Int,
        val seqnum: Int,
    ) : DilationRecord

    data class Ack(
        val respSeqnum: Int,
    ) : DilationRecord
}

/** The seqnum of an OPEN/DATA/CLOSE record, or null for the record types that don't carry one. */
internal val DilationRecord.seqnumOrNull: Int?
    get() =
        when (this) {
            is DilationRecord.Open -> seqnum
            is DilationRecord.Data -> seqnum
            is DilationRecord.Close -> seqnum
            else -> null
        }

/** The subchannel id of an OPEN/DATA/CLOSE record, or null for the record types that don't carry one. */
internal val DilationRecord.scidOrNull: Int?
    get() =
        when (this) {
            is DilationRecord.Open -> scid
            is DilationRecord.Data -> scid
            is DilationRecord.Close -> scid
            else -> null
        }

internal object DilationRecordCodec {
    private const val TAG_KCM: Byte = 0x00
    private const val TAG_PING: Byte = 0x01
    private const val TAG_PONG: Byte = 0x02
    private const val TAG_OPEN: Byte = 0x03
    private const val TAG_DATA: Byte = 0x04
    private const val TAG_CLOSE: Byte = 0x05
    private const val TAG_ACK: Byte = 0x06

    fun encode(record: DilationRecord): ByteArray =
        when (record) {
            is DilationRecord.Kcm -> {
                byteArrayOf(TAG_KCM)
            }

            is DilationRecord.Ping -> {
                fixedLength(TAG_PING, 5) { putBe32(it, 1, record.pingId) }
            }

            is DilationRecord.Pong -> {
                fixedLength(TAG_PONG, 5) { putBe32(it, 1, record.pingId) }
            }

            is DilationRecord.Ack -> {
                fixedLength(TAG_ACK, 5) { putBe32(it, 1, record.respSeqnum) }
            }

            is DilationRecord.Close -> {
                fixedLength(TAG_CLOSE, 9) {
                    putBe32(it, 1, record.scid)
                    putBe32(it, 5, record.seqnum)
                }
            }

            is DilationRecord.Open -> {
                val subprotocol = record.subprotocol.encodeToByteArray()
                ByteArray(9 + subprotocol.size).also {
                    it[0] = TAG_OPEN
                    putBe32(it, 1, record.scid)
                    putBe32(it, 5, record.seqnum)
                    subprotocol.copyInto(it, 9)
                }
            }

            is DilationRecord.Data -> {
                ByteArray(9 + record.payload.size).also {
                    it[0] = TAG_DATA
                    putBe32(it, 1, record.scid)
                    putBe32(it, 5, record.seqnum)
                    record.payload.copyInto(it, 9)
                }
            }
        }

    fun decode(bytes: ByteArray): DilationRecord {
        if (bytes.isEmpty()) throw WormholeProtocolException("Empty Dilation record")
        return when (bytes[0]) {
            TAG_KCM -> {
                DilationRecord.Kcm
            }

            TAG_PING -> {
                requireLength(bytes, 5)
                DilationRecord.Ping(be32(bytes, 1))
            }

            TAG_PONG -> {
                requireLength(bytes, 5)
                DilationRecord.Pong(be32(bytes, 1))
            }

            TAG_ACK -> {
                requireLength(bytes, 5)
                DilationRecord.Ack(be32(bytes, 1))
            }

            TAG_CLOSE -> {
                requireLength(bytes, 9)
                DilationRecord.Close(be32(bytes, 1), be32(bytes, 5))
            }

            TAG_OPEN -> {
                requireMinLength(bytes, 9)
                DilationRecord.Open(be32(bytes, 1), be32(bytes, 5), bytes.copyOfRange(9, bytes.size).decodeToString())
            }

            TAG_DATA -> {
                requireMinLength(bytes, 9)
                DilationRecord.Data(be32(bytes, 1), be32(bytes, 5), bytes.copyOfRange(9, bytes.size))
            }

            else -> {
                throw WormholeProtocolException("Unknown Dilation record tag ${bytes[0]}")
            }
        }
    }

    private inline fun fixedLength(
        tag: Byte,
        length: Int,
        fill: (ByteArray) -> Unit,
    ): ByteArray {
        val out = ByteArray(length)
        out[0] = tag
        fill(out)
        return out
    }

    private fun requireLength(
        bytes: ByteArray,
        length: Int,
    ) {
        if (bytes.size != length) throw WormholeProtocolException("Malformed Dilation record (tag ${bytes[0]})")
    }

    private fun requireMinLength(
        bytes: ByteArray,
        length: Int,
    ) {
        if (bytes.size < length) throw WormholeProtocolException("Malformed Dilation record (tag ${bytes[0]})")
    }
}

private fun putBe32(
    bytes: ByteArray,
    offset: Int,
    value: Int,
) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

private fun be32(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)
