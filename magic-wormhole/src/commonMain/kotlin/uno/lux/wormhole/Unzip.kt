package uno.lux.wormhole

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
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

/** The zip is damaged, unsafe (a path leaves the target folder) or larger than allowed. */
public class InvalidZipException(
    message: String,
) : WormholeException(message)

/** Where [unzip] puts the files. Paths are relative, use `/` and never contain `..`. */
public interface UnzipTarget {
    /** Creates the folder [path] and its parents. */
    public fun createDirectory(path: String)

    /** Opens [path] for writing. Creates its parent folders first. [unzip] closes the sink. */
    public fun createFile(path: String): RawSink
}

/**
 * Unpacks a received directory (see [ReceiveEvent.FileOffered.isDirectory]) into [target].
 * Reads zips made by the `wormhole` CLI, wormhole-william and [Wormhole.sendDirectory].
 *
 * The zip is [size] bytes long; [open] returns a new source that starts at the given byte offset
 * (a few small reads near the end, then one read from the start). The library closes the sources.
 *
 * Fails with [InvalidZipException] when a path would leave the target folder, a checksum does not
 * match, or the zip holds more than [maxBytes] bytes or [maxFiles] files. Pass the offer's
 * [ReceiveEvent.FileOffered.unpackedSize] and [ReceiveEvent.FileOffered.fileCount] so a small zip
 * cannot fill the disk. Files written before a failure stay in [target].
 */
public suspend fun unzip(
    size: Long,
    open: (offset: Long) -> RawSource,
    target: UnzipTarget,
    maxBytes: Long = Long.MAX_VALUE,
    maxFiles: Int = Int.MAX_VALUE,
) {
    val job = currentCoroutineContext()[Job]
    try {
        ZipReader(size, open, target, maxBytes, maxFiles) { job?.ensureActive() }.readAll()
    } catch (e: EOFException) {
        throw InvalidZipException("The zip file ends too early")
    }
}

