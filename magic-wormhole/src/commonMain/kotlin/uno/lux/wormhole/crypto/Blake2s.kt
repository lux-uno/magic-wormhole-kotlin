// BLAKE2s (RFC 7693), unkeyed with the fixed 32-byte digest length Noise's HASH() needs.
package uno.lux.wormhole.crypto

internal fun blake2s(data: ByteArray): ByteArray = Blake2sCore.hash(data)

/**
 * HMAC (RFC 2104) using [blake2s] as the underlying hash, with its 64-byte block size.
 * This is what the Noise Protocol Framework calls `HMAC-HASH`.
 */
internal fun hmacBlake2s(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val blockSize = Blake2sCore.BLOCK_SIZE
    val normalizedKey = if (key.size > blockSize) blake2s(key) else key
    val paddedKey = normalizedKey.copyOf(blockSize)
    val innerPad = ByteArray(blockSize) { (paddedKey[it].toInt() xor 0x36).toByte() }
    val outerPad = ByteArray(blockSize) { (paddedKey[it].toInt() xor 0x5c).toByte() }
    return blake2s(outerPad + blake2s(innerPad + data))
}

private object Blake2sCore {
    const val DIGEST_SIZE = 32
    const val BLOCK_SIZE = 64

    private val IV =
        intArrayOf(
            0x6A09E667,
            -0x4498517b,
            0x3C6EF372,
            -0x5ab00ac6,
            0x510E527F,
            -0x64fa9774,
            0x1F83D9AB,
            0x5BE0CD19,
        )

    private val SIGMA =
        arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )

    fun hash(data: ByteArray): ByteArray {
        val h = IV.copyOf()
        h[0] = h[0] xor 0x01010000 xor DIGEST_SIZE

        var offset = 0
        var processed = 0L
        while (offset + BLOCK_SIZE < data.size) {
            processed += BLOCK_SIZE
            compress(h, data, offset, processed, isLast = false)
            offset += BLOCK_SIZE
        }
        val finalBlock = ByteArray(BLOCK_SIZE)
        val remaining = data.size - offset
        data.copyInto(finalBlock, 0, offset, data.size)
        processed += remaining
        compress(h, finalBlock, 0, processed, isLast = true)

        val out = ByteArray(DIGEST_SIZE)
        for (i in 0 until 8) putLe32(out, i * 4, h[i])
        return out
    }

    private fun compress(
        h: IntArray,
        block: ByteArray,
        blockOffset: Int,
        counter: Long,
        isLast: Boolean,
    ) {
        val m = IntArray(16) { le32(block, blockOffset + it * 4) }
        val v = IntArray(16)
        for (i in 0 until 8) v[i] = h[i]
        for (i in 0 until 8) v[8 + i] = IV[i]
        v[12] = v[12] xor (counter and 0xffffffffL).toInt()
        v[13] = v[13] xor ((counter ushr 32) and 0xffffffffL).toInt()
        if (isLast) v[14] = v[14] xor -1

        for (round in 0 until 10) {
            val s = SIGMA[round]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(
        v: IntArray,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        x: Int,
        y: Int,
    ) {
        v[a] = v[a] + v[b] + x
        v[d] = (v[d] xor v[a]).rotr(16)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotr(12)
        v[a] = v[a] + v[b] + y
        v[d] = (v[d] xor v[a]).rotr(8)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotr(7)
    }

    private fun Int.rotr(n: Int): Int = (this ushr n) or (this shl (32 - n))
}
