// XSalsa20 and the secretbox construction are ported from TweetNaCl (public domain).
// Poly1305 follows the 26-bit limb layout of poly1305-donna (public domain).
package uno.lux.wormhole.crypto

/** Thrown when a secretbox fails authentication. */
internal class DecryptionException(
    message: String = "Decryption failed",
) : Exception(message)

/** NaCl `crypto_secretbox` (XSalsa20-Poly1305). A box is `tag(16) || ciphertext`. */
internal object SecretBox {
    const val KEY_SIZE: Int = 32
    const val NONCE_SIZE: Int = 24
    const val MAC_SIZE: Int = 16

    fun seal(
        message: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray {
        checkSizes(nonce, key)
        val out = ByteArray(MAC_SIZE + message.size)
        val polyKey = xsalsa20Xor(message, out, MAC_SIZE, nonce, key)
        val tag = Poly1305.mac(out, MAC_SIZE, message.size, polyKey)
        tag.copyInto(out, 0)
        return out
    }

    fun open(
        box: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray {
        checkSizes(nonce, key)
        if (box.size < MAC_SIZE) throw DecryptionException("Box too short")
        val stream = XSalsa20(nonce, key)
        val polyKey = ByteArray(32)
        stream.xor(ByteArray(32), 0, polyKey, 0, 32)
        val expected = Poly1305.mac(box, MAC_SIZE, box.size - MAC_SIZE, polyKey)
        if (!constantTimeEquals(expected, box, MAC_SIZE)) throw DecryptionException()
        val out = ByteArray(box.size - MAC_SIZE)
        stream.xor(box, MAC_SIZE, out, 0, out.size)
        return out
    }

    /** Encrypts [message] into [out] at [outOffset] and returns the one-time Poly1305 key. */
    private fun xsalsa20Xor(
        message: ByteArray,
        out: ByteArray,
        outOffset: Int,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val stream = XSalsa20(nonce, key)
        val polyKey = ByteArray(32)
        stream.xor(ByteArray(32), 0, polyKey, 0, 32)
        stream.xor(message, 0, out, outOffset, message.size)
        return polyKey
    }

    private fun checkSizes(
        nonce: ByteArray,
        key: ByteArray,
    ) {
        require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes" }
        require(key.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes" }
    }

    private fun constantTimeEquals(
        tag: ByteArray,
        box: ByteArray,
        length: Int,
    ): Boolean {
        var diff = 0
        for (i in 0 until length) diff = diff or (tag[i].toInt() xor box[i].toInt())
        return diff == 0
    }
}

/** An XSalsa20 key stream. Consecutive [xor] calls continue the stream. */
internal class XSalsa20(
    nonce: ByteArray,
    key: ByteArray,
) {
    private val state = IntArray(16)
    private val block = ByteArray(64)
    private var blockPos = 64

    init {
        val subKey = Salsa20.hsalsa20(nonce, key)
        Salsa20.initState(state, subKey, nonce, nonceOffset = 16)
    }

    fun xor(
        input: ByteArray,
        inOffset: Int,
        output: ByteArray,
        outOffset: Int,
        length: Int,
    ) {
        var i = 0
        while (i < length) {
            if (blockPos == 64) {
                Salsa20.block(state, block)
                // 64-bit little-endian block counter in words 8 and 9.
                state[8]++
                if (state[8] == 0) state[9]++
                blockPos = 0
            }
            val n = minOf(64 - blockPos, length - i)
            for (j in 0 until n) {
                output[outOffset + i + j] = (input[inOffset + i + j].toInt() xor block[blockPos + j].toInt()).toByte()
            }
            blockPos += n
            i += n
        }
    }
}

internal object Salsa20 {
    private const val SIGMA0 = 0x61707865
    private const val SIGMA1 = 0x3320646e
    private const val SIGMA2 = 0x79622d32
    private const val SIGMA3 = 0x6b206574

    fun initState(
        state: IntArray,
        key: ByteArray,
        nonce: ByteArray,
        nonceOffset: Int,
    ) {
        state[0] = SIGMA0
        for (i in 0 until 4) state[1 + i] = le32(key, i * 4)
        state[5] = SIGMA1
        state[6] = le32(nonce, nonceOffset)
        state[7] = le32(nonce, nonceOffset + 4)
        state[8] = 0
        state[9] = 0
        state[10] = SIGMA2
        for (i in 0 until 4) state[11 + i] = le32(key, 16 + i * 4)
        state[15] = SIGMA3
    }

    /** HSalsa20 on the first 16 bytes of [nonce]. Returns a 32-byte subkey. */
    fun hsalsa20(
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val x = IntArray(16)
        x[0] = SIGMA0
        for (i in 0 until 4) x[1 + i] = le32(key, i * 4)
        x[5] = SIGMA1
        for (i in 0 until 4) x[6 + i] = le32(nonce, i * 4)
        x[10] = SIGMA2
        for (i in 0 until 4) x[11 + i] = le32(key, 16 + i * 4)
        x[15] = SIGMA3
        rounds(x)
        val out = ByteArray(32)
        intArrayOf(x[0], x[5], x[10], x[15], x[6], x[7], x[8], x[9]).forEachIndexed { i, w -> putLe32(out, i * 4, w) }
        return out
    }

    /** Writes one 64-byte Salsa20 block for [state] into [out]. */
    fun block(
        state: IntArray,
        out: ByteArray,
    ) {
        val x = state.copyOf()
        rounds(x)
        for (i in 0 until 16) putLe32(out, i * 4, x[i] + state[i])
    }

    private fun rounds(x: IntArray) {
        var x0 = x[0]
        var x1 = x[1]
        var x2 = x[2]
        var x3 = x[3]
        var x4 = x[4]
        var x5 = x[5]
        var x6 = x[6]
        var x7 = x[7]
        var x8 = x[8]
        var x9 = x[9]
        var x10 = x[10]
        var x11 = x[11]
        var x12 = x[12]
        var x13 = x[13]
        var x14 = x[14]
        var x15 = x[15]
        repeat(10) {
            x4 = x4 xor (x0 + x12).rotateLeft(7)
            x8 = x8 xor (x4 + x0).rotateLeft(9)
            x12 = x12 xor (x8 + x4).rotateLeft(13)
            x0 = x0 xor (x12 + x8).rotateLeft(18)
            x9 = x9 xor (x5 + x1).rotateLeft(7)
            x13 = x13 xor (x9 + x5).rotateLeft(9)
            x1 = x1 xor (x13 + x9).rotateLeft(13)
            x5 = x5 xor (x1 + x13).rotateLeft(18)
            x14 = x14 xor (x10 + x6).rotateLeft(7)
            x2 = x2 xor (x14 + x10).rotateLeft(9)
            x6 = x6 xor (x2 + x14).rotateLeft(13)
            x10 = x10 xor (x6 + x2).rotateLeft(18)
            x3 = x3 xor (x15 + x11).rotateLeft(7)
            x7 = x7 xor (x3 + x15).rotateLeft(9)
            x11 = x11 xor (x7 + x3).rotateLeft(13)
            x15 = x15 xor (x11 + x7).rotateLeft(18)

            x1 = x1 xor (x0 + x3).rotateLeft(7)
            x2 = x2 xor (x1 + x0).rotateLeft(9)
            x3 = x3 xor (x2 + x1).rotateLeft(13)
            x0 = x0 xor (x3 + x2).rotateLeft(18)
            x6 = x6 xor (x5 + x4).rotateLeft(7)
            x7 = x7 xor (x6 + x5).rotateLeft(9)
            x4 = x4 xor (x7 + x6).rotateLeft(13)
            x5 = x5 xor (x4 + x7).rotateLeft(18)
            x11 = x11 xor (x10 + x9).rotateLeft(7)
            x8 = x8 xor (x11 + x10).rotateLeft(9)
            x9 = x9 xor (x8 + x11).rotateLeft(13)
            x10 = x10 xor (x9 + x8).rotateLeft(18)
            x12 = x12 xor (x15 + x14).rotateLeft(7)
            x13 = x13 xor (x12 + x15).rotateLeft(9)
            x14 = x14 xor (x13 + x12).rotateLeft(13)
            x15 = x15 xor (x14 + x13).rotateLeft(18)
        }
        x[0] = x0
        x[1] = x1
        x[2] = x2
        x[3] = x3
        x[4] = x4
        x[5] = x5
        x[6] = x6
        x[7] = x7
        x[8] = x8
        x[9] = x9
        x[10] = x10
        x[11] = x11
        x[12] = x12
        x[13] = x13
        x[14] = x14
        x[15] = x15
    }
}

internal object Poly1305 {
    private const val MASK26 = 0x3ffffffL

    /** Computes the Poly1305 tag of `data[offset until offset + length]` with a 32-byte one-time [key]. */
    fun mac(
        data: ByteArray,
        offset: Int,
        length: Int,
        key: ByteArray,
    ): ByteArray {
        val r0 = le32u(key, 0) and 0x3ffffffL
        val r1 = (le32u(key, 3) ushr 2) and 0x3ffff03L
        val r2 = (le32u(key, 6) ushr 4) and 0x3ffc0ffL
        val r3 = (le32u(key, 9) ushr 6) and 0x3f03fffL
        val r4 = (le32u(key, 12) ushr 8) and 0x00fffffL
        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5

        var h0 = 0L
        var h1 = 0L
        var h2 = 0L
        var h3 = 0L
        var h4 = 0L
        val tail = ByteArray(16)
        var pos = 0
        while (pos < length) {
            val remaining = length - pos
            val buf: ByteArray
            val bufOffset: Int
            val hiBit: Long
            if (remaining >= 16) {
                buf = data
                bufOffset = offset + pos
                hiBit = 1L shl 24
            } else {
                tail.fill(0)
                data.copyInto(tail, 0, offset + pos, offset + length)
                tail[remaining] = 1
                buf = tail
                bufOffset = 0
                hiBit = 0L
            }
            h0 += le32u(buf, bufOffset) and MASK26
            h1 += (le32u(buf, bufOffset + 3) ushr 2) and MASK26
            h2 += (le32u(buf, bufOffset + 6) ushr 4) and MASK26
            h3 += (le32u(buf, bufOffset + 9) ushr 6) and MASK26
            h4 += (le32u(buf, bufOffset + 12) ushr 8) or hiBit

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            var c = d0 ushr 26
            h0 = d0 and MASK26
            d1 += c
            c = d1 ushr 26
            h1 = d1 and MASK26
            d2 += c
            c = d2 ushr 26
            h2 = d2 and MASK26
            d3 += c
            c = d3 ushr 26
            h3 = d3 and MASK26
            d4 += c
            c = d4 ushr 26
            h4 = d4 and MASK26
            h0 += c * 5
            c = h0 ushr 26
            h0 = h0 and MASK26
            h1 += c
            pos += 16
        }

        var c = h1 ushr 26
        h1 = h1 and MASK26
        h2 += c
        c = h2 ushr 26
        h2 = h2 and MASK26
        h3 += c
        c = h3 ushr 26
        h3 = h3 and MASK26
        h4 += c
        c = h4 ushr 26
        h4 = h4 and MASK26
        h0 += c * 5
        c = h0 ushr 26
        h0 = h0 and MASK26
        h1 += c

        // g = h - p. Use g when h >= p.
        var g0 = h0 + 5
        c = g0 ushr 26
        g0 = g0 and MASK26
        var g1 = h1 + c
        c = g1 ushr 26
        g1 = g1 and MASK26
        var g2 = h2 + c
        c = g2 ushr 26
        g2 = g2 and MASK26
        var g3 = h3 + c
        c = g3 ushr 26
        g3 = g3 and MASK26
        val g4 = h4 + c - (1L shl 26)
        val keepH = g4 shr 63 // -1 when g4 < 0 (h < p), else 0
        h0 = (h0 and keepH) or (g0 and keepH.inv())
        h1 = (h1 and keepH) or (g1 and keepH.inv())
        h2 = (h2 and keepH) or (g2 and keepH.inv())
        h3 = (h3 and keepH) or (g3 and keepH.inv())
        h4 = (h4 and keepH) or (g4 and keepH.inv())

        val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL

        val out = ByteArray(16)
        var f = w0 + le32u(key, 16)
        putLe32(out, 0, f.toInt())
        f = w1 + le32u(key, 20) + (f ushr 32)
        putLe32(out, 4, f.toInt())
        f = w2 + le32u(key, 24) + (f ushr 32)
        putLe32(out, 8, f.toInt())
        f = w3 + le32u(key, 28) + (f ushr 32)
        putLe32(out, 12, f.toInt())
        return out
    }
}

internal fun le32(
    b: ByteArray,
    offset: Int,
): Int =
    (b[offset].toInt() and 0xff) or
        ((b[offset + 1].toInt() and 0xff) shl 8) or
        ((b[offset + 2].toInt() and 0xff) shl 16) or
        ((b[offset + 3].toInt() and 0xff) shl 24)

private fun le32u(
    b: ByteArray,
    offset: Int,
): Long = le32(b, offset).toLong() and 0xffffffffL

internal fun putLe32(
    b: ByteArray,
    offset: Int,
    value: Int,
) {
    b[offset] = value.toByte()
    b[offset + 1] = (value ushr 8).toByte()
    b[offset + 2] = (value ushr 16).toByte()
    b[offset + 3] = (value ushr 24).toByte()
}
