package uno.lux.wormhole.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ChaCha20Poly1305Test {
    @Test
    fun sealMatchesReferenceImplementation() {
        for (v in ChaCha20Poly1305Vectors.all) {
            val sealed =
                ChaCha20Poly1305.seal(
                    v.key.hexToByteArray(),
                    v.nonce.hexToByteArray(),
                    v.plaintext.hexToByteArray(),
                    v.aad.hexToByteArray(),
                )
            assertContentEquals(v.sealed.hexToByteArray(), sealed, v.label)
        }
    }

    @Test
    fun openMatchesReferenceImplementation() {
        for (v in ChaCha20Poly1305Vectors.all) {
            val plaintext =
                ChaCha20Poly1305.open(
                    v.key.hexToByteArray(),
                    v.nonce.hexToByteArray(),
                    v.sealed.hexToByteArray(),
                    v.aad.hexToByteArray(),
                )
            assertContentEquals(v.plaintext.hexToByteArray(), plaintext, v.label)
        }
    }

    @Test
    fun openRejectsTamperedCiphertext() {
        val v = ChaCha20Poly1305Vectors.all.last()
        val sealed = v.sealed.hexToByteArray()
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertFailsWith<DecryptionException> {
            ChaCha20Poly1305.open(v.key.hexToByteArray(), v.nonce.hexToByteArray(), sealed, v.aad.hexToByteArray())
        }
    }

    @Test
    fun openRejectsWrongAad() {
        val v = ChaCha20Poly1305Vectors.all.first { it.aad.isNotEmpty() }
        assertFailsWith<DecryptionException> {
            ChaCha20Poly1305.open(
                v.key.hexToByteArray(),
                v.nonce.hexToByteArray(),
                v.sealed.hexToByteArray(),
                ByteArray(0),
            )
        }
    }

    @Test
    fun openRejectsTooShortMessage() {
        assertFailsWith<DecryptionException> {
            ChaCha20Poly1305.open(ByteArray(32), ByteArray(12), ByteArray(15))
        }
    }
}
