// Field arithmetic, point addition and modL are ported from TweetNaCl (public domain).
// Point decoding, arbitrary elements and scalar conversions follow python-spake2 (MIT).
package uno.lux.wormhole.crypto

/**
 * An element of GF(2^255 - 19) as 16 limbs of 16 bits (TweetNaCl layout).
 * Values are not always fully reduced; [pack] gives the canonical encoding.
 */
internal typealias Fe = LongArray

internal object Field {
    fun zero(): Fe = LongArray(16)

    fun one(): Fe = LongArray(16).also { it[0] = 1 }

    fun of(value: Int): Fe = LongArray(16).also { it[0] = value.toLong() }

    fun copy(a: Fe): Fe = a.copyOf()

    fun add(
        a: Fe,
        b: Fe,
    ): Fe = LongArray(16) { a[it] + b[it] }

    fun sub(
        a: Fe,
        b: Fe,
    ): Fe = LongArray(16) { a[it] - b[it] }

    fun neg(a: Fe): Fe = sub(zero(), a)

    fun mul(
        a: Fe,
        b: Fe,
    ): Fe {
        val t = LongArray(31)
        for (i in 0 until 16) {
            val ai = a[i]
            for (j in 0 until 16) t[i + j] += ai * b[j]
        }
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        val o = t.copyOf(16)
        carry(o)
        carry(o)
        return o
    }

    fun square(a: Fe): Fe = mul(a, a)

    fun invert(a: Fe): Fe {
        var c = copy(a)
        for (i in 253 downTo 0) {
            c = square(c)
            if (i != 2 && i != 4) c = mul(c, a)
        }
        return c
    }

    /** a^((p - 5) / 8) = a^(2^252 - 3). */
    fun pow2523(a: Fe): Fe {
        var c = copy(a)
        for (i in 250 downTo 0) {
            c = square(c)
            if (i != 1) c = mul(c, a)
        }
        return c
    }

    /** a^e where e is a little-endian byte array. Not constant time; only used for constants. */
    fun pow(
        a: Fe,
        exponent: ByteArray,
    ): Fe {
        var result = one()
        for (bit in exponent.size * 8 - 1 downTo 0) {
            result = square(result)
            if ((exponent[bit / 8].toInt() ushr (bit % 8)) and 1 == 1) result = mul(result, a)
        }
        return result
    }

    fun unpack(bytes: ByteArray): Fe {
        val o = LongArray(16) { (bytes[2 * it].toLong() and 0xff) + ((bytes[2 * it + 1].toLong() and 0xff) shl 8) }
        o[15] = o[15] and 0x7fff
        return o
    }

    fun pack(n: Fe): ByteArray {
        val t = copy(n)
        carry(t)
        carry(t)
        carry(t)
        val m = LongArray(16)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            select(t, m, 1 - b)
        }
        val o = ByteArray(32)
        for (i in 0 until 16) {
            o[2 * i] = t[i].toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
        return o
    }

    fun equal(
        a: Fe,
        b: Fe,
    ): Boolean = pack(a).contentEquals(pack(b))

    fun isZero(a: Fe): Boolean = pack(a).all { it.toInt() == 0 }

    fun parity(a: Fe): Int = pack(a)[0].toInt() and 1

    private fun carry(o: Fe) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    /** Swaps p and q when b == 1, in constant time. */
    fun select(
        p: Fe,
        q: Fe,
        b: Int,
    ) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }
}

