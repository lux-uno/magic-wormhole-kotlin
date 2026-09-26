@file:Suppress("ktlint:standard:max-line-length") // Generated test vectors.

package uno.lux.wormhole.transit

// Generated with Python (cryptography HKDF + PyNaCl), following magic-wormhole's transit.py.
// Transit key = bytes 0..31, relay side = "0123456789abcdef".
internal object TransitVectors {
    val KEY = ByteArray(32) { it.toByte() }
    const val RELAY_SIDE = "0123456789abcdef"
    const val SENDER_HANDSHAKE = "transit sender df67f98b57b6009f674ac8ea789aa2a494e4c52582da55d38857d56518b81bba ready\n\n"
    const val RECEIVER_HANDSHAKE = "transit receiver c83f7ef38f8b2e0a2495966db3c137772e81c475f54f45483fb1e5dc500db6dc ready\n\n"
    const val RELAY_HANDSHAKE = "please relay 2bb809ffd25339e827f73497f80f9d4419708192bc8282ab3d28e530fc7599e7 for side 0123456789abcdef\n"
    const val SENDER_RECORD_KEY = "a879f7c8206df95eba1f8a887203e3ceaa5238b43455859c34e02f34eb797d37"
    const val RECEIVER_RECORD_KEY = "438fe439c794bdc524ba4f5935b03fdfef305e8dbc6369a839f0ea09d09ef2ef"

    /** Framed records "hello" (nonce 0) and "world" (nonce 1) sent by the sender. */
    const val SENDER_RECORDS = "0000002d000000000000000000000000000000000000000000000000e484d471d9cdacf07329034263db07ca6265401d770000002d0000000000000000000000000000000000000000000000018f630593a1417e62ff1d1b53a2c412a3e7772ccd04"
}
