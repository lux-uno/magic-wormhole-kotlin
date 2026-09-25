package uno.lux.wormhole.rendezvous

import kotlinx.coroutines.test.runTest
import uno.lux.wormhole.ServerConnectionException
import uno.lux.wormhole.WormholeServerException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RendezvousClientTest {
    private val appId = "test/app"

    @Test
    fun bindSendsAppIdAndSide() =
        runTest {
            val server = FakeMailboxServer()
            val client = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
            client.bind()
            val bind = server.received.first()
            assertIs<ClientMessage.Bind>(bind)
            assertEquals(appId, bind.appId)
            assertEquals(client.side, bind.side)
            assertEquals(10, client.side.length)
            client.shutdown()
        }

    @Test
    fun twoClientsExchangeMessagesThroughAMailbox() =
        runTest {
            val server = FakeMailboxServer()
            val sender = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
            val receiver = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
            sender.bind()
            receiver.bind()

            val nameplate = sender.allocate()
            assertEquals("1", nameplate)
            val mailbox = sender.claim(nameplate)
            sender.open(mailbox)
            assertEquals(mailbox, receiver.claim(nameplate))
            receiver.open(mailbox)

            sender.add("pake", byteArrayOf(1, 2))
            receiver.add("pake", byteArrayOf(3, 4))

            val atReceiver = receiver.receive()
            assertEquals(sender.side, atReceiver.side)
            assertContentEquals(byteArrayOf(1, 2), atReceiver.body)
            // The server echoes our own messages; the client skips them.
            val atSender = sender.receive()
            assertEquals(receiver.side, atSender.side)
            assertContentEquals(byteArrayOf(3, 4), atSender.body)

            sender.release(nameplate)
            sender.close(mailbox, "happy")
            receiver.close(mailbox, "happy")
            assertTrue(server.received.any { it is ClientMessage.Close && it.mood == "happy" })
        }

    @Test
    fun welcomeErrorFailsBind() =
        runTest {
            val server = FakeMailboxServer(welcomeError = "server is closed")
            val client = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
            val e = assertFailsWith<WormholeServerException> { client.bind() }
            assertEquals("server is closed", e.serverError)
            client.shutdown()
        }

    @Test
    fun serverErrorFailsThePendingRequest() =
        runTest {
            val server = FakeMailboxServer(maxSidesPerNameplate = 1)
            val a = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
            val b = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
            a.bind()
            b.bind()
            a.claim("5")
            val e = assertFailsWith<WormholeServerException> { b.claim("5") }
            assertEquals("crowded", e.serverError)
            a.shutdown()
            b.shutdown()
        }

    @Test
    fun lostConnectionFailsReceive() =
        runTest {
            val server = FakeMailboxServer()
            val client = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
            client.bind()
            server.dropAllConnections()
            assertFailsWith<ServerConnectionException> { client.receive() }
            client.shutdown()
        }
}
