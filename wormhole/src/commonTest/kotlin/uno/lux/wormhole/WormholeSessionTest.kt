package uno.lux.wormhole

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import uno.lux.wormhole.crypto.SecretBox
import uno.lux.wormhole.crypto.hkdfSha256
import uno.lux.wormhole.crypto.sha256
import uno.lux.wormhole.rendezvous.ClientMessage
import uno.lux.wormhole.rendezvous.FakeMailboxServer
import uno.lux.wormhole.rendezvous.RendezvousClient
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WormholeSessionTest {
    private val appId = DEFAULT_APP_ID

    private suspend fun kotlinx.coroutines.CoroutineScope.pair(
        server: FakeMailboxServer,
    ): Pair<RendezvousClient, RendezvousClient> {
        val a = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
        val b = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
        a.bind(); b.bind()
        val nameplate = a.allocate()
        a.open(a.claim(nameplate))
        b.open(b.claim(nameplate))
        return a to b
    }

    @Test
    fun bothSidesAgreeOnTheKeyAndExchangeEncryptedMessages() = runTest {
        val server = FakeMailboxServer()
        val (a, b) = pair(server)
        val sa = WormholeSession(a, appId)
        val sb = WormholeSession(b, appId)
        val ka = async { sa.exchangeKeys("1-purple-sausages") }
        val kb = async { sb.exchangeKeys("1-purple-sausages") }
        ka.await(); kb.await()
        assertContentEquals(sa.deriveKey("test"), sb.deriveKey("test"))

        sa.send(buildJsonObject { put("offer", buildJsonObject { put("message", "hello") }) })
        sa.send(buildJsonObject { put("second", 2) })
        assertEquals("""{"offer":{"message":"hello"}}""", sb.receive().toString())
        assertEquals("""{"second":2}""", sb.receive().toString())
        a.shutdown(); b.shutdown()
    }

    @Test
    fun pakeAndPhaseMessagesUseTheMagicWormholeFormat() = runTest {
        val server = FakeMailboxServer()
        val (a, b) = pair(server)
        val sa = WormholeSession(a, appId)
        val sb = WormholeSession(b, appId)
        val ka = async { sa.exchangeKeys("1-a") }
        sb.exchangeKeys("1-a")
        ka.await()
        sa.send(buildJsonObject { put("x", 1) })

        val adds = server.received.filterIsInstance<ClientMessage.Add>()
        val pake = Json.parseToJsonElement(adds.first { it.phase == "pake" }.body.decodeToString()).jsonObject
        assertEquals(setOf("pake_v1"), pake.keys)
        assertEquals(33 * 2, pake["pake_v1"].toString().trim('"').length)

        // Phase "0" from side a is encrypted with HKDF(key, "wormhole:phase:" + sha256(side) + sha256(phase)).
        val body = adds.first { it.phase == "0" }.body
        val phaseKey = hkdfSha256(
            sa.keyForTests(), ByteArray(0),
            "wormhole:phase:".encodeToByteArray() + sha256(a.side.encodeToByteArray()) + sha256("0".encodeToByteArray()),
            32,
        )
        val plain = SecretBox.open(body.copyOfRange(24, body.size), body.copyOfRange(0, 24), phaseKey)
        assertEquals("""{"x":1}""", plain.decodeToString())
        assertTrue(adds.any { it.phase == "version" })
        a.shutdown(); b.shutdown()
    }

    @Test
    fun wrongCodeIsDetectedOnBothSides() = runTest {
        val server = FakeMailboxServer()
        val (a, b) = pair(server)
        val ea = async { runCatching { WormholeSession(a, appId).exchangeKeys("1-right") } }
        val eb = async { runCatching { WormholeSession(b, appId).exchangeKeys("1-wrong") } }
        assertTrue(ea.await().exceptionOrNull() is WrongCodeException)
        assertTrue(eb.await().exceptionOrNull() is WrongCodeException)
        a.shutdown(); b.shutdown()
    }

    @Test
    fun badPakeMessageIsReported() = runTest {
        val server = FakeMailboxServer()
        val (a, b) = pair(server)
        b.add("pake", """{"pake_v1":"00"}""".encodeToByteArray())
        assertFailsWith<WormholeProtocolException> { WormholeSession(a, appId).exchangeKeys("1-a") }
        a.shutdown(); b.shutdown()
    }

}
