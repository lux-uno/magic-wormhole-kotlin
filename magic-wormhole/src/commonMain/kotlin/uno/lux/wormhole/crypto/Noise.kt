// The Noise Protocol Framework (https://noiseprotocol.org/noise.html), scoped to exactly the one
// handshake Dilation uses: Noise_NNpsk0_25519_ChaChaPoly_BLAKE2s. Message pattern:
//   -> psk, e
//   <- e, ee
// This is not a general Noise engine: the two message patterns are implemented directly rather
// than interpreted from a token list, since no other pattern is needed here.
package uno.lux.wormhole.crypto

private val PROTOCOL_NAME = "Noise_NNpsk0_25519_ChaChaPoly_BLAKE2s".encodeToByteArray()
private const val HASH_LEN = 32
private const val DH_LEN = 32

internal enum class NoiseRole { INITIATOR, RESPONDER }

/** A one-way transport cipher produced by [NoiseHandshake.split]: a 32-byte key plus its own nonce. */
internal class NoiseCipherState(
    private val key: ByteArray,
) {
    init {
        require(key.size == 32) { "Key must be 32 bytes" }
    }

    private var nonce: Long = 0L

    fun encrypt(
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val ciphertext = ChaCha20Poly1305.seal(key, chachaNonce(nonce), plaintext, associatedData)
        nonce++
        return ciphertext
    }

    fun decrypt(
        associatedData: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val plaintext = ChaCha20Poly1305.open(key, chachaNonce(nonce), ciphertext, associatedData)
        nonce++
        return plaintext
    }
}

/** `4 zero bytes || little-endian 8-byte counter`, per the Noise spec's ChaChaPoly cipher functions. */
private fun chachaNonce(n: Long): ByteArray {
    val out = ByteArray(12)
    for (i in 0 until 8) out[4 + i] = (n ushr (8 * i)).toByte()
    return out
}

private fun hkdf2(
    chainingKey: ByteArray,
    inputKeyMaterial: ByteArray,
): Pair<ByteArray, ByteArray> {
    val tempKey = hmacBlake2s(chainingKey, inputKeyMaterial)
    val output1 = hmacBlake2s(tempKey, byteArrayOf(1))
    val output2 = hmacBlake2s(tempKey, output1 + byteArrayOf(2))
    return output1 to output2
}

private fun hkdf3(
    chainingKey: ByteArray,
    inputKeyMaterial: ByteArray,
): Triple<ByteArray, ByteArray, ByteArray> {
    val tempKey = hmacBlake2s(chainingKey, inputKeyMaterial)
    val output1 = hmacBlake2s(tempKey, byteArrayOf(1))
    val output2 = hmacBlake2s(tempKey, output1 + byteArrayOf(2))
    val output3 = hmacBlake2s(tempKey, output2 + byteArrayOf(3))
    return Triple(output1, output2, output3)
}

/** Noise's `SymmetricState`: the running `h`/`ck`/cipher-key used while a handshake is in progress. */
private class SymmetricState(
    protocolName: ByteArray,
) {
    var h: ByteArray =
        if (protocolName.size <= HASH_LEN) protocolName.copyOf(HASH_LEN) else blake2s(protocolName)
    var chainingKey: ByteArray = h.copyOf()
    private var cipherKey: ByteArray? = null
    private var cipherNonce: Long = 0L

    init {
        mixHash(ByteArray(0)) // MixHash(prologue); Dilation uses an empty Noise prologue.
    }

    fun mixHash(data: ByteArray) {
        h = blake2s(h + data)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (ck, tempKey) = hkdf2(chainingKey, inputKeyMaterial)
        chainingKey = ck
        cipherKey = tempKey
        cipherNonce = 0
    }

    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val (ck, tempH, tempKey) = hkdf3(chainingKey, inputKeyMaterial)
        chainingKey = ck
        mixHash(tempH)
        cipherKey = tempKey
        cipherNonce = 0
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val key = requireNotNull(cipherKey) { "MixKey(AndHash) must run before encrypting" }
        val ciphertext = ChaCha20Poly1305.seal(key, chachaNonce(cipherNonce), plaintext, h)
        cipherNonce++
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val key = requireNotNull(cipherKey) { "MixKey(AndHash) must run before decrypting" }
        val plaintext = ChaCha20Poly1305.open(key, chachaNonce(cipherNonce), ciphertext, h)
        cipherNonce++
        mixHash(ciphertext)
        return plaintext
    }

    fun split(): Pair<NoiseCipherState, NoiseCipherState> {
        val (k1, k2) = hkdf2(chainingKey, ByteArray(0))
        return NoiseCipherState(k1) to NoiseCipherState(k2)
    }
}