/** A scalar modulo the group order L, stored as 32 little-endian bytes. */
internal class Scalar private constructor(
    private val bytes: ByteArray,
) {
    fun toBytes(): ByteArray = bytes.copyOf()

    /** Returns L - this (mod L). */
    fun negate(): Scalar {
        if (bytes.all { it.toInt() == 0 }) return this
        val out = ByteArray(32)
        var borrow = 0
        for (i in 0 until 32) {
            var d = (L_BYTES[i].toInt() and 0xff) - (bytes[i].toInt() and 0xff) - borrow
            borrow = if (d < 0) 1 else 0
            if (d < 0) d += 256
            out[i] = d.toByte()
        }
        return Scalar(out)
    }

    internal fun bit(i: Int): Int = (bytes[i / 8].toInt() ushr (i and 7)) and 1

    companion object {
        /** L = 2^252 + 27742317777372353535851937790883648493, little-endian. */
        val L_BYTES: ByteArray =
            longArrayOf(
                0xed,
                0xd3,
                0xf5,
                0x5c,
                0x1a,
                0x63,
                0x12,
                0x58,
                0xd6,
                0x9c,
                0xf7,
                0xa2,
                0xde,
                0xf9,
                0xde,
                0x14,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0x10,
            ).map { it.toByte() }.toByteArray()

        private val L = LongArray(32) { L_BYTES[it].toLong() and 0xff }

        fun fromLong(value: Long): Scalar {
            require(value >= 0)
            val x = LongArray(64)
            var v = value
            for (i in 0 until 8) {
                x[i] = v and 0xff
                v = v ushr 8
            }
            return Scalar(modL(x))
        }

        /** Interprets up to 64 big-endian bytes as a number and reduces it mod L. */
        fun fromBigEndianModL(bigEndian: ByteArray): Scalar {
            require(bigEndian.size <= 64)
            val x = LongArray(64)
            for (i in bigEndian.indices) x[i] = bigEndian[bigEndian.size - 1 - i].toLong() and 0xff
            return Scalar(modL(x))
        }

        /** python-spake2 `password_to_scalar`: HKDF-SHA256(pw, info="SPAKE2 pw", 48 bytes) mod L. */
        fun fromPassword(password: ByteArray): Scalar =
            fromBigEndianModL(hkdfSha256(password, ByteArray(0), "SPAKE2 pw".encodeToByteArray(), 48))

        /** python-spake2 `random_scalar`: 64 random bytes, big-endian, mod L. */
        fun random(random: (Int) -> ByteArray): Scalar = fromBigEndianModL(random(64))

        private fun modL(x: LongArray): ByteArray {
            for (i in 63 downTo 32) {
                var carry = 0L
                var j = i - 32
                while (j < i - 12) {
                    x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                    carry = (x[j] + 128) shr 8
                    x[j] -= carry shl 8
                    j++
                }
                x[j] += carry
                x[i] = 0
            }
            var carry = 0L
            for (j in 0 until 32) {
                x[j] += carry - (x[31] shr 4) * L[j]
                carry = x[j] shr 8
                x[j] = x[j] and 255
            }
            for (j in 0 until 32) x[j] -= carry * L[j]
            val r = ByteArray(32)
            for (i in 0 until 32) {
                x[i + 1] += x[i] shr 8
                r[i] = (x[i] and 255).toByte()
            }
            return r
        }
    }
}

/** A point on edwards25519 in extended coordinates (X, Y, Z, T), with x = X/Z, y = Y/Z, xy = T/Z. */
internal class EdPoint(
    val x: Fe,
    val y: Fe,
    val z: Fe,
    val t: Fe,
) {
    /** Unified addition (add-2008-hwcd-3). Also correct for doubling and the identity. */
    operator fun plus(q: EdPoint): EdPoint {
        val a = Field.mul(Field.sub(y, x), Field.sub(q.y, q.x))
        val b = Field.mul(Field.add(x, y), Field.add(q.x, q.y))
        val c = Field.mul(Field.mul(t, q.t), Ed25519.D2)
        val d0 = Field.mul(z, q.z)
        val d = Field.add(d0, d0)
        val e = Field.sub(b, a)
        val f = Field.sub(d, c)
        val g = Field.add(d, c)
        val h = Field.add(b, a)
        return EdPoint(Field.mul(e, f), Field.mul(h, g), Field.mul(g, f), Field.mul(e, h))
    }

    /** Constant-time double-and-add ladder over all 256 bits of [scalar]. */
    fun times(scalar: Scalar): EdPoint = timesBits { scalar.bit(it) }

    internal fun timesBits(bit: (Int) -> Int): EdPoint {
        var p = Ed25519.IDENTITY.copy()
        var q = copy()
        for (i in 255 downTo 0) {
            val b = bit(i)
            swap(p, q, b)
            q = q + p
            p = p + p
            swap(p, q, b)
        }
        return p
    }

    fun isIdentity(): Boolean = Field.isZero(x) && Field.equal(y, z)

    fun encode(): ByteArray {
        val zi = Field.invert(z)
        val ax = Field.mul(x, zi)
        val ay = Field.mul(y, zi)
        val r = Field.pack(ay)
        r[31] = (r[31].toInt() xor (Field.parity(ax) shl 7)).toByte()
        return r
    }

    fun copy(): EdPoint = EdPoint(x.copyOf(), y.copyOf(), z.copyOf(), t.copyOf())

    private fun swap(
        p: EdPoint,
        q: EdPoint,
        b: Int,
    ) {
        Field.select(p.x, q.x, b)
        Field.select(p.y, q.y, b)
        Field.select(p.z, q.z, b)
        Field.select(p.t, q.t, b)
    }
}

