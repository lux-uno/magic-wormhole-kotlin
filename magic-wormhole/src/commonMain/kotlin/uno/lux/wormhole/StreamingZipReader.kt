package uno.lux.wormhole

import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.discardingSink
import kotlinx.io.readByteArray
import kotlinx.io.readIntLe
import kotlinx.io.readLongLe
import kotlinx.io.readShortLe
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
    /** The checksum and sizes of an entry's data, as in a zip data descriptor. */
    private class DataDescriptor(
        val crc: Int,
        val compressedSize: Long,
        val size: Long,
    )

    private class LocalHeader(
        val flags: Int,
        val method: Short,
        val name: String,
        val zip64: Boolean,
        val descriptor: DataDescriptor,
    ) {
        /** The sizes and checksum are in a data descriptor after the data, not in this header. */
        val hasDescriptor get() = flags and FLAG_DESCRIPTOR != 0
    }

    private class LocalEntry(
        val offset: Long,
        val name: String,
        val descriptor: DataDescriptor,
    ) {
        fun matches(central: CentralEntry) =
            name == central.name && descriptor.crc == central.crc && descriptor.size == central.size &&
                descriptor.compressedSize == central.compressedSize
    }

    /** Where the end record says the central directory is, and how many entries it holds. */
    private class Directory(
        val count: Long,
        val offset: Long,
    )

    /** The part of a scan window that holds entry data, and the length of the descriptor after it. */
    private class WindowScan(
        val dataEnd: Int,
        val descriptorLength: Int,
    )

    private val entries = mutableListOf<LocalEntry>()
    private val unpackedBytes = UnpackedBytes(maxBytes)
    private var position = 0L
    private var files = 0

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

    private fun readUnsignedInt(): Long = readInt().toLong() and ZipWriter.MAX_32

    private fun readShort(): Int = (input.readShortLe().toInt() and 0xFFFF).also { position += 2 }

    private fun readBytes(count: Int): ByteArray = input.readByteArray(count).also { position += count }

    private fun readEntry(offset: Long): LocalEntry {
        val header = readLocalHeader()
        if (header.flags and FLAG_ENCRYPTED != 0) throw InvalidZipException("Encrypted zip files are not supported")
        val isDirectory = isDirectoryName(header.name)
        val path = safePath(header.name)
        if (!isDirectory && ++files > maxFiles) throw InvalidZipException("The zip holds more files than announced")

        val maxSize = if (header.hasDescriptor) Long.MAX_VALUE else header.descriptor.size
        val output = EntryWriter(target.openEntry(path, isDirectory), maxSize, unpackedBytes, checkCancelled)
        val descriptor = output.use { readData(header, it) }
        if (output.written != descriptor.size || output.crc != descriptor.crc) {
            throw InvalidZipException("Damaged file in zip: ${header.name}")
        }
        return LocalEntry(offset, header.name, descriptor)
    }

    /** Reads a local header, after its signature. Resolves Zip64 sizes. */
    private fun readLocalHeader(): LocalHeader {
        readShort() // version
        val flags = readShort()
        val method = readShort().toShort()
        readInt() // time and date
        val crc = readInt()
        var compressedSize = readUnsignedInt()
        var size = readUnsignedInt()
        val nameLength = readShort()
        val extraLength = readShort()
        val name = readBytes(nameLength).decodeToString()
        var zip64 = false
        forEachZip64Field(readBytes(extraLength)) { field ->
            zip64 = true
            if (size == ZipWriter.MAX_32 && field.size >= 8) size = field.readLongLe()
            if (compressedSize == ZipWriter.MAX_32 && field.size >= 8) compressedSize = field.readLongLe()
        }
        return LocalHeader(flags, method, name, zip64, DataDescriptor(crc, compressedSize, size))
    }

    /** Unpacks the entry's data into [output], and returns its checksum and sizes. */
    private fun readData(
        header: LocalHeader,
        output: EntryWriter,
    ): DataDescriptor =
        when {
            !header.hasDescriptor -> readDataOfKnownSize(header, output)
            header.method == ZipWriter.METHOD_STORED -> readStoredWithDescriptor(output)
            header.method == ZipWriter.METHOD_DEFLATED -> readDeflatedWithDescriptor(header, output)
            else -> throw InvalidZipException("Unsupported compression method ${header.method}")
        }

    private fun readDataOfKnownSize(
        header: LocalHeader,
        output: EntryWriter,
    ): DataDescriptor {
        val compressedSize = header.descriptor.compressedSize
        val data = LimitedSource(input, compressedSize).buffered()
        decompress(header.method, data, output)
        data.transferTo(discardingSink())
        position += compressedSize
        return header.descriptor
    }

    private fun readStoredWithDescriptor(output: EntryWriter): DataDescriptor {
        position += readStoredUntilDescriptor(output)
        return DataDescriptor(output.crc, compressedSize = output.written, size = output.written)
    }

    /** Deflated data ends by itself, so the descriptor after it only has to confirm its length. */
    private fun readDeflatedWithDescriptor(
        header: LocalHeader,
        output: EntryWriter,
    ): DataDescriptor {
        val inflater = Inflater(input)
        inflater.inflate(output::write)
        position += inflater.consumed
        val large = header.zip64 || inflater.consumed >= ZipWriter.MAX_32 || output.written >= ZipWriter.MAX_32
        val descriptor = readDescriptor(large)
        if (descriptor.compressedSize != inflater.consumed) {
            throw InvalidZipException("Damaged file in zip: ${header.name}")
        }
        return descriptor
    }

    /** Reads a data descriptor after deflated data. Its signature is optional. */
    private fun readDescriptor(large: Boolean): DataDescriptor {
        var crc = readInt()
        if (crc == ZipWriter.DATA_DESCRIPTOR) crc = readInt()
        return if (large) {
            DataDescriptor(crc, input.readLongLe(), input.readLongLe()).also { position += 16 }
        } else {
            DataDescriptor(crc, readUnsignedInt(), readUnsignedInt())
        }
    }

    /**
     * Stored data whose size is only in the data descriptor after it. The data ends at the first
     * descriptor signature that is followed by the checksum and size of the bytes before it, and
     * then by the next header. Writes the data and returns the bytes read, descriptor included.
     */
    private fun readStoredUntilDescriptor(output: EntryWriter): Long {
        val window = ByteArray(SCAN_CHUNK + DESCRIPTOR_LOOKAHEAD)
        var consumed = 0L
        while (true) {
            val n = peek(window)
            val scan = scanWindow(window, n, ended = n < window.size, output)
            val length = scan.dataEnd + scan.descriptorLength
            input.skip(length.toLong())
            consumed += length
            if (scan.descriptorLength > 0) return consumed
        }
    }

    /**
     * Looks for the data descriptor in the first [n] bytes of [window], and writes the data before
     * it to [output]. When no descriptor is found, keeps the last bytes, which may start one.
     */
    private fun scanWindow(
        window: ByteArray,
        n: Int,
        ended: Boolean,
        output: EntryWriter,
    ): WindowScan {
        var written = 0
        for (i in 0..n - 4) {
            if (window.intAt(i) != ZipWriter.DATA_DESCRIPTOR) continue
            output.write(window, written, i)
            written = i
            val length = descriptorLength(window, i, n, ended, output.crc, output.written)
            if (length == NEED_MORE) return WindowScan(dataEnd = i, descriptorLength = 0)
            if (length > 0) return WindowScan(dataEnd = i, descriptorLength = length)
        }
        if (ended) throw InvalidZipException("The zip file ends too early")
        val dataEnd = maxOf(written, n - 3)
        output.write(window, written, dataEnd)
        return WindowScan(dataEnd, descriptorLength = 0)
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
        val directory = readEndRecords(signature)
        if (!matchesLocalEntries(central, directory, start)) {
            throw InvalidZipException("The zip's central directory does not match its files")
        }
    }

    /** Reads the Zip64 end record and locator, when present, and the end record. */
    private fun readEndRecords(firstSignature: Int): Directory {
        var signature = firstSignature
        var zip64: Directory? = null
        if (signature == ZipWriter.END64) {
            zip64 = readZip64EndRecord()
            signature = input.readIntLe()
        }
        if (signature == ZipWriter.END64_LOCATOR) {
            input.skip(16)
            signature = input.readIntLe()
        }
        if (signature != ZipWriter.END) {
            throw InvalidZipException(if (entries.isEmpty()) "Not a zip file" else "Damaged zip: bad central directory")
        }
        val end = readEndRecord()
        return zip64 ?: end
    }

    private fun readZip64EndRecord(): Directory {
        val record = Buffer().apply { write(input.readByteArray(input.readLongLe().toInt())) }
        record.skip(20)
        val count = record.readLongLe()
        record.skip(8)
        return Directory(count, offset = record.readLongLe())
    }

    private fun readEndRecord(): Directory {
        input.skip(6)
        val count = input.readShortLe().toLong() and 0xFFFF
        input.skip(4)
        val offset = input.readIntLe().toLong() and ZipWriter.MAX_32
        input.skip((input.readShortLe().toLong() and 0xFFFF)) // comment
        return Directory(count, offset)
    }

    private fun matchesLocalEntries(
        central: List<CentralEntry>,
        directory: Directory,
        start: Long,
    ): Boolean {
        val local = entries.associateBy { it.offset }
        return directory.count == central.size.toLong() && central.size == entries.size &&
            directory.offset == start && central.all { local[it.offset]?.matches(it) == true }
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
