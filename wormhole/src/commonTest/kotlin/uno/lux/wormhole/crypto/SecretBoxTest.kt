package uno.lux.wormhole.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class SecretBoxTest {
    @Test
    fun sealMatchesPyNaCl() {
        for (v in SecretBoxVectors.all) {
            val box = SecretBox.seal(v.message.hexToByteArray(), v.nonce.hexToByteArray(), v.key.hexToByteArray())
            assertContentEquals(v.box.hexToByteArray(), box, "message length ${v.message.length / 2}")
        }
    }

    @Test
    fun openMatchesPyNaCl() {
        for (v in SecretBoxVectors.all) {
            val message = SecretBox.open(v.box.hexToByteArray(), v.nonce.hexToByteArray(), v.key.hexToByteArray())
            assertContentEquals(v.message.hexToByteArray(), message, "message length ${v.message.length / 2}")
        }
    }

    @Test
    fun openRejectsTamperedBox() {
        val v = SecretBoxVectors.all.last()
        val box = v.box.hexToByteArray()
        box[box.size / 2] = (box[box.size / 2].toInt() xor 1).toByte()
        assertFailsWith<DecryptionException> {
            SecretBox.open(box, v.nonce.hexToByteArray(), v.key.hexToByteArray())
        }
    }

    @Test
    fun openRejectsWrongKey() {
        val v = SecretBoxVectors.all.last()
        assertFailsWith<DecryptionException> {
            SecretBox.open(v.box.hexToByteArray(), v.nonce.hexToByteArray(), ByteArray(32))
        }
    }

    @Test
    fun openRejectsTooShortBox() {
        assertFailsWith<DecryptionException> {
            SecretBox.open(ByteArray(15), ByteArray(24), ByteArray(32))
        }
    }
}
