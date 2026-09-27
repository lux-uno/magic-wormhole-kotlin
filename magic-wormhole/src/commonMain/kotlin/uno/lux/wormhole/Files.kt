package uno.lux.wormhole

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * Unpacks the zip file [zip] into [folder], which is created if needed. See the other [unzip]
 * for the checks and for [maxBytes] and [maxFiles].
 */
public suspend fun unzip(
    zip: Path,
    folder: Path,
    maxBytes: Long = Long.MAX_VALUE,
    maxFiles: Int = Int.MAX_VALUE,
) {
    SystemFileSystem.createDirectories(folder)
    unzipFile(zip, FolderUnzipTarget(folder), maxBytes, maxFiles)
}

internal suspend fun unzipFile(
    zip: Path,
    target: UnzipTarget,
    maxBytes: Long,
    maxFiles: Int,
) {
    val size = SystemFileSystem.metadataOrNull(zip)?.size ?: throw FileNotFoundException("$zip")
    unzip(size, { offset -> openFileAt(zip, offset) }, target, maxBytes, maxFiles)
}

/** Opens [path] for reading from byte [offset] on, seeking where the platform can. */
internal expect fun openFileAt(
    path: Path,
    offset: Long,
): RawSource

private class FolderUnzipTarget(
    private val folder: Path,
) : UnzipTarget {
    override fun createDirectory(path: String) = SystemFileSystem.createDirectories(resolve(path))

    override fun createFile(path: String): RawSink {
        val file = resolve(path)
        file.parent?.let { SystemFileSystem.createDirectories(it) }
        return SystemFileSystem.sink(file)
    }

    private fun resolve(path: String) = Path(folder, *path.split('/').toTypedArray())
}

/** The files in [folder] and its subfolders, with paths relative to [folder]. */
internal fun filesIn(folder: Path): List<OutgoingFile> {
    require(SystemFileSystem.metadataOrNull(folder)?.isDirectory == true) { "Not a folder: $folder" }
    val entries = mutableListOf<OutgoingFile>()
    val visited = mutableSetOf<Path>()

    fun walk(
        dir: Path,
        prefix: String,
    ) {
        // Symbolic links can make loops.
        if (!visited.add(SystemFileSystem.resolve(dir))) return
        for (child in SystemFileSystem.list(dir).sortedBy { it.name }) {
            val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
            val path = prefix + child.name
            when {
                metadata.isDirectory -> {
                    walk(child, "$path/")
                }

                metadata.isRegularFile -> {
                    entries +=
                        OutgoingFile(path, metadata.size) { SystemFileSystem.source(child) }
                }
            }
        }
    }
    walk(folder, "")
    return entries
}

/** Where a received file goes. */
internal interface Destination {
    /** Prepares the storage. Storage errors are thrown as [SaveFailedException]. */
    suspend fun open()

    /** The next received bytes. */
    suspend fun write(bytes: ByteArray)

    /**
     * Called after all bytes arrived. Calls [onUnpacking] before a slow unpack. Returns where the
     * file was saved, if known.
     */
    suspend fun finish(onUnpacking: suspend () -> Unit): SavedFile?

    /** Called when the transfer failed after [open]. */
    suspend fun discard()
}

internal class SinkDestination(
    private val sink: RawSink,
) : Destination {
    private val buffer = Buffer()

    override suspend fun open() = Unit

    override suspend fun write(bytes: ByteArray) {
        buffer.write(bytes)
        sink.write(buffer, buffer.size)
    }

    override suspend fun finish(onUnpacking: suspend () -> Unit): SavedFile? {
        sink.flush()
        sink.close()
        return null
    }

    override suspend fun discard() {
        runCatching { sink.close() }
    }
}

/**
 * Saves through [saver]. When [unpack] is true, a directory is unpacked by [unpacker] into a
 * folder of the saver.
 */
internal class SaverDestination(
    private val saver: FileSaver,
    private val offer: ReceiveEvent.FileOffered,
    unpack: Boolean,
    private val unpacker: FolderUnpacker,
) : Destination {
    private val unpacks = unpack && offer.isDirectory
    private var file: IncomingFile? = null
    private var folder: IncomingFolder? = null
    private var zip: ZipReceiver? = null
    private val buffer = Buffer()

    override suspend fun open() {
        try {
            saving("Could not create the file") {
                if (unpacks) {
                    val folder = saver.createFolder(safeFileName(offer.name.removeSuffix(".zip"))).also { folder = it }
                    val maxBytes = offer.unpackedSize ?: Long.MAX_VALUE
                    zip = unpacker.start(folder, offer.size, maxBytes, offer.fileCount ?: Int.MAX_VALUE)
                } else {
                    file = saver.createFile(safeFileName(offer.name), offer.size)
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { discard() }
            throw e
        }
    }

    override suspend fun write(bytes: ByteArray) =
        saving("Could not save the file") {
            val zip = zip
            if (zip != null) {
                zip.write(bytes)
            } else {
                buffer.write(bytes)
                file!!.sink.write(buffer, buffer.size)
            }
        }

    override suspend fun finish(onUnpacking: suspend () -> Unit): SavedFile =
        saving("Could not save the file") {
            val file = file
            if (file != null) {
                file.sink.flush()
                file.sink.close()
                file.commit()
            } else {
                zip!!.finish(onUnpacking)
                folder!!.commit()
            }
        }

    override suspend fun discard() {
        runCatching { zip?.discard() }
        runCatching { folder?.discard() }
        file?.let {
            runCatching { it.sink.close() }
            runCatching { it.discard() }
        }
    }
}

/** Runs [block] and reports storage errors as [SaveFailedException], with [what] when they have no message. */
private suspend fun <T> saving(
    what: String,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: WormholeException) {
        throw e
    } catch (e: Exception) {
        throw SaveFailedException(e.message ?: what, e)
    }
