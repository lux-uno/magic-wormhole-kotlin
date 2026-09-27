package uno.lux.wormhole.zip

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe
import kotlinx.io.writeLongLe
import kotlinx.io.writeShortLe
import uno.lux.wormhole.WormholeException

/** A file to put into a zip. [crc] must be the CRC-32 of the [size] bytes that [open] returns. */
internal class ZipFileEntry(
    val path: String,
    val size: Long,
    val crc: Int,
    val open: () -> RawSource,
)

/**
 * Writes an uncompressed (STORED) zip as a stream. Sizes and CRCs are known up front, so
 * [size] is exact before any byte is written and no temporary file is needed. Uses Zip64
 * where a size, offset or the entry count is too large for the classic format.
 */
internal class ZipWriter(
    private val entries: List<ZipFileEntry>,
    /** Use Zip64 records everywhere. Only for tests. */
    private val forceZip64: Boolean = false,
) {
    private val names = entries.map { it.path.encodeToByteArray() }
    private val offsets = LongArray(entries.size)
    private val centralDirectory = Buffer()
    private val end = Buffer()

    /** Total size of the zip in bytes. */
    val size: Long

    init {
        var offset = 0L
        entries.forEachIndexed { i, entry ->
            offsets[i] = offset
            offset += localHeaderSize(i) + entry.size
        }
        entries.indices.forEach { writeCentralHeader(it, centralDirectory) }
        writeEnd(offset, centralDirectory.size, end)
        size = offset + centralDirectory.size + end.size
    }

    /** Returns the zip bytes. Each file is opened when the stream reaches it. */
    fun source(): RawSource {
        val parts =
            sequence {
                entries.forEachIndexed { i, entry ->
                    yield { Buffer().also { writeLocalHeader(i, it) } }
                    yield { CheckedSource(entry) }
                }
                yield { centralDirectory.copy() }
                yield { end.copy() }
            }
        return ConcatSource(parts.iterator())
    }

    private fun isLarge(value: Long) = forceZip64 || value >= MAX_32

    private fun localZip64(i: Int) = isLarge(entries[i].size)

    private fun localHeaderSize(i: Int): Long = 30L + names[i].size + if (localZip64(i)) 20 else 0

    private fun writeLocalHeader(
        i: Int,
        out: Buffer,
    ) {
        val entry = entries[i]
        val zip64 = localZip64(i)
        out.writeIntLe(LOCAL_HEADER)
        out.writeShortLe(if (zip64) VERSION_ZIP64 else VERSION_DEFAULT)
        out.writeShortLe(FLAG_UTF8)
        out.writeShortLe(METHOD_STORED)
        out.writeShortLe(DOS_TIME)
        out.writeShortLe(DOS_DATE)
        out.writeIntLe(entry.crc)
        out.writeIntLe(if (zip64) -1 else entry.size.toInt())
        out.writeIntLe(if (zip64) -1 else entry.size.toInt())
        out.writeShortLe(names[i].size.toShort())
        out.writeShortLe((if (zip64) 20 else 0).toShort())
        out.write(names[i])
        if (zip64) {
            out.writeShortLe(ZIP64_EXTRA)
            out.writeShortLe(16)
            out.writeLongLe(entry.size)
            out.writeLongLe(entry.size)
        }
    }

    private fun writeCentralHeader(
        i: Int,
        out: Buffer,
    ) {
        val entry = entries[i]
        val largeSize = isLarge(entry.size)
        val largeOffset = isLarge(offsets[i])
        val extraSize = (if (largeSize) 16 else 0) + (if (largeOffset) 8 else 0)
        val version = if (extraSize > 0) VERSION_ZIP64 else VERSION_DEFAULT
        out.writeIntLe(CENTRAL_HEADER)
        out.writeShortLe(version)
        out.writeShortLe(version)
        out.writeShortLe(FLAG_UTF8)
        out.writeShortLe(METHOD_STORED)
        out.writeShortLe(DOS_TIME)
        out.writeShortLe(DOS_DATE)
        out.writeIntLe(entry.crc)
        out.writeIntLe(if (largeSize) -1 else entry.size.toInt())
        out.writeIntLe(if (largeSize) -1 else entry.size.toInt())
        out.writeShortLe(names[i].size.toShort())
        out.writeShortLe((if (extraSize > 0) extraSize + 4 else 0).toShort())
        out.writeShortLe(0) // comment length
        out.writeShortLe(0) // disk number
        out.writeShortLe(0) // internal attributes
        out.writeIntLe(0) // external attributes
        out.writeIntLe(if (largeOffset) -1 else offsets[i].toInt())
        out.write(names[i])
        if (extraSize > 0) {
            out.writeShortLe(ZIP64_EXTRA)
            out.writeShortLe(extraSize.toShort())
            if (largeSize) {
                out.writeLongLe(entry.size)
                out.writeLongLe(entry.size)
            }
            if (largeOffset) out.writeLongLe(offsets[i])
        }
    }

    private fun writeEnd(
        centralOffset: Long,
        centralSize: Long,
        out: Buffer,
    ) {
        val count = entries.size.toLong()
        val zip64 = forceZip64 || count >= 0xFFFF || isLarge(centralOffset) || isLarge(centralSize)
        if (zip64) {
            out.writeIntLe(END64)
            out.writeLongLe(44) // size of the rest of this record
            out.writeShortLe(VERSION_ZIP64)
            out.writeShortLe(VERSION_ZIP64)
            out.writeIntLe(0)
            out.writeIntLe(0)
            out.writeLongLe(count)
            out.writeLongLe(count)
            out.writeLongLe(centralSize)
            out.writeLongLe(centralOffset)
            out.writeIntLe(END64_LOCATOR)
            out.writeIntLe(0)
            out.writeLongLe(centralOffset + centralSize)
            out.writeIntLe(1)
        }
        out.writeIntLe(END)
        out.writeShortLe(0)
        out.writeShortLe(0)
        out.writeShortLe(if (zip64) -1 else count.toShort())
        out.writeShortLe(if (zip64) -1 else count.toShort())
        out.writeIntLe(if (zip64) -1 else centralSize.toInt())
        out.writeIntLe(if (zip64) -1 else centralOffset.toInt())
        out.writeShortLe(0)
    }

    /** Reads one file and fails if its size or CRC changed since it was measured. */
    private class CheckedSource(
        private val entry: ZipFileEntry,
    ) : RawSource {
        private val source = entry.open()
        private val crc = Crc32()
        private val chunk = Buffer()
        private var read = 0L

        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            val n = source.readAtMostTo(chunk, minOf(byteCount, entry.size - read + 1))
            if (n == -1L) {
                if (read != entry.size || crc.value != entry.crc) throw changed()
                return -1
            }
            read += n
            if (read > entry.size) throw changed()
            val bytes = chunk.readByteArray()
            crc.update(bytes)
            sink.write(bytes)
            return n
        }

        private fun changed() = WormholeException("The file ${entry.path} changed while it was being sent")

        override fun close() = source.close()
    }

    /** Reads the parts one after another, opening each only when it is needed. */
    private class ConcatSource(
        private val parts: Iterator<() -> RawSource>,
    ) : RawSource {
        private var current: RawSource? = null

        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            while (true) {
                val source = current ?: (if (parts.hasNext()) parts.next()() else return -1).also { current = it }
                val n = source.readAtMostTo(sink, byteCount)
                if (n != -1L) return n
                source.close()
                current = null
            }
        }

        override fun close() {
            current?.close()
            current = null
        }
    }

    internal companion object {
        const val LOCAL_HEADER = 0x04034b50
        const val CENTRAL_HEADER = 0x02014b50
        const val DATA_DESCRIPTOR = 0x08074b50
        const val END = 0x06054b50
        const val END64 = 0x06064b50
        const val END64_LOCATOR = 0x07064b50
        const val ZIP64_EXTRA: Short = 0x0001
        const val FLAG_UTF8: Short = 0x0800
        const val METHOD_STORED: Short = 0
        const val METHOD_DEFLATED: Short = 8
        const val MAX_32 = 0xFFFFFFFFL
        private const val VERSION_DEFAULT: Short = 20
        private const val VERSION_ZIP64: Short = 45
        private const val DOS_TIME: Short = 0
        private const val DOS_DATE: Short = 0x21 // 1980-01-01
    }
}
