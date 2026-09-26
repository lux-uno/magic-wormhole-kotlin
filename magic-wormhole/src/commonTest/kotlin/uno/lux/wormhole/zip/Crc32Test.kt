package uno.lux.wormhole.zip

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32Test {
    @Test
    fun knownValues() {
        assertEquals(0, Crc32().value)
        assertEquals(0xCBF43926.toInt(), Crc32().apply { update("123456789".encodeToByteArray()) }.value)
    }

    @Test
    fun updatesInPartsGiveTheSameValue() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        val whole = Crc32().apply { update(data) }.value
        val parts =
            Crc32()
                .apply {
                    update(data, 0, 300)
                    update(data, 300, 1000)
                }.value
        assertEquals(whole, parts)
    }
}
