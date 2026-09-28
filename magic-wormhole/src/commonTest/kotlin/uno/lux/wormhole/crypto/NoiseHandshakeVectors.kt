@file:Suppress("ktlint:standard:max-line-length") // Generated test vectors.

package uno.lux.wormhole.crypto

// Cross-checked against a from-scratch Python reimplementation of the Noise Protocol
// Framework spec (noiseprotocol.org, section 5) for Noise_NNpsk0_25519_ChaChaPoly_BLAKE2s,
// built on Python's `cryptography` (X25519, ChaCha20-Poly1305) and `hashlib`/`hmac`
// (BLAKE2s) rather than hand-transcribed from any single source. No independently
// published test vector for this exact pattern was found (checked noise-c and the
// "cacophony" vector sets, which cover NN and PSK patterns but not NNpsk0 together).
// Ephemeral keys and the PSK below are fixed, non-random, test-only values.
internal object NoiseHandshakeVectors {
    class Vector(
        val label: String,
        val psk: String,
        val initiatorEphemeralPrivate: String,
        val responderEphemeralPrivate: String,
        val message1: String,
        val message2: String,
        val handshakeHash: String,
        val initiatorSendKey: String,
        val initiatorReceiveKey: String,
        val transportPlaintexts: List<String>,
        val transportCiphertexts: List<String>,
    )

    val all: List<Vector> =
        listOf(
            Vector(
                label = "case1",
                psk = "fa6332010a001e8b11747f38b4e4d29b934c04bbc93508d7c4c1c15b49ed8ba2",
                initiatorEphemeralPrivate = "938599172f6132319b42436a43d7eb9963b24e73b17cb2d383fed0bdc81e40cf",
                responderEphemeralPrivate = "763e42df8a38a9a68e781f1701b71484c83cd3db21b0a212a46ed353007fe37e",
                message1 = "36d1bc218d85b130b64433eed33fd3e76596c38bd982757877f158bf3947cc0547e9a86eb8ae5a0a095137007de59666",
                message2 = "e6b05f32f8996803de630bc7503f341dede20d87ddc748460ab905bd8f6c2b4a04e452e51079dffd188c6ff5534c96f6",
                handshakeHash = "550f2fa97c73ef016c6e67d8f0d0a262ede7778255619435fdcf2aefd3bdd94a",
                initiatorSendKey = "4275a7d1bf77a0f472915d78720f7f0d69e9577e05c323c7b704a9171385c3ea",
                initiatorReceiveKey = "75c4f86c18123622888dae2589f045e5636ffffcf77691b9d5d1d353abdf1a31",
                transportPlaintexts =
                    listOf(
                        "",
                        "acfeffb73e",
                        "4a2839f57fb35110fabf68da09f272d79064f2b1d0ea05fe1e7fddc294af670deef43974498e55b501eccb91be5fae182ed23d48a1566bf159aa279db64722ec9fda8bb2179d6917bab1a6c05a229e183d6c62ce8e369e95a7f378139d0978dc82008dab",
                    ),
                transportCiphertexts =
                    listOf(
                        "d109fdb37a344ae8a5c5ddb9e4788fd7",
                        "c8aaec4ed893d4d09c20313b96d663b16c3ad2cee7",
                        "dfb9b342715eefd0c8e80b31553c3abffbe052d30b6d890f4966560c593fcdc798027f8baea933959829d8e3bb04d064dfe722c09af35a01161682705a37552cf4998fe46d5d289ede6e2038a790a6cc0f47fa8ba9111c2e23d52de70d30667b0eb8c700147da76ffc88061ef9915724b737d7c8",
                    ),
            ),
            Vector(
                label = "case2",
                psk = "9eabfae6a708d1ac6e2762a6230ada2ab1336ea6e67ba9b9b3c4d0fad56ec225",
                initiatorEphemeralPrivate = "fb625d37420a2ccfbd04f2791cb0a64afcd81ea9049e4188d035ad598a41d94b",
                responderEphemeralPrivate = "5d898db7c53a31b6aef798c1d7e7cbe2a6e334e3dc9b95d57f338aafa23cf9d7",
                message1 = "cc2b4402710e30ffd5fd3e25dd1b4d6f35d6b5d6bf3b72a84ea25b45705f562178cb3b35745144d81697ef5fd9f33de2",
                message2 = "9be0b1f8a33ef29e3123adceb7a8cd6e37692b676e98532db01c93611e61c55f739a872ff6cf1dfe893c48177da2f696",
                handshakeHash = "989582c59f079b037c59774c82e6bd9fcca86abeef4df52b0ad29f5353a86c8c",
                initiatorSendKey = "9011b624ee8363a107bef7a7819c47a7c72f0d8b9dc979962d715c13d1c7263d",
                initiatorReceiveKey = "3a164546ea44daf8098e38735b52191bd8ca0ceb5183e0b063130ab493a8ebc6",
                transportPlaintexts = listOf("5e9186642deedeea6f3f8c71ddd39c93c1b692d2"),
                transportCiphertexts =
                    listOf(
                        "a98922b259b9033d476703058c70ae0650eba596fba430ec280102f82d9984e8c36fdc13",
                    ),
            ),
        )
}
