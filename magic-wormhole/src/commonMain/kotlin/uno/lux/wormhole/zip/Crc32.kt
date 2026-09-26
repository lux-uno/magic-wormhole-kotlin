package uno.lux.wormhole.zip

/** CRC-32 (IEEE 802.3), as used by zip. */
internal class Crc32 {
    private var crc = -1

    val value: Int get() = crc.inv()

    fun update(
        bytes: ByteArray,
        startIndex: Int = 0,
        endIndex: Int = bytes.size,
    ) {
        var c = crc
        for (i in startIndex until endIndex) {
            c = TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        crc = c
    }

    private companion object {
        val TABLE =
            IntArray(256) { n ->
                var c = n
                repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
                c
            }
    }
}
