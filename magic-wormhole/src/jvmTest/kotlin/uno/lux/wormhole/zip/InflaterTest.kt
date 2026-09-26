package uno.lux.wormhole.zip

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** Checks the inflater against data compressed by the JDK. */
class InflaterTest {
    private fun deflate(
        data: ByteArray,
        level: Int,
        strategy: Int = Deflater.DEFAULT_STRATEGY,
    ): ByteArray {
        val deflater = Deflater(level, true).apply { setStrategy(strategy) }
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(compressed: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Inflater(Buffer().apply { write(compressed) }).inflate { bytes, start, end ->
            out.write(
                bytes,
                start,
                end - start,
            )
        }
        return out.toByteArray()
    }

    private fun samples(): List<ByteArray> {
        val random = Random(1)
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(20_000).encodeToByteArray()
        val noise = ByteArray(300_000).also { random.nextBytes(it) }
        val mixed = ByteArray(500_000) { if (it % 1000 < 500) (it % 7).toByte() else random.nextInt().toByte() }
        return listOf(ByteArray(0), byteArrayOf(42), text, noise, mixed)
    }

    @Test
    fun inflatesWhatTheJdkDeflates() {
        for (data in samples()) {
            for (level in listOf(0, 1, 6, 9)) {
                assertContentEquals(data, inflate(deflate(data, level)), "level $level, ${data.size} bytes")
            }
            assertContentEquals(data, inflate(deflate(data, 6, Deflater.HUFFMAN_ONLY)))
            assertContentEquals(data, inflate(deflate(data, 6, Deflater.FILTERED)))
        }
    }

    @Test
    fun stopsAtTheEndOfTheStream() {
        val data = "hello hello hello hello".encodeToByteArray()
        val trailer = byteArrayOf(0x50, 0x4b, 7, 8, 1, 2, 3)
        val source = Buffer().apply { write(deflate(data, 9) + trailer) }
        val out = ByteArrayOutputStream()
        Inflater(source).inflate { bytes, start, end -> out.write(bytes, start, end - start) }
        assertContentEquals(data, out.toByteArray())
        assertContentEquals(trailer, source.readByteArray())
    }

    @Test
    fun truncatedOrBrokenDataFails() {
        val compressed = deflate(ByteArray(10_000) { (it % 251).toByte() }, 9)
        assertFailsWith<uno.lux.wormhole.InvalidZipException> { inflate(compressed.copyOf(compressed.size / 2)) }
        assertFailsWith<uno.lux.wormhole.InvalidZipException> { inflate(byteArrayOf(0x07)) } // block type 3
    }
}
