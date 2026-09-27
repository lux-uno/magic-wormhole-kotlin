package uno.lux.wormhole

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import uno.lux.wormhole.zip.ZipWriter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FolderUnpackerTest {
    private class MemoryTarget : UnzipTarget {
        val files = mutableMapOf<String, Buffer>()

        override fun createDirectory(path: String) = Unit

        override fun createFile(path: String): RawSink = Buffer().also { files[path] = it }
    }

    private val temporary = Path(SystemTemporaryDirectory, "unpacker-${Random.nextLong().toULong()}")
    private val unpackers = listOf(FolderUnpacker.streaming(), FolderUnpacker.temporaryFile(temporary))

    private val files =
        mapOf(
            "a.txt" to "hello".encodeToByteArray(),
            "sub/b.bin" to ByteArray(150_000) { (it * 7).toByte() },
            "empty" to ByteArray(0),
        )

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val entries =
            files.map { (path, data) ->
                OutgoingFile(path, data.size.toLong()) { Buffer().apply { write(data) } }.checksummed()
            }
        return ZipWriter(entries).source().buffered().use { it.readByteArray() }
    }

    /** Writes [zip] in chunks of [chunk] bytes, like transit records. */
    private suspend fun ZipReceiver.writeAll(
        zip: ByteArray,
        chunk: Int,
    ) {
        for (start in zip.indices step chunk) write(zip.copyOfRange(start, minOf(start + chunk, zip.size)))
    }

    @Test
    fun bothUnpackersUnpackAZipThatArrivesInPieces() =
        runTest {
            val zip = zip(files)
            for (unpacker in unpackers) {
                for (chunk in listOf(7, 4096, zip.size)) {
                    val target = MemoryTarget()
                    var unpacking = false
                    unpacker.start(target, zip.size.toLong(), Long.MAX_VALUE, Int.MAX_VALUE).apply {
                        writeAll(zip, chunk)
                        finish { unpacking = true }
                    }
                    assertEquals(files.keys, target.files.keys, "$unpacker, $chunk")
                    files.forEach { (path, data) ->
                        assertContentEquals(data, target.files.getValue(path).readByteArray())
                    }
                    assertEquals(unpacker != FolderUnpacker.streaming(), unpacking, "$unpacker shows an unpacking step")
                }
            }
        }

    @Test
    fun aDamagedZipFails() =
        runTest {
            val zip = zip(files)
            zip[37] = (zip[37] + 1).toByte() // inside the data of a.txt
            for (unpacker in unpackers) {
                assertFailsWith<InvalidZipException>("$unpacker") {
                    unpacker.start(MemoryTarget(), zip.size.toLong(), Long.MAX_VALUE, Int.MAX_VALUE).apply {
                        writeAll(zip, 1000)
                        finish {}
                    }
                }
            }
        }

    @Test
    fun limitsApplyWhileStreaming() =
        runTest {
            val zip = zip(files)
            assertFailsWith<InvalidZipException> {
                FolderUnpacker
                    .streaming()
                    .start(
                        MemoryTarget(),
                        zip.size.toLong(),
                        maxBytes = 1000,
                        maxFiles = 10,
                    ).apply {
                        writeAll(zip, 1000)
                        finish {}
                    }
            }
        }

    @Test
    fun discardStopsAndLeavesNoTemporaryFile() =
        runTest {
            val zip = zip(files)
            for (unpacker in unpackers) {
                unpacker.start(MemoryTarget(), zip.size.toLong(), Long.MAX_VALUE, Int.MAX_VALUE).apply {
                    write(zip.copyOf(50_000))
                    discard()
                }
            }
            assertTrue(SystemFileSystem.list(temporary).isEmpty())
        }
}
