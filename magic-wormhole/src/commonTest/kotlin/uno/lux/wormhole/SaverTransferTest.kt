package uno.lux.wormhole

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import uno.lux.wormhole.rendezvous.FakeMailboxServer
import uno.lux.wormhole.transit.FakeInternet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Receiving through a [FileSaver]. */
class SaverTransferTest {
    /** Keeps saved files in memory and logs what the library asks for. */
    private class MemorySaver : FileSaver {
        val log = mutableListOf<String>()
        val files = mutableMapOf<String, Buffer>()
        var failure: Exception? = null

        override suspend fun createFile(
            name: String,
            size: Long,
        ): IncomingFile {
            failure?.let { throw it }
            log += "createFile $name $size"
            val buffer = Buffer().also { files[name] = it }
            return object : IncomingFile {
                override val sink: RawSink = buffer

                override suspend fun commit() = SavedFile(name, "memory:$name").also { log += "commit $name" }

                override suspend fun discard() {
                    log += "discard $name"
                }
            }
        }

        override suspend fun createFolder(name: String): IncomingFolder {
            log += "createFolder $name"
            return object : IncomingFolder {
                override fun createDirectory(path: String) = Unit

                override fun createFile(path: String): RawSink = Buffer().also { files["$name/$path"] = it }

                override suspend fun commit() = SavedFile(name, "memory:$name").also { log += "commit $name" }

                override suspend fun discard() {
                    log += "discard $name"
                }
            }
        }
    }

    private val server = FakeMailboxServer()
    private var internet: FakeInternet? = null
    private val saver = MemorySaver()

    private fun TestScope.wormhole(address: String): Wormhole {
        val internet = internet ?: FakeInternet(backgroundScope).also { internet = it }
        return Wormhole(
            WormholeConfig(transitRelay = "tcp:${FakeInternet.RELAY_HOST}:${FakeInternet.RELAY_PORT}"),
            rendezvousTransport = server.transport,
            transitNetwork = { internet.network(listOf(address)) },
        )
    }

    private fun content(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun entry(
        path: String,
        data: ByteArray,
    ) = OutgoingFile(path, data.size.toLong()) { Buffer().apply { write(data) } }

    /** Sends with [send] and receives with [onOffer]. Returns the receiver's events and the sender's error. */
    private suspend fun TestScope.transfer(
        send: Wormhole.() -> Flow<SendEvent>,
        onOffer: (ReceiveEvent.FileOffered) -> Unit = { it.acceptInto(saver) },
    ): Pair<List<ReceiveEvent>, Throwable?> {
        val code = CompletableDeferred<String>()
        val sender =
            async {
                runCatching {
                    wormhole("10.0.0.1").send().collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
                }
            }
        val received = mutableListOf<ReceiveEvent>()
        wormhole("10.0.0.2").receive(code.await()).collect { event ->
            received += event
            if (event is ReceiveEvent.FileOffered) onOffer(event)
        }
        return received to sender.await().exceptionOrNull()
    }

    @Test
    fun fileIsCreatedWrittenAndCommitted() =
        runTest {
            val data = content(50_000)
            val (events, error) =
                transfer(
                    { sendFile(OutgoingFile("photo.jpg", 50_000) { Buffer().apply { write(data) } }) },
                )

            assertEquals(null, error)
            assertEquals(listOf("createFile photo.jpg 50000", "commit photo.jpg"), saver.log)
            assertContentEquals(data, saver.files.getValue("photo.jpg").readByteArray())
            assertEquals(ReceiveEvent.FileReceived(SavedFile("photo.jpg", "memory:photo.jpg")), events.last())
        }

    @Test
    fun directoryIsUnpackedIntoAFolder() =
        runTest {
            val files = mapOf("a.txt" to content(1000), "sub/b.bin" to content(70_000))
            val (events, error) = transfer({ sendDirectory("holiday", files.map { (p, d) -> entry(p, d) }) })

            assertEquals(null, error)
            assertEquals(listOf("createFolder holiday", "commit holiday"), saver.log)
            files.forEach { (path, data) ->
                assertContentEquals(data, saver.files.getValue("holiday/$path").readByteArray())
            }
            assertEquals(ReceiveEvent.Unpacking, events[events.size - 2])
            assertEquals(ReceiveEvent.FileReceived(SavedFile("holiday", "memory:holiday")), events.last())
        }

    @Test
    fun directoryCanBeKeptAsAZip() =
        runTest {
            val (events, _) =
                transfer(
                    { sendDirectory("holiday", listOf(entry("a.txt", content(10)))) },
                    onOffer = { it.acceptInto(saver, unpack = false) },
                )
            val offer = assertIs<ReceiveEvent.FileOffered>(events.first())
            assertEquals(listOf("createFile holiday.zip ${offer.size}", "commit holiday.zip"), saver.log)
            assertTrue(events.none { it == ReceiveEvent.Unpacking })
        }

    @Test
    fun offeredNamesAreMadeSafeBeforeTheSaverSeesThem() =
        runTest {
            transfer({ sendFile(OutgoingFile("../../evil.txt", 1) { Buffer().apply { writeByte(1) } }) })
            assertEquals(listOf("createFile evil.txt 1", "commit evil.txt"), saver.log)
        }

    @Test
    fun saverFailureRejectsTheTransfer() =
        runTest {
            saver.failure = IOException("Disk full")
            val code = CompletableDeferred<String>()
            val sender =
                async {
                    runCatching {
                        wormhole(
                            "10.0.0.1",
                        ).sendFile(OutgoingFile("a.txt", 1) { Buffer().apply { writeByte(1) } }).collect {
                            if (it is SendEvent.CodeAllocated) code.complete(it.code)
                        }
                    }
                }
            val error =
                assertFailsWith<SaveFailedException> {
                    wormhole("10.0.0.2").receive(code.await()).collect {
                        if (it is ReceiveEvent.FileOffered) it.acceptInto(saver)
                    }
                }
            assertEquals("Disk full", error.cause?.message)
            assertIs<TransferRejectedException>(sender.await().exceptionOrNull())
        }

    @Test
    fun failedTransferDiscardsTheFile() =
        runTest {
            val brokenSource =
                object : RawSource {
                    var sent = 0L

                    override fun readAtMostTo(
                        sink: Buffer,
                        byteCount: Long,
                    ): Long {
                        if (sent >= 20_000) throw IOException("Disk error")
                        sink.write(ByteArray(10_000))
                        sent += 10_000
                        return 10_000
                    }

                    override fun close() = Unit
                }
            assertFailsWith<WormholeException> {
                transfer(
                    { sendFile(OutgoingFile("big.bin", 100_000) { brokenSource }) },
                )
            }
            assertEquals(listOf("createFile big.bin 100000", "discard big.bin"), saver.log)
        }
}
