package uno.lux.wormhole

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uno.lux.wormhole.rendezvous.FakeMailboxServer
import uno.lux.wormhole.rendezvous.RendezvousClient
import uno.lux.wormhole.transit.FakeInternet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileTransferTest {
    private val server = FakeMailboxServer()

    private fun kotlinx.coroutines.test.TestScope.wormhole(address: String): Wormhole {
        val internet = sharedInternet ?: FakeInternet(backgroundScope).also { sharedInternet = it }
        return Wormhole(
            WormholeConfig(transitRelay = "tcp:${FakeInternet.RELAY_HOST}:${FakeInternet.RELAY_PORT}"),
            rendezvousTransport = server.transport,
            transitNetwork = { internet.network(listOf(address)) },
        )
    }

    private var sharedInternet: FakeInternet? = null

    private fun content(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    private suspend fun kotlinx.coroutines.test.TestScope.transfer(data: ByteArray): Pair<List<SendEvent>, List<ReceiveEvent>> {
        val code = CompletableDeferred<String>()
        val sender = async {
            val events = mutableListOf<SendEvent>()
            wormhole("10.0.0.1").sendFile("photo.jpg", data.size.toLong(), Buffer().apply { write(data) }).collect {
                events += it
                if (it is SendEvent.CodeAllocated) code.complete(it.code)
            }
            events
        }
        val sink = Buffer()
        val received = mutableListOf<ReceiveEvent>()
        wormhole("10.0.0.2").receive(code.await()).collect { event ->
            received += event
            if (event is ReceiveEvent.FileOffered) event.accept(sink)
        }
        assertContentEquals(data, sink.readByteArray())
        return sender.await() to received
    }

    @Test
    fun fileGoesFromSenderToReceiver() = runTest {
        val data = content(200_000)
        val (sent, received) = transfer(data)

        assertIs<SendEvent.CodeAllocated>(sent.first())
        assertEquals(SendEvent.Progress(200_000, 200_000), sent[sent.size - 2])
        assertEquals(SendEvent.Completed, sent.last())

        val offer = received.first()
        assertIs<ReceiveEvent.FileOffered>(offer)
        assertEquals("photo.jpg", offer.name)
        assertEquals(200_000, offer.size)
        assertEquals(false, offer.isDirectory)
        assertEquals(ReceiveEvent.Progress(200_000, 200_000), received[received.size - 2])
        assertEquals(ReceiveEvent.FileReceived, received.last())
    }

    @Test
    fun emptyFileWorks() = runTest {
        val (sent, received) = transfer(ByteArray(0))
        assertEquals(SendEvent.Completed, sent.last())
        assertEquals(ReceiveEvent.FileReceived, received.last())
    }

    @Test
    fun receiverRejectReachesTheSender() = runTest {
        val code = CompletableDeferred<String>()
        val sender = async {
            runCatching {
                wormhole("10.0.0.1").sendFile("a.txt", 3, Buffer().apply { write(byteArrayOf(1, 2, 3)) }).collect {
                    if (it is SendEvent.CodeAllocated) code.complete(it.code)
                }
            }
        }
        val received = mutableListOf<ReceiveEvent>()
        wormhole("10.0.0.2").receive(code.await()).collect { event ->
            received += event
            if (event is ReceiveEvent.FileOffered) event.reject()
        }
        assertEquals(1, received.size)
        assertIs<TransferRejectedException>(sender.await().exceptionOrNull())
    }

    @Test
    fun directoryOffersArriveAsZipFiles() = runTest {
        val client = RendezvousClient.connect(server.transport, "ws://fake", DEFAULT_APP_ID, this)
        client.bind()
        val nameplate = client.allocate()
        client.open(client.claim(nameplate))
        val code = "$nameplate-a-b"
        val receiver = async {
            val events = mutableListOf<ReceiveEvent>()
            wormhole("10.0.0.2").receive(code).collect { e ->
                events += e
                if (e is ReceiveEvent.FileOffered) e.reject()
            }
            events
        }
        val session = WormholeSession(client, DEFAULT_APP_ID)
        session.exchangeKeys(code)
        session.send(buildJsonObject {
            put("offer", buildJsonObject {
                put("directory", buildJsonObject {
                    put("mode", "zipfile/deflated")
                    put("dirname", "holiday")
                    put("zipsize", 1234)
                    put("numbytes", 5000)
                    put("numfiles", 3)
                })
            })
        })
        val offer = receiver.await().single()
        assertIs<ReceiveEvent.FileOffered>(offer)
        assertEquals("holiday.zip", offer.name)
        assertEquals(1234, offer.size)
        assertEquals(true, offer.isDirectory)
        client.shutdown()
    }
}
