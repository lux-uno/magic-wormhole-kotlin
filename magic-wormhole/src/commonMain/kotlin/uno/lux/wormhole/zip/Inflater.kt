package uno.lux.wormhole.zip

import kotlinx.io.EOFException
import kotlinx.io.Source
import uno.lux.wormhole.InvalidZipException

/**
 * Decompresses one raw DEFLATE stream (RFC 1951) from [source]. It reads exactly up to the end
 * of the stream and no further, so the zip reader can continue right after it.
 */
internal class Inflater(
    private val source: Source,
) {
    private var bitBuffer = 0
    private var bitCount = 0
    private val window = ByteArray(WINDOW_SIZE)
    private var position = 0
    private var flushedUpTo = 0
    private var total = 0L

    /** Inflates the whole stream. [output] gets the bytes `bytes[start until end]`. */
    fun inflate(output: (bytes: ByteArray, start: Int, end: Int) -> Unit) {
        try {
            do {
                val last = bits(1) == 1
                when (bits(2)) {
                    0 -> stored(output)
                    1 -> codes(FIXED_LITERALS, FIXED_DISTANCES, output)
                    2 -> dynamic(output)
                    else -> throw InvalidZipException("Invalid deflate block type")
                }
            } while (!last)
            flush(output)
        } catch (e: EOFException) {
            throw InvalidZipException("Compressed data ends too early")
        }
    }

    private fun readByte(): Int = source.readByte().toInt() and 0xFF

    private fun bits(n: Int): Int {
        while (bitCount < n) {
            bitBuffer = bitBuffer or (readByte() shl bitCount)
            bitCount += 8
        }
        val value = bitBuffer and ((1 shl n) - 1)
        bitBuffer = bitBuffer ushr n
        bitCount -= n
        return value
    }

    private fun put(
        byte: Byte,
        output: (ByteArray, Int, Int) -> Unit,
    ) {
        window[position++] = byte
        total++
        if (position == WINDOW_SIZE) {
            flush(output)
            position = 0
            flushedUpTo = 0
        }
    }

    private fun flush(output: (ByteArray, Int, Int) -> Unit) {
        if (position > flushedUpTo) output(window, flushedUpTo, position)
        flushedUpTo = position
    }

    private fun stored(output: (ByteArray, Int, Int) -> Unit) {
        // Skip to the byte boundary. Bytes are only read when needed, so fewer than 8 bits are left.
        bitBuffer = 0
        bitCount = 0
        val length = readByte() or (readByte() shl 8)
        val inverse = readByte() or (readByte() shl 8)
        if (length != inverse.inv() and 0xFFFF) throw InvalidZipException("Invalid stored block length")
        repeat(length) { put(source.readByte(), output) }
    }

    private fun decode(code: Huffman): Int {
        while (true) {
            val entry = code.table[bitBuffer and code.mask]
            val length = entry and 0xF
            if (length != 0 && length <= bitCount) {
                bitBuffer = bitBuffer ushr length
                bitCount -= length
                return entry ushr 4
            }
            if (bitCount >= code.maxLength) throw InvalidZipException("Invalid Huffman code")
            bitBuffer = bitBuffer or (readByte() shl bitCount)
            bitCount += 8
        }
    }

    private fun codes(
        literals: Huffman,
        distances: Huffman,
        output: (ByteArray, Int, Int) -> Unit,
    ) {
        while (true) {
            val symbol = decode(literals)
            when {
                symbol < 256 -> {
                    put(symbol.toByte(), output)
                }

                symbol == 256 -> {
                    return
                }

                else -> {
                    val index = symbol - 257
                    if (index >= LENGTH_BASE.size) throw InvalidZipException("Invalid length code")
                    val length = LENGTH_BASE[index] + bits(LENGTH_EXTRA[index])
                    val distanceSymbol = decode(distances)
                    if (distanceSymbol >= DISTANCE_BASE.size) throw InvalidZipException("Invalid distance code")
                    val distance = DISTANCE_BASE[distanceSymbol] + bits(DISTANCE_EXTRA[distanceSymbol])
                    if (distance > total) throw InvalidZipException("Distance too far back")
                    repeat(length) { put(window[(position - distance) and (WINDOW_SIZE - 1)], output) }
                }
            }
        }
    }

    private fun dynamic(output: (ByteArray, Int, Int) -> Unit) {
        val literalCount = bits(5) + 257
        val distanceCount = bits(5) + 1
        val codeLengthCount = bits(4) + 4
        if (literalCount > 286 || distanceCount > 30) throw InvalidZipException("Too many deflate codes")
        val codeLengthLengths = IntArray(19)
        for (i in 0 until codeLengthCount) codeLengthLengths[CODE_LENGTH_ORDER[i]] = bits(3)
        val codeLengthCode = Huffman(codeLengthLengths)

        val lengths = IntArray(literalCount + distanceCount)
        var i = 0
        while (i < lengths.size) {
            val symbol = decode(codeLengthCode)
            if (symbol < 16) {
                lengths[i++] = symbol
                continue
            }
            val (value, repeat) =
                when (symbol) {
                    16 -> {
                        if (i == 0) throw InvalidZipException("Repeat with no previous length")
                        lengths[i - 1] to 3 + bits(2)
                    }

                    17 -> {
                        0 to 3 + bits(3)
                    }

                    else -> {
                        0 to 11 + bits(7)
                    }
                }
            if (i + repeat > lengths.size) throw InvalidZipException("Too many code lengths")
            repeat(repeat) { lengths[i++] = value }
        }
        if (lengths[256] == 0) throw InvalidZipException("No end-of-block code")
        codes(
            Huffman(lengths.copyOfRange(0, literalCount)),
            Huffman(lengths.copyOfRange(literalCount, lengths.size)),
            output,
        )
    }

    /**
     * A canonical Huffman code as a lookup table indexed by the next [maxLength] input bits
     * (least significant bit first). Each entry is `symbol shl 4 or length`; 0 means no code.
     */
    private class Huffman(
        lengths: IntArray,
    ) {
        val maxLength = maxOf(1, lengths.maxOrNull() ?: 0)
        val mask = (1 shl maxLength) - 1
        val table = IntArray(1 shl maxLength)

        init {
            val count = IntArray(16)
            lengths.forEach { count[it]++ }
            count[0] = 0
            var left = 1
            for (len in 1..15) {
                left = (left shl 1) - count[len]
                if (left < 0) throw InvalidZipException("Over-subscribed Huffman code")
            }
            val next = IntArray(16)
            var code = 0
            for (len in 1..15) {
                code = (code + count[len - 1]) shl 1
                next[len] = code
            }
            lengths.forEachIndexed { symbol, len ->
                if (len == 0) return@forEachIndexed
                val reversed = reverse(next[len]++, len)
                var index = reversed
                while (index < table.size) {
                    table[index] = (symbol shl 4) or len
                    index += 1 shl len
                }
            }
        }

        private fun reverse(
            code: Int,
            length: Int,
        ): Int {
            var result = 0
            var c = code
            repeat(length) {
                result = (result shl 1) or (c and 1)
                c = c ushr 1
            }
            return result
        }
    }

    private companion object {
        const val WINDOW_SIZE = 32768

        val LENGTH_BASE =
            intArrayOf(
                3,
                4,
                5,
                6,
                7,
                8,
                9,
                10,
                11,
                13,
                15,
                17,
                19,
                23,
                27,
                31,
                35,
                43,
                51,
                59,
                67,
                83,
                99,
                115,
                131,
                163,
                195,
                227,
                258,
            )
        val LENGTH_EXTRA =
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        val DISTANCE_BASE =
            intArrayOf(
                1,
                2,
                3,
                4,
                5,
                7,
                9,
                13,
                17,
                25,
                33,
                49,
                65,
                97,
                129,
                193,
                257,
                385,
                513,
                769,
                1025,
                1537,
                2049,
                3073,
                4097,
                6145,
                8193,
                12289,
                16385,
                24577,
            )
        val DISTANCE_EXTRA =
            intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
        val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

        val FIXED_LITERALS =
            Huffman(
                IntArray(288) {
                    if (it < 144) {
                        8
                    } else if (it < 256) {
                        9
                    } else if (it < 280) {
                        7
                    } else {
                        8
                    }
                },
            )
        val FIXED_DISTANCES = Huffman(IntArray(30) { 5 })
    }
}
