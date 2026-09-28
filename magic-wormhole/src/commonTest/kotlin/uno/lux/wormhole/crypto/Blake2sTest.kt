package uno.lux.wormhole.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class Blake2sTest {
    @Test
    fun hashMatchesReferenceImplementation() {
        for (v in Blake2sVectors.hashes) {
            assertEquals(v.digest, blake2s(v.message.hexToByteArray()).toHexString(), v.label)
        }
    }

    @Test
    fun hmacMatchesReferenceImplementation() {
        for (v in Blake2sVectors.hmacs) {
            val mac = hmacBlake2s(v.key.hexToByteArray(), v.message.hexToByteArray())
            assertEquals(v.mac, mac.toHexString(), v.label)
        }
    }
}
