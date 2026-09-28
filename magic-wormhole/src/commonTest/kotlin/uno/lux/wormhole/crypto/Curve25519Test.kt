package uno.lux.wormhole.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Curve25519Test {
    @Test
    fun x25519MatchesVectors() {
        for (v in Curve25519Vectors.all) {
            val output = Curve25519.x25519(v.scalar.hexToByteArray(), v.u.hexToByteArray())
            assertContentEquals(v.output.hexToByteArray(), output, "scalar=${v.scalar}")
        }
    }

    @Test
    fun iteratedOnceMatchesRfc7748() {
        val nine = ByteArray(32).also { it[0] = 9 }
        assertEquals(Curve25519Vectors.ITERATE_1, Curve25519.x25519(nine, nine).toHexString())
    }

    @Test
    fun iterated1000TimesMatchesRfc7748() {
        var k = ByteArray(32).also { it[0] = 9 }
        var u = ByteArray(32).also { it[0] = 9 }
        repeat(1000) {
            val next = Curve25519.x25519(k, u)
            u = k
            k = next
        }
        assertEquals(Curve25519Vectors.ITERATE_1000, k.toHexString())
    }

    @Test
    fun diffieHellmanAgreementMatchesIndependentImplementation() {
        val alicePrivate = Curve25519Vectors.ALICE_PRIVATE.hexToByteArray()
        val bobPrivate = Curve25519Vectors.BOB_PRIVATE.hexToByteArray()

        val alicePublic = Curve25519.scalarMultBase(alicePrivate)
        val bobPublic = Curve25519.scalarMultBase(bobPrivate)
        assertEquals(Curve25519Vectors.ALICE_PUBLIC, alicePublic.toHexString())
        assertEquals(Curve25519Vectors.BOB_PUBLIC, bobPublic.toHexString())

        val sharedByAlice = Curve25519.x25519(alicePrivate, bobPublic)
        val sharedByBob = Curve25519.x25519(bobPrivate, alicePublic)
        assertContentEquals(sharedByAlice, sharedByBob)
        assertEquals(Curve25519Vectors.SHARED_SECRET, sharedByAlice.toHexString())
    }
}
