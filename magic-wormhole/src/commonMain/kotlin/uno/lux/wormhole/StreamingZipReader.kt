package uno.lux.wormhole

import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.RawSink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.discardingSink
import kotlinx.io.readByteArray
import kotlinx.io.readIntLe
import kotlinx.io.readLongLe
import kotlinx.io.readShortLe
import uno.lux.wormhole.zip.Crc32
import uno.lux.wormhole.zip.Inflater
import uno.lux.wormhole.zip.ZipWriter

/**
 * Unpacks a zip from start to end as its bytes arrive, without seeking. Each file goes to [target]
 * as soon as its data is read. At the end, the central directory must list exactly the files that
 * were read: same offsets, names, sizes and checksums. Paths and limits are checked like [unzip].
 */
internal class StreamingZipReader(
    private val input: Source,
    private val target: UnzipTarget,
    private val maxBytes: Long,
    private val maxFiles: Int,
    private val checkCancelled: () -> Unit = {},
) {
    private class LocalEntry(
        val offset: Long,
        val name: String,
        val crc: Int,
        val compressedSize: Long,
        val size: Long,
    )

    private val entries = mutableListOf<LocalEntry>()
    private var position = 0L
    private var files = 0
    private var totalBytes = 0L

    fun readAll() {
        try {
            var signature = readInt()
            while (signature == ZipWriter.LOCAL_HEADER) {
                entries += readEntry(offset = position - 4)
                signature = readInt()
            }
            readCentralDirectory(signature, start = position - 4)
            if (!input.exhausted()) throw InvalidZipException("Unexpected data after the end of the zip")
        } catch (e: EOFException) {
            throw InvalidZipException("The zip file ends too early")
        }
    }

    private fun readInt(): Int = input.readIntLe().also { position += 4 }

    private fun readShort(): Int = (input.readShortLe().toInt() and 0xFFFF).also { position += 2 }

    private fun readBytes(count: Int): ByteArray = input.readByteArray(count).also { position += count }

    private fun readEntry(offset: Long): LocalEntry {
        readShort() // version
        val flags = readShort()
        val method = readShort().toShort()
        readInt() // time and date
        var crc = readInt()
        var compressedSize = readInt().toLong() and ZipWriter.MAX_32
        var size = readInt().toLong() and ZipWriter.MAX_32
        val nameLength = readShort()
        val extraLength = readShort()
        val name = readBytes(nameLength).decodeToString()
        val extra = Buffer().apply { write(readBytes(extraLength)) }
        var zip64 = false
        while (extra.size >= 4) {
            val id = extra.readShortLe()
            val length = (extra.readShortLe().toInt() and 0xFFFF).toLong()
            if (extra.size < length) break
            val field = Buffer().apply { write(extra, length) }
            if (id != ZipWriter.ZIP64_EXTRA) continue
            zip64 = true
            if (size == ZipWriter.MAX_32 && field.size >= 8) size = field.readLongLe()
            if (compressedSize == ZipWriter.MAX_32 && field.size >= 8) compressedSize = field.readLongLe()
        }
        if (flags and FLAG_ENCRYPTED != 0) throw InvalidZipException("Encrypted zip files are not supported")
        val hasDescriptor = flags and FLAG_DESCRIPTOR != 0
        val isDirectory = name.endsWith('/') || name.endsWith('\\')
        val path = safePath(name)
        if (!isDirectory && ++files > maxFiles) throw InvalidZipException("The zip holds more files than announced")

        val sink: RawSink? =
            when {
                path == null -> null
                isDirectory -> null.also { target.createDirectory(path) }
                else -> target.createFile(path)
            }
        val checksum = Crc32()
        var written = 0L
        val buffer = Buffer()
        val write = { bytes: ByteArray, start: Int, end: Int ->
            checkCancelled()
            written += end - start
            totalBytes += end - start
            if ((!hasDescriptor && written > size) || totalBytes > maxBytes) {
                throw InvalidZipException("The zip holds more data than announced")
            }
            checksum.update(bytes, start, end)
            if (sink != null) {
                buffer.write(bytes, start, end)
                sink.write(buffer, buffer.size)
            }
        }
        try {
            when {
                method == ZipWriter.METHOD_STORED && hasDescriptor -> {
                    position += readStoredUntilDescriptor(write, checksum) { written }
                    crc = checksum.value
                    compressedSize = written
                    size = written
                }

                method == ZipWriter.METHOD_STORED -> {
                    copy(LimitedSource(input, compressedSize).buffered(), write)
                    position += compressedSize
                }

                method == ZipWriter.METHOD_DEFLATED && hasDescriptor -> {
                    val inflater = Inflater(input)
                    inflater.inflate(write)
                    position += inflater.consumed
                    val descriptor =
                        readDescriptor(
                            large =
                                zip64 || inflater.consumed >= ZipWriter.MAX_32 || written >= ZipWriter.MAX_32,
                        )
                    if (descriptor.compressedSize !=
                        inflater.consumed
                    ) {
                        throw InvalidZipException("Damaged file in zip: $name")
                    }
                    crc = descriptor.crc
                    compressedSize = descriptor.compressedSize
                    size = descriptor.size
                }

                method == ZipWriter.METHOD_DEFLATED -> {
                    val data = LimitedSource(input, compressedSize).buffered()
                    Inflater(data).inflate(write)
                    data.transferTo(discardingSink())
                    position += compressedSize
                }

                else -> {
                    throw InvalidZipException("Unsupported compression method $method")
                }
            }
            sink?.flush()
        } finally {
            sink?.close()
        }
        if (written != size || checksum.value != crc) throw InvalidZipException("Damaged file in zip: $name")
        return LocalEntry(offset, name, crc, compressedSize, size)
    }

    private class Descriptor(
        val crc: Int,
        val compressedSize: Long,
        val size: Long,
    )

    /** Reads a data descriptor after deflated data. Its signature is optional. */
    private fun readDescriptor(large: Boolean): Descriptor {
        var crc = readInt()
        if (crc == ZipWriter.DATA_DESCRIPTOR) crc = readInt()
        return if (large) {
            Descriptor(crc, input.readLongLe(), input.readLongLe()).also { position += 16 }
        } else {
            Descriptor(crc, readInt().toLong() and ZipWriter.MAX_32, readInt().toLong() and ZipWriter.MAX_32)
        }
    }

    /**
     * Stored data whose size is only in the data descriptor after it. The data ends at the first
     * descriptor signature that is followed by the checksum and size of the bytes before it, and
     * then by the next header. Writes the data and returns the bytes read, descriptor included.
     */
    private fun readStoredUntilDescriptor(
        write: (ByteArray, Int, Int) -> Unit,
        checksum: Crc32,
        written: () -> Long,
    ): Long {
        val ahead = ByteArray(SCAN_CHUNK + DESCRIPTOR_LOOKAHEAD)
        var consumed = 0L
        while (true) {
            val n = peek(ahead)
            val ended = n < ahead.size
            var emitted = 0
            var i = 0
            var needMore = false
            while (i + 4 <= n) {
                if (ahead.intAt(i) == ZipWriter.DATA_DESCRIPTOR) {
                    write(ahead, emitted, i)
                    emitted = i
                    val length = descriptorLength(ahead, i, n, ended, checksum.value, written())
                    if (length == NEED_MORE) {
                        needMore = true
                        break
                    }
                    if (length > 0) {
                        input.skip((i + length).toLong())
                        return consumed + i + length
                    }
                }
                i++
            }
            if (ended && !needMore) throw InvalidZipException("The zip file ends too early")
            val keep = if (needMore) i else maxOf(emitted, n - 3)
            write(ahead, emitted, keep)
            input.skip(keep.toLong())
            consumed += keep
        }
    }

    /** Fills [array] from the input without consuming it. Returns the number of bytes. */
    private fun peek(array: ByteArray): Int {
        val source = input.peek()
        var n = 0
        while (n < array.size) {
            val read = source.readAtMostTo(array, n, array.size)
            if (read == -1) break
            n += read
        }
        return n
    }

    /**
     * The length of the data descriptor at [i] (16 or 24 bytes) if it matches [crc] and [size] and
     * the next header follows it; 0 if it does not match; [NEED_MORE] if [n] bytes are too few.
     */
    private fun descriptorLength(
        a: ByteArray,
        i: Int,
        n: Int,
        ended: Boolean,
        crc: Int,
        size: Long,
    ): Int {
        if (i + 20 > n) return if (ended) 0 else NEED_MORE
        if (a.intAt(i + 4) == crc && a.uintAt(i + 8) == size && a.uintAt(i + 12) == size && isHeader(a.intAt(i + 16))) {
            return 16
        }
        if (i + 28 > n) return if (ended) 0 else NEED_MORE
        if (a.intAt(i + 4) == crc && a.longAt(i + 8) == size && a.longAt(i + 16) == size && isHeader(a.intAt(i + 24))) {
            return 24
        }
        return 0
    }

    private fun isHeader(signature: Int) = signature == ZipWriter.LOCAL_HEADER || signature == ZipWriter.CENTRAL_HEADER

    private fun readCentralDirectory(
        firstSignature: Int,
        start: Long,
    ) {
        var signature = firstSignature
        val central = mutableListOf<CentralEntry>()
        while (signature == ZipWriter.CENTRAL_HEADER) {
            central += readCentralEntry(input)
            signature = input.readIntLe()
        }
        var count = -1L
        var directoryOffset = -1L
        if (signature == ZipWriter.END64) {
            val record = Buffer().apply { write(input.readByteArray(input.readLongLe().toInt())) }
            record.skip(20)
            count = record.readLongLe()
            record.skip(8)
            directoryOffset = record.readLongLe()
            signature = input.readIntLe()
        }
        if (signature == ZipWriter.END64_LOCATOR) {
            input.skip(16)
            signature = input.readIntLe()
        }
        if (signature != ZipWriter.END) {
            throw InvalidZipException(if (entries.isEmpty()) "Not a zip file" else "Damaged zip: bad central directory")
        }
        input.skip(6)
        val count16 = input.readShortLe().toLong() and 0xFFFF
        input.skip(4)
        val offset32 = input.readIntLe().toLong() and ZipWriter.MAX_32
        input.skip((input.readShortLe().toLong() and 0xFFFF))
        if (count == -1L) count = count16
        if (directoryOffset == -1L) directoryOffset = offset32

        val local = entries.associateBy { it.offset }
        val matches =
            count == central.size.toLong() && central.size == entries.size && directoryOffset == start &&
                central.all { c ->
                    val l = local[c.offset]
                    l != null && l.name == c.name && l.crc == c.crc && l.size == c.size &&
                        l.compressedSize == c.compressedSize
                }
        if (!matches) throw InvalidZipException("The zip's central directory does not match its files")
    }

    private fun ByteArray.intAt(i: Int) =
        (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8) or
            ((this[i + 2].toInt() and 0xFF) shl 16) or ((this[i + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.uintAt(i: Int) = intAt(i).toLong() and ZipWriter.MAX_32

    private fun ByteArray.longAt(i: Int) = uintAt(i) or (uintAt(i + 4) shl 32)

    private companion object {
        const val FLAG_ENCRYPTED = 1
        const val FLAG_DESCRIPTOR = 8
        const val SCAN_CHUNK = 64 * 1024

        // Descriptor signature, CRC, two 8-byte sizes, and the next header's signature.
        const val DESCRIPTOR_LOOKAHEAD = 28
        const val NEED_MORE = -1
    }
}
