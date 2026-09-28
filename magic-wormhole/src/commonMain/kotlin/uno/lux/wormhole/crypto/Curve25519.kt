// X25519 (RFC 7748 section 5), expressed with the GF(2^255 - 19) field arithmetic from Ed25519.kt.
package uno.lux.wormhole.crypto

/** X25519 Diffie-Hellman over Curve25519 (RFC 7748), used by [Noise]'s `25519` DH function. */
internal object Curve25519 {
    private val A24: Fe = Field.of(121665)
    private val BASE_U: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** `X25519(scalar, u)`: the Montgomery ladder from RFC 7748 section 5. */
    fun x25519(
        scalar: ByteArray,
        u: ByteArray,
    ): ByteArray {
        require(scalar.size == 32) { "Scalar must be 32 bytes" }
        require(u.size == 32) { "U-coordinate must be 32 bytes" }
        val k = clamp(scalar)
        val x1 = Field.unpack(u)
        var x2 = Field.one()
        var z2 = Field.zero()
        var x3 = Field.copy(x1)
        var z3 = Field.one()
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = (k[t / 8].toInt() ushr (t % 8)) and 1
            swap = swap xor kt
            Field.select(x2, x3, swap)
            Field.select(z2, z3, swap)
            swap = kt

            val a = Field.add(x2, z2)
            val aa = Field.square(a)
            val b = Field.sub(x2, z2)
            val bb = Field.square(b)
            val e = Field.sub(aa, bb)
            val c = Field.add(x3, z3)
            val d = Field.sub(x3, z3)
            val da = Field.mul(d, a)
            val cb = Field.mul(c, b)
            x3 = Field.square(Field.add(da, cb))
            z3 = Field.mul(x1, Field.square(Field.sub(da, cb)))
            x2 = Field.mul(aa, bb)
            z2 = Field.mul(e, Field.add(aa, Field.mul(A24, e)))
        }
        Field.select(x2, x3, swap)
        Field.select(z2, z3, swap)
        return Field.pack(Field.mul(x2, Field.invert(z2)))
    }

    /** `X25519(scalar, 9)`: derives the public key for a private [scalar]. */
    fun scalarMultBase(scalar: ByteArray): ByteArray = x25519(scalar, BASE_U)

    private fun clamp(scalar: ByteArray): ByteArray {
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 0xf8).toByte()
        k[31] = ((k[31].toInt() and 0x7f) or 0x40).toByte()
        return k
    }
}