/** Reads the central directory at the end of the zip, then the entries in file order. */
private class ZipReader(
    private val size: Long,
    private val open: (Long) -> RawSource,
    private val target: UnzipTarget,
    private val maxBytes: Long,
    private val maxFiles: Int,
    private val checkCancelled: () -> Unit,
) {
    private val unpackedBytes = UnpackedBytes(maxBytes)

    /** Where the central directory is, and how many entries it holds. */
    private class Directory(
        val count: Long,
        val size: Long,
        val offset: Long,
    )

    fun readAll() {
        val entries = readCentralDirectory().sortedBy { it.offset }
        checkAnnouncedLimits(entries)
        open(0).buffered().use { input -> extractAll(entries, input) }
    }

    private fun checkAnnouncedLimits(entries: List<CentralEntry>) {
        val files = entries.filterNot { it.isDirectory() }
        if (files.size > maxFiles) throw InvalidZipException("The zip holds more files than announced")
        if (files.sumOf { it.size } > maxBytes) throw InvalidZipException("The zip holds more data than announced")
    }

    private fun extractAll(
        entries: List<CentralEntry>,
        input: Source,
    ) {
        var position = 0L
        for (entry in entries) {
            checkCancelled()
            if (entry.offset < position) throw InvalidZipException("Overlapping entries in zip")
            input.skip(entry.offset - position)
            position = entry.offset + skipLocalHeader(input)
            extract(entry, input)
            position += entry.compressedSize
        }
    }

    /** Skips the local header at the start of [input], and returns its length. */
    private fun skipLocalHeader(input: Source): Long {
        if (input.readIntLe() != ZipWriter.LOCAL_HEADER) throw InvalidZipException("Damaged zip: missing local header")
        input.skip(22)
        val nameLength = input.readShortLe().toInt() and 0xFFFF
        val extraLength = input.readShortLe().toInt() and 0xFFFF
        input.skip((nameLength + extraLength).toLong())
        return 30L + nameLength + extraLength
    }

    private fun readCentralDirectory(): List<CentralEntry> {
        val directory = findCentralDirectory()
        if (directory.offset + directory.size > size || directory.count < 0 ||
            directory.count > directory.size / 46
        ) {
            throw InvalidZipException("Damaged zip: bad central directory")
        }
        return open(directory.offset).buffered().use { input ->
            List(directory.count.toInt()) { readCentralHeader(input, directory.offset) }
        }
    }

    /** Reads the end record from the last bytes of the zip, and the Zip64 end record if there is one. */
    private fun findCentralDirectory(): Directory {
        if (size < END_SIZE) throw InvalidZipException("Not a zip file")
        val tailStart = maxOf(0L, size - MAX_TAIL)
        val tail = open(tailStart).buffered().use { it.readByteArray((size - tailStart).toInt()) }
        val end = findEndRecord(tail)
        val locator = end - 20
        if (locator >= 0 && tail.intAt(locator) == ZipWriter.END64_LOCATOR) {
            return readZip64EndRecord(tail, tailStart, locator)
        }
        return Directory(
            count = tail.shortAt(end + 10).toLong(),
            size = tail.intAt(end + 12).toLong() and 0xFFFFFFFFL,
            offset = tail.intAt(end + 16).toLong() and 0xFFFFFFFFL,
        )
    }

    /** The position of the end record in [tail]: the last one whose comment ends the zip. */
    private fun findEndRecord(tail: ByteArray): Int =
        (tail.size - END_SIZE downTo 0).firstOrNull { i ->
            tail.intAt(i) == ZipWriter.END && i + END_SIZE + tail.shortAt(i + 20) == tail.size
        } ?: throw InvalidZipException("Not a zip file, or a damaged one")

    private fun readZip64EndRecord(
        tail: ByteArray,
        tailStart: Long,
        locator: Int,
    ): Directory {
        val end64Offset = Buffer().apply { write(tail, locator + 8, locator + 16) }.readLongLe()
        val record =
            if (end64Offset >= tailStart) {
                Buffer().apply { write(tail, (end64Offset - tailStart).toInt(), tail.size) }
            } else {
                Buffer().apply { open(end64Offset).buffered().use { write(it.readByteArray(56)) } }
            }
        if (record.readIntLe() != ZipWriter.END64) throw InvalidZipException("Damaged Zip64 end record")
        record.skip(28)
        val count = record.readLongLe()
        val directorySize = record.readLongLe()
        val directoryOffset = record.readLongLe()
        return Directory(count, directorySize, directoryOffset)
    }

    /** Reads one central directory header, and checks that its data ends before [directoryOffset]. */
    private fun readCentralHeader(
        input: Source,
        directoryOffset: Long,
    ): CentralEntry {
        if (input.readIntLe() != ZipWriter.CENTRAL_HEADER) throw InvalidZipException("Damaged central directory")
        val entry = readCentralEntry(input)
        if (entry.offset + entry.compressedSize > directoryOffset) {
            throw InvalidZipException("Damaged zip: bad entry ${entry.name}")
        }
        return entry
    }

    private fun extract(
        entry: CentralEntry,
        input: Source,
    ) {
        if (entry.flags and FLAG_ENCRYPTED != 0) throw InvalidZipException("Encrypted zip files are not supported")
        val sink = target.openEntry(safePath(entry.name), entry.isDirectory())
        val output = EntryWriter(sink, entry.size, unpackedBytes, checkCancelled)
        val data = LimitedSource(input, entry.compressedSize).buffered()
        output.use { decompress(entry.method, data, it) }
        data.transferTo(discardingSink())
        if (output.written != entry.size || output.crc != entry.crc) {
            throw InvalidZipException("Damaged file in zip: ${entry.name}")
        }
    }

    private fun ByteArray.intAt(i: Int) =
        (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8) or
            ((this[i + 2].toInt() and 0xFF) shl 16) or ((this[i + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.shortAt(i: Int) = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val FLAG_ENCRYPTED = 1
        const val END_SIZE = 22

        // End record with the longest comment, plus the Zip64 locator before it.
        const val MAX_TAIL = END_SIZE + 0xFFFF + 20L
    }
}

/** Counts the bytes unpacked from all entries, and fails when they pass [maxBytes]. */
internal class UnpackedBytes(
    private val maxBytes: Long,
) {
    private var total = 0L

    fun add(count: Int) {
        total += count
        if (total > maxBytes) throw InvalidZipException("The zip holds more data than announced")
    }
}

/**
 * Takes the unpacked bytes of one entry: checks them against [maxSize] and the [total] limit,
 * computes their checksum, and writes them to [sink]. A null [sink] drops the bytes.
 */
internal class EntryWriter(
    private val sink: RawSink?,
    private val maxSize: Long,
    private val total: UnpackedBytes,
    private val checkCancelled: () -> Unit,
) {
    private val checksum = Crc32()
    private val buffer = Buffer()

    var written = 0L
        private set

    val crc: Int get() = checksum.value

    fun write(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {
        checkCancelled()
        written += end - start
        total.add(end - start)
        if (written > maxSize) throw InvalidZipException("The zip holds more data than announced")
        checksum.update(bytes, start, end)
        if (sink != null) {
            buffer.write(bytes, start, end)
            sink.write(buffer, buffer.size)
        }
    }

    /** Runs [block], then flushes the sink. Closes the sink in every case. */
    fun <T> use(block: (EntryWriter) -> T): T =
        try {
            block(this).also { sink?.flush() }
        } finally {
            sink?.close()
        }
}

/** Creates the folder or opens the file for an entry. Returns null when there is no file to write. */
internal fun UnzipTarget.openEntry(
    path: String?,
    isDirectory: Boolean,
): RawSink? =
    when {
        path == null -> {
            null
        }

        isDirectory -> {
            createDirectory(path)
            null
        }

        else -> {
            createFile(path)
        }
    }

/** Unpacks the entry [data] stored with [method] into [output]. */
internal fun decompress(
    method: Short,
    data: Source,
    output: EntryWriter,
) {
    when (method) {
        ZipWriter.METHOD_STORED -> copy(data, output::write)
        ZipWriter.METHOD_DEFLATED -> Inflater(data).inflate(output::write)
        else -> throw InvalidZipException("Unsupported compression method $method")
    }
}

internal fun isDirectoryName(name: String) = name.endsWith('/') || name.endsWith('\\')

/**
 * Turns a zip entry name into a safe relative path: `\` becomes `/`, and `.` and empty parts are
 * dropped. Returns null when nothing is left (the entry is the folder itself). Throws for
 * absolute paths, drive letters, `..` and control characters.
 */
internal fun safePath(name: String): String? {
    val normalized = name.replace('\\', '/')
    if (normalized.startsWith('/') || normalized.any { it < ' ' } || ':' in normalized) {
        throw InvalidZipException("Unsafe path in zip: $name")
    }
    val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.any { it == ".." }) throw InvalidZipException("Unsafe path in zip: $name")
    return parts.joinToString("/").ifEmpty { null }
}

internal fun copy(
    source: Source,
    write: (ByteArray, Int, Int) -> Unit,
) {
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val n = source.readAtMostTo(chunk, 0, chunk.size)
        if (n == -1) return
        write(chunk, 0, n)
    }
}

/** Reads at most [remaining] bytes of [source], and fails if the source ends before that. */
internal class LimitedSource(
    private val source: Source,
    private var remaining: Long,
) : RawSource {
    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (remaining == 0L) return -1
        val n = source.readAtMostTo(sink, minOf(byteCount, remaining))
        if (n == -1L) throw InvalidZipException("The zip file ends too early")
        remaining -= n
        return n
    }

    override fun close() = Unit
}

