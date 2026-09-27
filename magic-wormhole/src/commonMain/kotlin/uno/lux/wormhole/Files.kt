package uno.lux.wormhole

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

private suspend fun unzipFile(
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
    /** Opens the sink for the received bytes. Storage errors are thrown as [SaveFailedException]. */
    suspend fun open(): RawSink

    /**
     * Called after all bytes arrived and the sink was closed. Calls [onUnpacking] before it
     * unpacks a directory. Returns where the file was saved, if known.
     */
    suspend fun finish(onUnpacking: suspend () -> Unit): SavedFile? = null

    /** Called when the transfer failed after [open]. */
    suspend fun discard() = Unit
}

internal class SinkDestination(
    private val sink: RawSink,
) : Destination {
    override suspend fun open() = sink
}

/**
 * Saves through [saver]. When [unpack] is true, a directory goes to a temporary zip first and
 * [finish] unpacks it into a folder of the saver.
 */
internal class SaverDestination(
    private val saver: FileSaver,
    private val offer: ReceiveEvent.FileOffered,
    unpack: Boolean,
) : Destination {
    private val unpacks = unpack && offer.isDirectory
    private var file: IncomingFile? = null
    private var zip: Path? = null

    override suspend fun open(): RawSink =
        saving("Could not create the file") {
            if (unpacks) {
                val path = Path(SystemTemporaryDirectory, "wormhole-${Random.nextLong().toULong().toString(16)}.zip")
                zip = path
                SystemFileSystem.sink(path)
            } else {
                saver.createFile(safeFileName(offer.name), offer.size).also { file = it }.sink
            }
        }

    override suspend fun finish(onUnpacking: suspend () -> Unit): SavedFile {
        file?.let { return saving("Could not save the file") { it.commit() } }
        val zip = zip!!
        try {
            onUnpacking()
            val name = safeFileName(offer.name.removeSuffix(".zip"))
            val folder = saving("Could not create the folder") { saver.createFolder(name) }
            try {
                saving("Could not save the folder") {
                    unzipFile(zip, folder, offer.unpackedSize ?: Long.MAX_VALUE, offer.fileCount ?: Int.MAX_VALUE)
                }
                return saving("Could not save the folder") { folder.commit() }
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { folder.discard() } }
                throw e
            }
        } finally {
            SystemFileSystem.delete(zip, mustExist = false)
        }
    }

    override suspend fun discard() {
        runCatching { file?.discard() }
        zip?.let { runCatching { SystemFileSystem.delete(it, mustExist = false) } }
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
