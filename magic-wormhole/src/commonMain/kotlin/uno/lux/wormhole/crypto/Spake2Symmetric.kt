// Ported from python-spake2 (MIT, Copyright (c) 2015 Brian Warner): SPAKE2_Symmetric with the
// Ed25519 group, as used by magic-wormhole.
package uno.lux.wormhole.crypto

/**
 * One side of a symmetric SPAKE2 exchange. Call [start] once, send its result to the peer, then
 * call [finish] once with the peer's message to get the 32-byte shared key.
 */
internal class Spake2Symmetric(
    private val password: ByteArray,
    private val idSymmetric: ByteArray,
    private val random: (Int) -> ByteArray = ::randomBytes,
) {
    private val passwordScalar = Scalar.fromPassword(password)
    private var xyScalar: Scalar? = null
    private var outbound: ByteArray? = null
    private var finished = false

    fun start(): ByteArray {
        check(xyScalar == null) { "start() can only be called once" }
        val xy = Scalar.random(random)
        xyScalar = xy
        val message = Ed25519.BASE.times(xy) + S.times(passwordScalar)
        val encoded = message.encode()
        outbound = encoded
        return byteArrayOf(SIDE_SYMMETRIC) + encoded
    }

    fun finish(inboundSideAndMessage: ByteArray): ByteArray {
        val xy = checkNotNull(xyScalar) { "start() must be called first" }
        val out = outbound!!
        check(!finished) { "finish() can only be called once" }
        finished = true

        require(inboundSideAndMessage.size == 33) { "SPAKE2 message must be 33 bytes" }
        require(inboundSideAndMessage[0] == SIDE_SYMMETRIC) { "SPAKE2 message is not from a symmetric side" }
        val inbound = inboundSideAndMessage.copyOfRange(1, 33)
        val inboundElement = Ed25519.decodeElement(inbound)
        require(!inboundElement.encode().contentEquals(out)) { "SPAKE2 reflection detected" }

        val k = (inboundElement + S.times(passwordScalar.negate())).times(xy)
        val (first, second) = sortedMessages(inbound, out)
        val transcript = sha256(password) + sha256(idSymmetric) + first + second + k.encode()
        return sha256(transcript)
    }

    private fun sortedMessages(
        a: ByteArray,
        b: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        for (i in a.indices) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return if (x < y) a to b else b to a
        }
        return a to b
    }

    private companion object {
        const val SIDE_SYMMETRIC: Byte = 'S'.code.toByte()
        val S: EdPoint by lazy { Ed25519.arbitraryElement("symmetric".encodeToByteArray()) }
    }
}
