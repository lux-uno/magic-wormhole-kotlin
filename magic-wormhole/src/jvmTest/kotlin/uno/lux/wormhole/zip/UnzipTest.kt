package uno.lux.wormhole.zip

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe
import kotlinx.io.writeShortLe
import uno.lux.wormhole.InvalidZipException
import uno.lux.wormhole.UnzipTarget
import uno.lux.wormhole.unzip
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Checks [unzip]. [StreamingUnzipTest] runs the same cases through the streaming reader. */
open class UnzipTest {
    /** Collects what [unzip] writes. */
    class MemoryTarget : UnzipTarget {
        val files = mutableMapOf<String, Buffer>()
        val directories = mutableListOf<String>()

        override fun createDirectory(path: String) {
            directories += path
        }

        override fun createFile(path: String): RawSink = Buffer().also { files[path] = it }

        fun contents() = files.mapValues { it.value.readByteArray() }
    }

    protected val files =
        mapOf(
            "a.txt" to "hello".encodeToByteArray(),
            "sub/dir/b.bin" to ByteArray(200_000) { (it % 97).toByte() },
            "empty" to ByteArray(0),
            "ünïcode/ファイル.txt" to "x".encodeToByteArray(),
        )

    protected fun assertFiles(
        expected: Map<String, ByteArray>,
        target: MemoryTarget,
    ) {
        val actual = target.contents()
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, data) -> assertContentEquals(data, actual[path], path) }
    }

    /** A zip made by the JDK: deflated entries with data descriptors, like Go's archive/zip. */
    protected fun jdkZip(
        entries: Map<String, ByteArray>,
        directories: List<String> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            directories.forEach {
                zip.putNextEntry(ZipEntry(it))
                zip.closeEntry()
            }
            entries.forEach { (path, data) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Deflated entries with sizes in the local header and no data descriptor, like Python's zipfile. */
    private fun zipWithoutDescriptors(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (path, data) ->
                val compressed =
                    ByteArrayOutputStream().also {
                        DeflaterOutputStream(it, Deflater(6, true)).use { d ->
                            d.write(data)
                        }
                    }
                zip.putNextEntry(
                    ZipEntry(path).apply {
                        size = data.size.toLong()
                        compressedSize = compressed.size().toLong()
                        crc = CRC32().apply { update(data) }.value
                    },
                )
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    protected open suspend fun unzipBytes(
        zip: ByteArray,
        maxBytes: Long = Long.MAX_VALUE,
        maxFiles: Int = Int.MAX_VALUE,
    ): MemoryTarget =
        MemoryTarget().also { target ->
            unzip(zip.size.toLong(), { offset ->
                Buffer().apply {
                    write(zip, offset.toInt(), zip.size)
                }
            }, target, maxBytes, maxFiles)
        }

    @Test
    fun readsOwnZips() =
        runTest {
            for (zip64 in listOf(false, true)) {
                val entries =
                    files.map { (p, d) ->
                        ZipFileEntry(
                            p,
                            d.size.toLong(),
                            Crc32().apply { update(d) }.value,
                        ) { Buffer().apply { write(d) } }
                    }
                val bytes = Buffer().apply { transferFrom(ZipWriter(entries, zip64).source()) }.readByteArray()
                assertFiles(files, unzipBytes(bytes))
            }
        }

    @Test
    fun readsDeflatedZipsWithDataDescriptors() =
        runTest {
            val target = unzipBytes(jdkZip(files, directories = listOf("folder/", "folder/inner/")))
            assertFiles(files, target)
            assertEquals(listOf("folder", "folder/inner"), target.directories)
        }

    @Test
    fun readsDeflatedZipsWithoutDataDescriptors() =
        runTest { assertFiles(files, unzipBytes(zipWithoutDescriptors(files))) }

    /** Stored entries with sizes only in data descriptors, like zipstream-ng (the `wormhole` CLI since 0.17). */
    @Test
    fun readsStoredEntriesWithDataDescriptors() =
        runTest {
            val data = "hello world".encodeToByteArray()
            val crc = CRC32().apply { update(data) }.value.toInt()
            val name = "a.txt".encodeToByteArray()
            val out = Buffer()
            out.writeIntLe(0x04034b50)
            out.writeShortLe(20)
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
            out.writeIntLe(crc)
            out.writeIntLe(data.size)
            out.writeIntLe(data.size)
            val centralOffset = out.size.toInt()
            out.writeIntLe(0x02014b50)
            out.writeShortLe(20)
            out.writeShortLe(20)
            out.writeShortLe(8)
            out.writeShortLe(0)
            out.writeIntLe(0)
            out.writeIntLe(crc)
            out.writeIntLe(data.size)
            out.writeIntLe(data.size)
            out.writeShortLe(name.size.toShort())
            out.writeShortLe(0)
            out.writeShortLe(0)
            out.writeShortLe(0)
            out.writeShortLe(0)
            out.writeIntLe(0)
            out.writeIntLe(0)
            out.write(name)
            val centralSize = out.size.toInt() - centralOffset
            out.writeIntLe(0x06054b50)
            out.writeIntLe(0)
            out.writeShortLe(1)
            out.writeShortLe(1)
            out.writeIntLe(centralSize)
            out.writeIntLe(centralOffset)
            out.writeShortLe(0)
            assertFiles(mapOf("a.txt" to data), unzipBytes(out.readByteArray()))
        }

    @Test
    fun rejectsOverlappingEntries() =
        runTest {
            // Two central directory entries that point at the same data: a known zip bomb trick.
            val zip = jdkZip(mapOf("a" to ByteArray(10), "b" to ByteArray(10)))
            val end = zip.size - 22
            val centralOffset =
                java.nio.ByteBuffer
                    .wrap(zip, end + 16, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .int
            val second = centralOffset + 46 + 1
            for (i in 0 until 4) zip[second + 42 + i] = 0
            assertFailsWith<InvalidZipException> { unzipBytes(zip) }
        }

    @Test
    fun emptyZip() = runTest { assertFiles(emptyMap(), unzipBytes(jdkZip(emptyMap()))) }

    @Test
    fun rejectsPathsOutsideTheTarget() =
        runTest {
            for (bad in listOf(
                "../evil",
                "a/../../evil",
                "/etc/passwd",
                "C:/Windows/x",
                "..\\evil",
                "\\abs",
                "a\u0000b",
            )) {
                assertFailsWith<InvalidZipException>(bad) { unzipBytes(jdkZip(mapOf(bad to byteArrayOf(1)))) }
            }
        }

    @Test
    fun normalizesHarmlessPaths() =
        runTest {
            val target = unzipBytes(jdkZip(mapOf("./a/./b.txt" to byteArrayOf(1), "win\\style.txt" to byteArrayOf(2))))
            assertEquals(setOf("a/b.txt", "win/style.txt"), target.files.keys)
        }

    @Test
    fun enforcesLimits() =
        runTest {
            val zip = jdkZip(files)
            val total = files.values.sumOf { it.size.toLong() }
            unzipBytes(zip, maxBytes = total, maxFiles = files.size)
            assertFailsWith<InvalidZipException> { unzipBytes(zip, maxBytes = total - 1) }
            assertFailsWith<InvalidZipException> { unzipBytes(zip, maxFiles = files.size - 1) }
            // A small zip that inflates to a lot of zeros.
            val bomb = jdkZip(mapOf("zeros" to ByteArray(50_000_000)))
            assertFailsWith<InvalidZipException> { unzipBytes(bomb, maxBytes = 1_000_000) }
        }

    @Test
    fun detectsCorruptData() =
        runTest {
            val zip = zipWithoutDescriptors(mapOf("a.txt" to "hello hello hello".encodeToByteArray()))
            val dataOffset = 30 + "a.txt".length
            zip[dataOffset + 1] = (zip[dataOffset + 1] + 1).toByte()
            assertFailsWith<InvalidZipException> { unzipBytes(zip) }
            assertFailsWith<InvalidZipException> { unzipBytes(zip.copyOf(20)) }
            assertFailsWith<InvalidZipException> { unzipBytes("not a zip".encodeToByteArray()) }
        }
}
