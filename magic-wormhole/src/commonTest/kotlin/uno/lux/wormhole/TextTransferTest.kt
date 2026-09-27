package uno.lux.wormhole

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import uno.lux.wormhole.rendezvous.ClientMessage
import uno.lux.wormhole.rendezvous.FakeMailboxServer
import uno.lux.wormhole.rendezvous.RendezvousClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextTransferTest {
    private val server = FakeMailboxServer()

    private fun wormhole(codeLength: Int = 2) =
        Wormhole(WormholeConfig(codeLength = codeLength), rendezvousTransport = server.transport)

    @Test
    fun textGoesFromSenderToReceiver() =
        runTest {
            val codeEvents = CompletableDeferred<String>()
            val sender =
                async {
                    val events = mutableListOf<SendEvent>()
                    wormhole().sendText("hello, wormhole").collect { e ->
                        events += e
                        if (e is SendEvent.CodeAllocated) codeEvents.complete(e.code)
                    }
                    events
                }
            val received = wormhole().receive(codeEvents.await()).toList()
            assertEquals(listOf<ReceiveEvent>(ReceiveEvent.TextReceived("hello, wormhole")), received)
            val senderEvents = sender.await()
            assertIs<SendEvent.CodeAllocated>(senderEvents.first())
            assertEquals(SendEvent.Completed, senderEvents.last())
        }

    @Test
    fun bothSidesCloseTheMailboxHappily() =
        runTest {
            val code = CompletableDeferred<String>()
            val sender =
                launch {
                    wormhole().sendText("x").collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
                }
            wormhole().receive(code.await()).toList()
            sender.join()
            val closes = server.received.filterIsInstance<ClientMessage.Close>()
            assertEquals(listOf("happy", "happy"), closes.map { it.mood })
            assertTrue(server.received.any { it is ClientMessage.Release })
        }

    @Test
    fun codeHasTheConfiguredNumberOfWords() =
        runTest {
            val code = CompletableDeferred<String>()
            val sender =
                launch {
                    wormhole(
                        codeLength = 3,
                    ).sendText("x").collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
                }
            val c = code.await()
            assertEquals(4, c.split("-").size, c)
            sender.cancel()
        }

    @Test
    fun wrongCodeFailsBothSides() =
        runTest {
            val code = CompletableDeferred<String>()
            val sender =
                async {
                    runCatching {
                        wormhole()
                            .sendText(
                                "secret",
                            ).collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
                    }
                }
            val nameplate = code.await().substringBefore("-")
            assertFailsWith<WrongCodeException> { wormhole().receive("$nameplate-wrong-words").toList() }
            assertIs<WrongCodeException>(sender.await().exceptionOrNull())
            assertTrue(server.received.filterIsInstance<ClientMessage.Close>().all { it.mood == "scary" })
        }

    @Test
    fun invalidCodeFailsBeforeConnecting() =
        runTest {
            assertFailsWith<InvalidCodeException> { wormhole().receive("not a code").toList() }
            assertTrue(server.connections.isEmpty())
        }

    @Test
    fun receiverErrorIsReportedToTheSender() =
        runTest {
            val code = CompletableDeferred<String>()
            val sender =
                async {
                    runCatching {
                        wormhole().sendText("x").collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
                    }
                }
            // A handwritten receiver that answers the offer with an error.
            val client = RendezvousClient.connect(server.transport, "ws://fake", DEFAULT_APP_ID, this)
            client.bind()
            val c = code.await()
            client.open(client.claim(c.substringBefore("-")))
            val session = WormholeSession(client, DEFAULT_APP_ID)
            session.exchangeKeys(c)
            session.receive()
            session.send(buildJsonObject { put("error", "transfer rejected") })
            assertIs<TransferRejectedException>(sender.await().exceptionOrNull())
            client.shutdown()
        }

    @Test
    fun senderErrorIsReportedToTheReceiver() =
        runTest {
            val client = RendezvousClient.connect(server.transport, "ws://fake", DEFAULT_APP_ID, this)
            client.bind()
            val nameplate = client.allocate()
            client.open(client.claim(nameplate))
            val code = "$nameplate-a-b"
            val receiver = async { runCatching { wormhole().receive(code).toList() } }
            val session = WormholeSession(client, DEFAULT_APP_ID)
            session.exchangeKeys(code)
            session.send(buildJsonObject { put("error", "something broke") })
            val e = receiver.await().exceptionOrNull()
            assertIs<PeerErrorException>(e)
            assertEquals("something broke", e.peerMessage)
            client.shutdown()
        }

    @Test
    fun receiverAcknowledgesTextWithMessageAck() =
        runTest {
            val client = RendezvousClient.connect(server.transport, "ws://fake", DEFAULT_APP_ID, this)
            client.bind()
            val nameplate = client.allocate()
            client.open(client.claim(nameplate))
            val code = "$nameplate-a-b"
            val receiver = async { wormhole().receive(code).toList() }
            val session = WormholeSession(client, DEFAULT_APP_ID)
            session.exchangeKeys(code)
            session.send(buildJsonObject { put("offer", buildJsonObject { put("message", "hi") }) })
            val answer = session.receive()
            assertEquals(JsonPrimitive("ok"), answer["answer"]!!.jsonObject["message_ack"])
            assertEquals(listOf<ReceiveEvent>(ReceiveEvent.TextReceived("hi")), receiver.await())
            client.shutdown()
        }
}
