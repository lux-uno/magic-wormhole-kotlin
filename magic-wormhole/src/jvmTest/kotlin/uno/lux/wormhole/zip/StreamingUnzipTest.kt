package uno.lux.wormhole.zip

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe
import kotlinx.io.writeLongLe
import kotlinx.io.writeShortLe
import uno.lux.wormhole.InvalidZipException
import uno.lux.wormhole.StreamingZipReader
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Runs all [UnzipTest] cases through [StreamingZipReader], plus cases only streaming has. */
class StreamingUnzipTest : UnzipTest() {
    override suspend fun unzipBytes(
        zip: ByteArray,
        maxBytes: Long,
        maxFiles: Int,
    ): MemoryTarget =
        MemoryTarget().also { target ->
            StreamingZipReader(Buffer().apply { write(zip) }, target, maxBytes, maxFiles).readAll()
        }

    /**
     * Stored entries whose sizes are only in data descriptors, like zipstream-ng (the `wormhole`
     * CLI since 0.17). With [zip64], the descriptors have 8-byte sizes.
     */
    private fun storedWithDescriptors(
        entries: Map<String, ByteArray>,
        zip64: Boolean = false,
    ): ByteArray {
        val out = Buffer()
        val offsets = mutableListOf<Int>()
        for ((path, data) in entries) {
            offsets += out.size.toInt()
            val name = path.encodeToByteArray()
            out.writeIntLe(0x04034b50)
            out.writeShortLe(45)
            out.writeShortLe(8) // flags: data descriptor
            out.writeShortLe(0) // stored
            out.writeIntLe(0)
            out.writeIntLe(0)
            out.writeIntLe(0)
            out.writeIntLe(0)
            out.writeShortLe(name.size.toShort())
            out.writeShortLe(0)
            out.write(name)
            out.write(data)
            out.writeIntLe(0x08074b50)
            out.writeIntLe(crc(data))
            if (zip64) {
                out.writeLongLe(data.size.toLong())
                out.writeLongLe(data.size.toLong())
            } else {
                out.writeIntLe(data.size)
                out.writeIntLe(data.size)
            }
        }
        val centralOffset = out.size.toInt()
        entries.entries.forEachIndexed { i, (path, data) ->
            val name = path.encodeToByteArray()
            out.writeIntLe(0x02014b50)
            out.writeShortLe(45)
            out.writeShortLe(45)
            out.writeShortLe(8)
            out.writeShortLe(0)
            out.writeIntLe(0)
            out.writeIntLe(crc(data))
            out.writeIntLe(data.size)
            out.writeIntLe(data.size)
            out.writeShortLe(name.size.toShort())
            out.writeShortLe(0)
            out.writeShortLe(0)
            out.writeShortLe(0)
            out.writeShortLe(0)
            out.writeIntLe(0)
            out.writeIntLe(offsets[i])
            out.write(name)
        }
        val centralSize = out.size.toInt() - centralOffset
        out.writeIntLe(0x06054b50)
        out.writeIntLe(0)
        out.writeShortLe(entries.size.toShort())
        out.writeShortLe(entries.size.toShort())
        out.writeIntLe(centralSize)
        out.writeIntLe(centralOffset)
        out.writeShortLe(0)
        return out.readByteArray()
    }

    private fun crc(data: ByteArray) = CRC32().apply { update(data) }.value.toInt()

    @Test
    fun readsSeveralStoredEntriesWithDataDescriptors() =
        runTest {
            for (zip64 in listOf(false, true)) assertFiles(files, unzipBytes(storedWithDescriptors(files, zip64)))
        }

    @Test
    fun storedDataMayContainSomethingThatLooksLikeADescriptor() =
        runTest {
            // The descriptor signature, a wrong checksum, and a local header signature.
            val tricky =
                Buffer()
                    .apply {
                        write("before".encodeToByteArray())
                        writeIntLe(0x08074b50)
                        writeIntLe(1234)
                        writeIntLe(6)
                        writeIntLe(6)
                        writeIntLe(0x04034b50)
                        write("after".encodeToByteArray())
                    }.readByteArray()
            val entries = mapOf("tricky.bin" to tricky, "next.txt" to "next".encodeToByteArray())
            assertFiles(entries, unzipBytes(storedWithDescriptors(entries)))
        }

    @Test
    fun rejectsAnIndexThatDoesNotMatchTheFiles() =
        runTest {
            val zip = storedWithDescriptors(mapOf("a.txt" to "hello".encodeToByteArray()))
            // Rename the file in the central directory only.
            val central = zip.size - 22 - (46 + "a.txt".length)
            zip[central + 46] = 'b'.code.toByte()
            assertFailsWith<InvalidZipException> { unzipBytes(zip) }
        }

    @Test
    fun rejectsDataAfterTheEnd() =
        runTest {
            val zip = jdkZip(files) + byteArrayOf(1, 2, 3)
            assertFailsWith<InvalidZipException> { unzipBytes(zip) }
        }
}
