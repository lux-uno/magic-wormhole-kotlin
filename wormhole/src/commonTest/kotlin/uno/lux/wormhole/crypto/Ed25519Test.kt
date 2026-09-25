package uno.lux.wormhole.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Ed25519Test {
    @Test
    fun baseEncodesLikePython() {
        assertEquals(Spake2Vectors.BASE, Ed25519.BASE.encode().toHexString())
    }

    @Test
    fun scalarMultiplicationOfBase() {
        val scalar = Scalar.fromLong(12345)
        assertEquals(Spake2Vectors.BASE_TIMES_12345, Ed25519.BASE.times(scalar).encode().toHexString())
    }

    @Test
    fun multiplyingByLMinusOneNegatesTheBase() {
        val lMinusOne = Scalar.fromLong(1).negate()
        assertEquals(Spake2Vectors.BASE_TIMES_L_MINUS_1, Ed25519.BASE.times(lMinusOne).encode().toHexString())
    }

    @Test
    fun addition() {
        val s = Ed25519.decodeElement(Spake2Vectors.S_ELEMENT.hexToByteArray())
        assertEquals(Spake2Vectors.BASE_PLUS_S, Ed25519.BASE.plus(s).encode().toHexString())
    }

    @Test
    fun decodeThenEncodeRoundTrips() {
        val bytes = Spake2Vectors.BASE_TIMES_12345.hexToByteArray()
        assertEquals(Spake2Vectors.BASE_TIMES_12345, Ed25519.decodeElement(bytes).encode().toHexString())
    }

    @Test
    fun arbitraryElementsMatchPython() {
        assertEquals(Spake2Vectors.S_ELEMENT, Ed25519.arbitraryElement("symmetric".encodeToByteArray()).encode().toHexString())
        assertEquals(Spake2Vectors.M_ELEMENT, Ed25519.arbitraryElement("M".encodeToByteArray()).encode().toHexString())
    }

    @Test
    fun decodeRejectsTheIdentity() {
        val identity = ByteArray(32).also { it[0] = 1 }
        assertFailsWith<IllegalArgumentException> { Ed25519.decodeElement(identity) }
    }

    @Test
    fun decodeRejectsLowOrderPoints() {
        // (0, -1) has order 2.
        val orderTwo = "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f".hexToByteArray()
        assertFailsWith<IllegalArgumentException> { Ed25519.decodeElement(orderTwo) }
    }

    @Test
    fun decodeRejectsPointsNotOnTheCurve() {
        val notOnCurve = ByteArray(32).also { it[0] = 2 }
        assertFailsWith<IllegalArgumentException> { Ed25519.decodeElement(notOnCurve) }
    }

    @Test
    fun passwordToScalarMatchesPython() {
        assertEquals(Spake2Vectors.SCALAR_PW, Scalar.fromPassword("4-purple-sausages".encodeToByteArray()).toBytes().toHexString())
        assertEquals(Spake2Vectors.SCALAR_EMPTY, Scalar.fromPassword(ByteArray(0)).toBytes().toHexString())
    }

    @Test
    fun randomScalarMatchesPython() {
        val scalar = Scalar.fromBigEndianModL(Spake2Vectors.RANDOM_SCALAR_INPUT.hexToByteArray())
        assertEquals(Spake2Vectors.RANDOM_SCALAR, scalar.toBytes().toHexString())
    }
}