/**
 * A `Noise_NNpsk0_25519_ChaChaPoly_BLAKE2s` handshake for one connection attempt. [psk] is
 * Dilation's `dilation-v1` key, shared by every attempt; ephemeral keys are fresh per instance.
 */
internal class NoiseHandshake(
    private val role: NoiseRole,
    private val psk: ByteArray,
    private val ephemeralKeyGenerator: () -> ByteArray = { randomBytes(DH_LEN) },
) {
    init {
        require(psk.size == 32) { "PSK must be 32 bytes" }
    }

    private val symmetric = SymmetricState(PROTOCOL_NAME)
    private var localEphemeralPrivate: ByteArray? = null
    private var remoteEphemeralPublic: ByteArray? = null
    private var messagesProcessed = 0

    val isComplete: Boolean get() = messagesProcessed == 2

    /** Produces the next handshake message for this role to send. No payload; Dilation sends none. */
    fun writeMessage(): ByteArray {
        check(!isComplete) { "Handshake already complete" }
        return if (role == NoiseRole.INITIATOR) {
            check(messagesProcessed == 0) { "Initiator already sent message 1" }
            writeMessage1AsInitiator()
        } else {
            check(messagesProcessed == 1) { "Responder must read message 1 before writing message 2" }
            writeMessage2AsResponder()
        }
    }

    /** Consumes the peer's handshake message. Throws [DecryptionException] on a bad PSK or corruption. */
    fun readMessage(message: ByteArray) {
        check(!isComplete) { "Handshake already complete" }
        if (role == NoiseRole.RESPONDER) {
            check(messagesProcessed == 0) { "Responder already read message 1" }
            readMessage1AsResponder(message)
        } else {
            check(messagesProcessed == 1) { "Initiator must write message 1 before reading message 2" }
            readMessage2AsInitiator(message)
        }
    }

    /** Valid once [isComplete]. Returns (send cipher, receive cipher) for this role. */
    fun split(): Pair<NoiseCipherState, NoiseCipherState> {
        check(isComplete) { "Handshake not complete" }
        val (c1, c2) = symmetric.split()
        return if (role == NoiseRole.INITIATOR) c1 to c2 else c2 to c1
    }

    fun handshakeHash(): ByteArray = symmetric.h

    private fun writeMessage1AsInitiator(): ByteArray {
        symmetric.mixKeyAndHash(psk)
        val (privateKey, publicKey) = generateEphemeral()
        localEphemeralPrivate = privateKey
        symmetric.mixHash(publicKey)
        symmetric.mixKey(publicKey)
        val tag = symmetric.encryptAndHash(ByteArray(0))
        messagesProcessed = 1
        return publicKey + tag
    }

    private fun readMessage1AsResponder(message: ByteArray) {
        require(message.size == DH_LEN + ChaCha20Poly1305.TAG_SIZE) { "Malformed handshake message 1" }
        symmetric.mixKeyAndHash(psk)
        val theirPublicKey = message.copyOf(DH_LEN)
        symmetric.mixHash(theirPublicKey)
        symmetric.mixKey(theirPublicKey)
        symmetric.decryptAndHash(message.copyOfRange(DH_LEN, message.size))
        remoteEphemeralPublic = theirPublicKey
        messagesProcessed = 1
    }

    private fun writeMessage2AsResponder(): ByteArray {
        val (privateKey, publicKey) = generateEphemeral()
        symmetric.mixHash(publicKey)
        symmetric.mixKey(publicKey)
        val dh = Curve25519.x25519(privateKey, requireNotNull(remoteEphemeralPublic))
        symmetric.mixKey(dh)
        val tag = symmetric.encryptAndHash(ByteArray(0))
        messagesProcessed = 2
        return publicKey + tag
    }

    private fun readMessage2AsInitiator(message: ByteArray) {
        require(message.size == DH_LEN + ChaCha20Poly1305.TAG_SIZE) { "Malformed handshake message 2" }
        val theirPublicKey = message.copyOf(DH_LEN)
        symmetric.mixHash(theirPublicKey)
        symmetric.mixKey(theirPublicKey)
        val dh = Curve25519.x25519(requireNotNull(localEphemeralPrivate), theirPublicKey)
        symmetric.mixKey(dh)
        symmetric.decryptAndHash(message.copyOfRange(DH_LEN, message.size))
        messagesProcessed = 2
    }

    private fun generateEphemeral(): Pair<ByteArray, ByteArray> {
        val privateKey = ephemeralKeyGenerator()
        return privateKey to Curve25519.scalarMultBase(privateKey)
    }
}
