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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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

    private suspend fun kotlinx.coroutines.test.TestScope.transfer(
        data: ByteArray,
    ): Pair<List<SendEvent>, List<ReceiveEvent>> {
        val code = CompletableDeferred<String>()
        val sender =
            async {
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
    fun fileGoesFromSenderToReceiver() =
        runTest {
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
            assertEquals(null, offer.fileCount)
            assertEquals(ReceiveEvent.Progress(200_000, 200_000), received[received.size - 2])
            assertEquals(ReceiveEvent.FileReceived(saved = null), received.last())
        }

    @Test
    fun emptyFileWorks() =
        runTest {
            val (sent, received) = transfer(ByteArray(0))
            assertEquals(SendEvent.Completed, sent.last())
            assertEquals(ReceiveEvent.FileReceived(saved = null), received.last())
        }

    @Test
    fun receiverRejectReachesTheSender() =
        runTest {
            val code = CompletableDeferred<String>()
            val sender =
                async {
                    runCatching {
                        wormhole(
                            "10.0.0.1",
                        ).sendFile("a.txt", 3, Buffer().apply { write(byteArrayOf(1, 2, 3)) }).collect {
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

    private fun entry(
        path: String,
        data: ByteArray,
    ) = DirectoryEntry(path, data.size.toLong()) { Buffer().apply { write(data) } }

    @Test
    fun directoryGoesFromSenderToReceiverAsAZip() =
        runTest {
            val files = mapOf("a.txt" to content(1000), "sub/b.bin" to content(70_000))
            val code = CompletableDeferred<String>()
            val sender =
                async {
                    val events = mutableListOf<SendEvent>()
                    wormhole("10.0.0.1").sendDirectory("holiday", files.map { (p, d) -> entry(p, d) }).collect {
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
            assertEquals(SendEvent.Completed, sender.await().last())

            val offer = received.first()
            assertIs<ReceiveEvent.FileOffered>(offer)
            assertEquals("holiday.zip", offer.name)
            assertEquals(true, offer.isDirectory)
            assertEquals(2, offer.fileCount)
            assertEquals(71_000, offer.unpackedSize)
            assertEquals(offer.size, sink.size)
            assertEquals(ReceiveEvent.FileReceived(saved = null), received.last())

            val unpacked = mutableMapOf<String, Buffer>()
            val zip = sink.readByteArray()
            unzip(
                zip.size.toLong(),
                { offset -> Buffer().apply { write(zip, offset.toInt(), zip.size) } },
                object : UnzipTarget {
                    override fun createDirectory(path: String) = Unit

                    override fun createFile(path: String) = Buffer().also { unpacked[path] = it }
                },
                maxBytes = offer.unpackedSize!!,
                maxFiles = offer.fileCount!!,
            )
            assertEquals(files.keys, unpacked.keys)
            files.forEach { (path, data) -> assertContentEquals(data, unpacked.getValue(path).readByteArray()) }
        }

    @Test
    fun directoryEntriesMustBeSafeRelativePaths() {
        val w = Wormhole()
        for (bad in listOf("", "/abs", "../up", "a/../b", "a//b", "dir/", "back\\slash", "./a")) {
            assertFailsWith<IllegalArgumentException>(bad) { w.sendDirectory("d", listOf(entry(bad, ByteArray(1)))) }
        }
        assertFailsWith<IllegalArgumentException> {
            w.sendDirectory("d", listOf(entry("same", ByteArray(1)), entry("same", ByteArray(1))))
        }
        assertFailsWith<IllegalArgumentException> { w.sendDirectory("a/b", listOf(entry("x", ByteArray(1)))) }
    }

    @Test
    fun directoryOffersArriveAsZipFiles() =
        runTest {
            val client = RendezvousClient.connect(server.transport, "ws://fake", DEFAULT_APP_ID, this)
            client.bind()
            val nameplate = client.allocate()
            client.open(client.claim(nameplate))
            val code = "$nameplate-a-b"
            val receiver =
                async {
                    val events = mutableListOf<ReceiveEvent>()
                    wormhole("10.0.0.2").receive(code).collect { e ->
                        events += e
                        if (e is ReceiveEvent.FileOffered) e.reject()
                    }
                    events
                }
            val session = WormholeSession(client, DEFAULT_APP_ID)
            session.exchangeKeys(code)
            session.send(
                buildJsonObject {
                    put(
                        "offer",
                        buildJsonObject {
                            put(
                                "directory",
                                buildJsonObject {
                                    put("mode", "zipfile/deflated")
                                    put("dirname", "holiday")
                                    put("zipsize", 1234)
                                    put("numbytes", 5000)
                                    put("numfiles", 3)
                                },
                            )
                        },
                    )
                },
            )
            val offer = receiver.await().single()
            assertIs<ReceiveEvent.FileOffered>(offer)
            assertEquals("holiday.zip", offer.name)
            assertEquals(1234, offer.size)
            assertEquals(true, offer.isDirectory)
            assertEquals(3, offer.fileCount)
            assertEquals(5000, offer.unpackedSize)
            client.shutdown()
        }
}