internal object Ed25519 {
    /** d = -121665 / 121666. */
    val D: Fe = Field.mul(Field.neg(Field.of(121665)), Field.invert(Field.of(121666)))
    val D2: Fe = Field.add(D, D)

    /** sqrt(-1) = 2^((p - 1) / 4). */
    val SQRT_M1: Fe =
        Field.pow(
            Field.of(2),
            ByteArray(32) {
                if (it == 0) {
                    0xfb.toByte()
                } else if (it == 31) {
                    0x1f
                } else {
                    0xff.toByte()
                }
            },
        )

    val IDENTITY: EdPoint = EdPoint(Field.zero(), Field.one(), Field.one(), Field.zero())

    /** The standard base point, with y = 4/5 and even x. */
    val BASE: EdPoint =
        run {
            val y = Field.mul(Field.of(4), Field.invert(Field.of(5)))
            affine(recoverEvenX(y)!!, y)
        }

    private fun affine(
        x: Fe,
        y: Fe,
    ) = EdPoint(x, y, Field.one(), Field.mul(x, y))

    /** Returns the even x with (x, y) on the curve, or null when there is none. */
    private fun recoverEvenX(y: Fe): Fe? {
        val y2 = Field.square(y)
        val xx = Field.mul(Field.sub(y2, Field.one()), Field.invert(Field.add(Field.mul(D, y2), Field.one())))
        var x = Field.mul(Field.pow2523(xx), xx) // xx^((p + 3) / 8)
        if (!Field.equal(Field.square(x), xx)) x = Field.mul(x, SQRT_M1)
        if (!Field.equal(Field.square(x), xx)) return null
        if (Field.parity(x) == 1) x = Field.neg(x)
        return x
    }

    /**
     * Decodes a point and checks that it is in the prime-order subgroup and not the identity
     * (python-spake2 `bytes_to_element`).
     */
    fun decodeElement(bytes: ByteArray): EdPoint {
        require(bytes.size == 32) { "Element must be 32 bytes" }
        val y = Field.unpack(bytes)
        val yBytes = bytes.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
        require(Field.pack(y).contentEquals(yBytes)) { "Non-canonical element encoding" }
        var x = requireNotNull(recoverEvenX(y)) { "Element is not on the curve" }
        if ((bytes[31].toInt() ushr 7) and 1 == 1) {
            require(!Field.isZero(x)) { "Invalid element sign" }
            x = Field.neg(x)
        }
        val point = affine(x, y)
        require(!point.isIdentity()) { "Element is the identity" }
        require(point.timesBits { bit -> (Scalar.L_BYTES[bit / 8].toInt() ushr (bit and 7)) and 1 }.isIdentity()) {
            "Element is not in the prime-order subgroup"
        }
        return point
    }

    /** python-spake2 `arbitrary_element`: hash the seed to a point with unknown discrete log. */
    fun arbitraryElement(seed: ByteArray): EdPoint {
        val h = hkdfSha256(seed, ByteArray(0), "SPAKE2 arbitrary element".encodeToByteArray(), 48)
        var y = Field.zero()
        val f256 = Field.of(256)
        for (b in h) y = Field.add(Field.mul(y, f256), Field.of(b.toInt() and 0xff))
        var plus = 0
        while (true) {
            val yPlus = Field.add(y, Field.of(plus++))
            val x = recoverEvenX(yPlus) ?: continue
            val p = affine(x, yPlus)
            val p2 = p + p
            val p4 = p2 + p2
            val p8 = p4 + p4
            if (p8.isIdentity()) continue
            return p8
        }
    }
}
