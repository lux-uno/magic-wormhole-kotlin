package uno.lux.wormhole.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class HashTest {
    @Test
    fun sha256OfAbc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHexString(),
        )
    }

    @Test
    fun hmacSha256Rfc4231Case2() {
        val mac =
            hmacSha256(
                key = "Jefe".encodeToByteArray(),
                data = "what do ya want for nothing?".encodeToByteArray(),
            )
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.toHexString())
    }

    @Test
    fun hkdfSha256Rfc5869Case1() {
        val okm =
            hkdfSha256(
                ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToByteArray(),
                salt = "000102030405060708090a0b0c".hexToByteArray(),
                info = "f0f1f2f3f4f5f6f7f8f9".hexToByteArray(),
                length = 42,
            )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHexString(),
        )
    }

    @Test
    fun hkdfSha256Rfc5869Case3EmptySaltAndInfo() {
        val okm =
            hkdfSha256(
                ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToByteArray(),
                salt = ByteArray(0),
                info = ByteArray(0),
                length = 42,
            )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.toHexString(),
        )
    }
}
