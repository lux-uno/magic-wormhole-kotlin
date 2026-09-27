package uno.lux.wormhole

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import uno.lux.wormhole.rendezvous.FakeMailboxServer
import uno.lux.wormhole.transit.FakeInternet
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Sending and receiving files and folders by [Path]. */
class PathTransferTest {
    private val server = FakeMailboxServer()
    private var internet: FakeInternet? = null
    private val root = Path(SystemTemporaryDirectory, "wormhole-test-${Random.nextLong().toULong()}")
    private val outbox = Path(root, "outbox").also { SystemFileSystem.createDirectories(it) }
    private val inbox = Path(root, "inbox").also { SystemFileSystem.createDirectories(it) }

    @AfterTest
    fun cleanUp() = deleteRecursively(root)

    private fun deleteRecursively(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach(::deleteRecursively)
        }
        SystemFileSystem.delete(path, mustExist = false)
    }

    private fun TestScope.wormhole(address: String): Wormhole {
        val internet = internet ?: FakeInternet(backgroundScope).also { internet = it }
        return Wormhole(
            WormholeConfig(transitRelay = "tcp:${FakeInternet.RELAY_HOST}:${FakeInternet.RELAY_PORT}"),
            rendezvousTransport = server.transport,
            transitNetwork = { internet.network(listOf(address)) },
        )
    }

    private fun write(
        path: Path,
        data: ByteArray,
    ) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(path).buffered().use { it.write(data) }
    }

    private fun read(path: Path) = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    private fun content(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    private val received = mutableListOf<ReceiveEvent>()

    /** Sends with [send], receives into [inbox], and returns the sender's error, if any. */
    private suspend fun TestScope.transfer(send: Wormhole.() -> kotlinx.coroutines.flow.Flow<SendEvent>): Throwable? {
        val code = CompletableDeferred<String>()
        val sender =
            async {
                runCatching {
                    wormhole("10.0.0.1").send().collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
                }
            }
        wormhole("10.0.0.2").receive(code.await()).collect { event ->
            received += event
            if (event is ReceiveEvent.FileOffered) event.acceptInto(inbox)
        }
        return sender.await().exceptionOrNull()
    }

    @Test
    fun fileGoesFromPathToFolder() =
        runTest {
            val data = content(100_000)
            write(Path(outbox, "photo.jpg"), data)

            assertNull(transfer { sendFile(Path(outbox, "photo.jpg")) })

            assertContentEquals(data, read(Path(inbox, "photo.jpg")))
            assertEquals(
                ReceiveEvent.FileReceived(SavedFile("photo.jpg", Path(inbox, "photo.jpg").toString())),
                received.last(),
            )
            assertEquals(listOf("photo.jpg"), SystemFileSystem.list(inbox).map { it.name })
        }

    @Test
    fun fileCanBeSentUnderAnotherName() =
        runTest {
            write(Path(outbox, "IMG_0001.jpg"), content(10))
            transfer { sendFile(Path(outbox, "IMG_0001.jpg"), name = "beach.jpg") }
            assertContentEquals(content(10), read(Path(inbox, "beach.jpg")))
        }

    @Test
    fun folderGoesFromPathToFolder() =
        runTest {
            val folder = Path(outbox, "holiday")
            write(Path(folder, "a.txt"), content(1000))
            write(Path(folder, "sub", "deeper", "b.bin"), content(70_000))

            assertNull(transfer { sendDirectory(folder) })

            assertContentEquals(content(1000), read(Path(inbox, "holiday", "a.txt")))
            assertEquals(
                ReceiveEvent.FileReceived(SavedFile("holiday", Path(inbox, "holiday").toString())),
                received.last(),
            )
            assertContentEquals(content(70_000), read(Path(inbox, "holiday", "sub", "deeper", "b.bin")))
            assertEquals(listOf("holiday"), SystemFileSystem.list(inbox).map { it.name })
        }

    @Test
    fun receiverGivesANewNameInsteadOfOverwriting() =
        runTest {
            write(Path(outbox, "notes.txt"), content(10))
            SystemFileSystem.sink(Path(inbox, "notes.txt")).buffered().use { it.writeString("mine") }

            assertNull(transfer { sendFile(Path(outbox, "notes.txt")) })

            assertEquals("mine", read(Path(inbox, "notes.txt")).decodeToString())
            assertContentEquals(content(10), read(Path(inbox, "notes (1).txt")))
        }

    @Test
    fun sendingAMissingFileFailsWhenCollected() =
        runTest {
            val flow = wormhole("10.0.0.1").sendFile(Path(outbox, "missing.txt"))
            assertFailsWith<FileNotFoundException> { flow.collect {} }
        }

    @Test
    fun unzipUnpacksAZipFileIntoAFolder() =
        runTest {
            val zip = zipOf(mapOf("a.txt" to content(5), "sub/b.txt" to content(7)))
            write(Path(root, "x.zip"), zip)

            unzip(Path(root, "x.zip"), Path(inbox, "x"))

            assertContentEquals(content(5), read(Path(inbox, "x", "a.txt")))
            assertContentEquals(content(7), read(Path(inbox, "x", "sub", "b.txt")))
        }

    private fun zipOf(files: Map<String, ByteArray>): ByteArray {
        val writer =
            uno.lux.wormhole.zip.ZipWriter(
                files.map { (path, data) ->
                    OutgoingFile(path, data.size.toLong()) { kotlinx.io.Buffer().apply { write(data) } }.checksummed()
                },
            )
        return writer.source().buffered().use { it.readByteArray() }
    }
}
