package uno.lux.wormhole.crypto

import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256
import org.kotlincrypto.random.CryptoRand

internal fun sha256(data: ByteArray): ByteArray = SHA256().digest(data)

internal fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray = HmacSHA256(key).doFinal(data)

/** HKDF-SHA256 (RFC 5869). An empty [salt] means a salt of 32 zero bytes. */
internal fun hkdfSha256(
    ikm: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    length: Int,
): ByteArray {
    require(length in 0..255 * 32) { "HKDF length too large: $length" }
    val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
    val okm = ByteArray(length)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < length) {
        previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
        val n = minOf(previous.size, length - offset)
        previous.copyInto(okm, offset, 0, n)
        offset += n
        counter++
    }
    return okm
}

internal fun randomBytes(size: Int): ByteArray = CryptoRand.Default.nextBytes(ByteArray(size))
