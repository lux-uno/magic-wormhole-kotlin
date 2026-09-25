package uno.lux.wormhole.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class Spake2Test {
    private val password = "4-purple-sausages".encodeToByteArray()
    private val appId = "lothar.com/wormhole/text-or-file-xfer".encodeToByteArray()

    private fun spake(entropy: String, pw: ByteArray = password) =
        Spake2Symmetric(pw, appId, random = { size -> entropy.hexToByteArray().copyOf(size) })

    @Test
    fun outboundMessagesMatchPython() {
        assertEquals(Spake2Vectors.ALICE_MSG, spake(Spake2Vectors.ALICE_ENTROPY).start().toHexString())
        assertEquals(Spake2Vectors.BOB_MSG, spake(Spake2Vectors.BOB_ENTROPY).start().toHexString())
    }

    @Test
    fun bothSidesDeriveThePythonKey() {
        val alice = spake(Spake2Vectors.ALICE_ENTROPY)
        val bob = spake(Spake2Vectors.BOB_ENTROPY)
        val aliceMsg = alice.start()
        val bobMsg = bob.start()
        assertEquals(Spake2Vectors.SHARED_KEY, alice.finish(bobMsg).toHexString())
        assertEquals(Spake2Vectors.SHARED_KEY, bob.finish(aliceMsg).toHexString())
    }

    @Test
    fun randomSidesAgree() {
        val a = Spake2Symmetric(password, appId)
        val b = Spake2Symmetric(password, appId)
        val ma = a.start()
        val mb = b.start()
        assertContentEquals(a.finish(mb), b.finish(ma))
    }

    @Test
    fun wrongPasswordGivesDifferentKeys() {
        val a = Spake2Symmetric(password, appId)
        val b = Spake2Symmetric("4-purple-sausage".encodeToByteArray(), appId)
        val ma = a.start()
        val mb = b.start()
        assertFalse(a.finish(mb).contentEquals(b.finish(ma)))
    }

    @Test
    fun rejectsReflectedMessage() {
        val a = Spake2Symmetric(password, appId)
        val ma = a.start()
        assertFailsWith<IllegalArgumentException> { a.finish(ma) }
    }

    @Test
    fun rejectsWrongSideByte() {
        val a = spake(Spake2Vectors.ALICE_ENTROPY)
        a.start()
        val fromA = Spake2Vectors.BOB_MSG.hexToByteArray().also { it[0] = 'A'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { a.finish(fromA) }
    }
}
