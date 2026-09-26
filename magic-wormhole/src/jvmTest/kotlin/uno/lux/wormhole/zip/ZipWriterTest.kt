package uno.lux.wormhole.zip

import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import uno.lux.wormhole.WormholeException
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Checks the zip writer against the JDK zip reader. */
class ZipWriterTest {
    private fun entry(
        path: String,
        data: ByteArray,
    ) = ZipFileEntry(path, data.size.toLong(), Crc32().apply { update(data) }.value) { Buffer().apply { write(data) } }

    private val files =
        mapOf(
            "a.txt" to "hello".encodeToByteArray(),
            "sub/dir/b.bin" to ByteArray(100_000) { (it * 13).toByte() },
            "empty" to ByteArray(0),
            "ünïcode/ファイル.txt" to "x".encodeToByteArray(),
        )

    private fun writeAndCheck(forceZip64: Boolean) {
        val writer = ZipWriter(files.map { (p, d) -> entry(p, d) }, forceZip64)
        val bytes = writer.source().buffered().readByteArray()
        assertEquals(writer.size, bytes.size.toLong())

        val file = File.createTempFile("zipwriter", ".zip").apply { deleteOnExit() }
        file.writeBytes(bytes)
        ZipFile(file).use { zip ->
            val read = zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() }
            assertEquals(files.keys, read.keys)
            files.forEach { (path, data) -> assertContentEquals(data, read[path], path) }
        }
    }

    @Test
    fun jdkReadsTheZip() = writeAndCheck(forceZip64 = false)

    @Test
    fun jdkReadsTheZip64Variant() = writeAndCheck(forceZip64 = true)

    @Test
    fun emptyZip() {
        val writer = ZipWriter(emptyList())
        assertEquals(22, writer.size)
        assertEquals(
            22,
            writer
                .source()
                .buffered()
                .readByteArray()
                .size,
        )
    }

    @Test
    fun fileThatChangedFails() {
        val wrongCrc = ZipFileEntry("a", 3, 0) { Buffer().apply { write(byteArrayOf(1, 2, 3)) } }
        assertFailsWith<WormholeException> { ZipWriter(listOf(wrongCrc)).source().buffered().readByteArray() }
        val shorter = ZipFileEntry("a", 4, 0) { Buffer().apply { write(byteArrayOf(1, 2, 3)) } }
        assertFailsWith<WormholeException> { ZipWriter(listOf(shorter)).source().buffered().readByteArray() }
    }
}
