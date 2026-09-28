// ChaCha20 and the ChaCha20-Poly1305 AEAD construction, per RFC 8439. Reuses the Poly1305
// one-time-MAC primitive already implemented in SecretBox.kt.
package uno.lux.wormhole.crypto

/** ChaCha20 (RFC 8439 section 2). */
internal object ChaCha20 {
    const val KEY_SIZE: Int = 32
    const val NONCE_SIZE: Int = 12

    private const val SIGMA0 = 0x61707865
    private const val SIGMA1 = 0x3320646e
    private const val SIGMA2 = 0x79622d32
    private const val SIGMA3 = 0x6b206574

    /** One 64-byte keystream block for [key]/[nonce] at the given block [counter]. */
    fun block(
        key: ByteArray,
        counter: Int,
        nonce: ByteArray,
    ): ByteArray {
        require(key.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes" }
        require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes" }
        val state = IntArray(16)
        state[0] = SIGMA0
        state[1] = SIGMA1
        state[2] = SIGMA2
        state[3] = SIGMA3
        for (i in 0 until 8) state[4 + i] = le32(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = le32(nonce, i * 4)

        val working = state.copyOf()
        repeat(10) { doubleRound(working) }

        val out = ByteArray(64)
        for (i in 0 until 16) putLe32(out, i * 4, working[i] + state[i])
        return out
    }

    /** XORs [data] with the ChaCha20 keystream starting at block [startCounter]. */
    fun xor(
        key: ByteArray,
        startCounter: Int,
        nonce: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val out = ByteArray(data.size)
        var counter = startCounter
        var offset = 0
        while (offset < data.size) {
            val keystream = block(key, counter, nonce)
            val n = minOf(64, data.size - offset)
            for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor keystream[i].toInt()).toByte()
            offset += n
            counter++
        }
        return out
    }

    private fun doubleRound(x: IntArray) {
        quarterRound(x, 0, 4, 8, 12)
        quarterRound(x, 1, 5, 9, 13)
        quarterRound(x, 2, 6, 10, 14)
        quarterRound(x, 3, 7, 11, 15)
        quarterRound(x, 0, 5, 10, 15)
        quarterRound(x, 1, 6, 11, 12)
        quarterRound(x, 2, 7, 8, 13)
        quarterRound(x, 3, 4, 9, 14)
    }

    private fun quarterRound(
        x: IntArray,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ) {
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(7)
    }
}

/** ChaCha20-Poly1305 AEAD (RFC 8439 section 2.8). A sealed message is `ciphertext || tag(16)`. */
internal object ChaCha20Poly1305 {
    const val KEY_SIZE: Int = ChaCha20.KEY_SIZE
    const val NONCE_SIZE: Int = ChaCha20.NONCE_SIZE
    const val TAG_SIZE: Int = 16

    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val oneTimeKey = ChaCha20.block(key, 0, nonce).copyOf(32)
        val ciphertext = ChaCha20.xor(key, 1, nonce, plaintext)
        val macInput = macInput(aad, ciphertext)
        val tag = Poly1305.mac(macInput, 0, macInput.size, oneTimeKey)
        return ciphertext + tag
    }

    fun open(
        key: ByteArray,
        nonce: ByteArray,
        sealed: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        if (sealed.size < TAG_SIZE) throw DecryptionException("Message too short")
        val ciphertext = sealed.copyOf(sealed.size - TAG_SIZE)
        val tag = sealed.copyOfRange(sealed.size - TAG_SIZE, sealed.size)

        val oneTimeKey = ChaCha20.block(key, 0, nonce).copyOf(32)
        val macInput = macInput(aad, ciphertext)
        val expected = Poly1305.mac(macInput, 0, macInput.size, oneTimeKey)
        if (!constantTimeEquals(expected, tag)) throw DecryptionException()
        return ChaCha20.xor(key, 1, nonce, ciphertext)
    }

    /** `aad || pad16(aad) || ciphertext || pad16(ciphertext) || len(aad):8LE || len(ciphertext):8LE`. */
    private fun macInput(
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val out = ByteArray(paddedLength(aad.size) + paddedLength(ciphertext.size) + 16)
        var offset = 0
        aad.copyInto(out, offset)
        offset += paddedLength(aad.size)
        ciphertext.copyInto(out, offset)
        offset += paddedLength(ciphertext.size)
        putLe64(out, offset, aad.size.toLong())
        putLe64(out, offset + 8, ciphertext.size.toLong())
        return out
    }

    private fun paddedLength(size: Int): Int = if (size % 16 == 0) size else size + (16 - size % 16)

    private fun putLe64(
        b: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (i in 0 until 8) b[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