/** One entry of the central directory. */
internal class CentralEntry(
    val name: String,
    val flags: Int,
    val method: Short,
    val crc: Int,
    val compressedSize: Long,
    val size: Long,
    val offset: Long,
) {
    fun isDirectory() = isDirectoryName(name)
}

/** Reads one central directory header, after its signature. Resolves Zip64 sizes and offsets. */
internal fun readCentralEntry(input: Source): CentralEntry {
    input.skip(4)
    val flags = input.readShortLe().toInt() and 0xFFFF
    val method = input.readShortLe()
    input.skip(4)
    val crc = input.readIntLe()
    var compressedSize = input.readIntLe().toLong() and 0xFFFFFFFFL
    var uncompressedSize = input.readIntLe().toLong() and 0xFFFFFFFFL
    val nameLength = input.readShortLe().toInt() and 0xFFFF
    val extraLength = input.readShortLe().toInt() and 0xFFFF
    val commentLength = input.readShortLe().toInt() and 0xFFFF
    input.skip(8)
    var offset = input.readIntLe().toLong() and 0xFFFFFFFFL
    val name = input.readByteArray(nameLength).decodeToString()
    val extra = input.readByteArray(extraLength)
    input.skip(commentLength.toLong())
    forEachZip64Field(extra) { field ->
        if (uncompressedSize == ZipWriter.MAX_32 && field.size >= 8) uncompressedSize = field.readLongLe()
        if (compressedSize == ZipWriter.MAX_32 && field.size >= 8) compressedSize = field.readLongLe()
        if (offset == ZipWriter.MAX_32 && field.size >= 8) offset = field.readLongLe()
    }
    if (compressedSize < 0 || uncompressedSize < 0 || offset < 0) {
        throw InvalidZipException("Damaged zip: bad entry $name")
    }
    return CentralEntry(name, flags, method, crc, compressedSize, uncompressedSize, offset)
}

/** Calls [action] with the data of each Zip64 field in the [extra] fields of a header. */
internal inline fun forEachZip64Field(
    extra: ByteArray,
    action: (Buffer) -> Unit,
) {
    val fields = Buffer().apply { write(extra) }
    while (fields.size >= 4) {
        val id = fields.readShortLe()
        val length = (fields.readShortLe().toInt() and 0xFFFF).toLong()
        if (fields.size < length) break
        val field = Buffer().apply { write(fields, length) }
        if (id == ZipWriter.ZIP64_EXTRA) action(field)
    }
}
