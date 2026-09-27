package uno.lux.wormhole

import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
    val size = SystemFileSystem.metadataOrNull(zip)?.size ?: throw kotlinx.io.files.FileNotFoundException("$zip")
    SystemFileSystem.createDirectories(folder)
    unzip(
        size,
        { offset -> SystemFileSystem.source(zip).buffered().apply { skip(offset) } },
        FolderUnzipTarget(folder),
        maxBytes,
        maxFiles,
    )
}

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
internal fun filesIn(folder: Path): List<DirectoryEntry> {
    require(SystemFileSystem.metadataOrNull(folder)?.isDirectory == true) { "Not a folder: $folder" }
    val entries = mutableListOf<DirectoryEntry>()
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
                        DirectoryEntry(path, metadata.size) { SystemFileSystem.source(child) }
                }
            }
        }
    }
    walk(folder, "")
    return entries
}

/**
 * The last part of a name offered by the sender, so that the file stays in the receiver's folder.
 * Like the `wormhole` CLI, `../../a.txt` becomes `a.txt`.
 */
internal fun safeFileName(offered: String): String {
    val name = offered.substringAfterLast('/').substringAfterLast('\\')
    if (name.isEmpty() || name == "." || name == ".." || ':' in name || name.any { it < ' ' }) {
        throw WormholeProtocolException("Unsafe file name: \"$offered\"")
    }
    return name
}

/** Where a received file goes. */
internal interface Destination {
    /** Opens the sink for the received bytes. */
    fun open(): RawSink

    /** Called after all bytes arrived and the sink was closed. Returns where the file was saved, if known. */
    suspend fun finish(): Path? = null

    /** Called when the transfer failed after [open]. */
    fun discard() = Unit
}

internal class SinkDestination(
    private val sink: RawSink,
) : Destination {
    override fun open() = sink
}

/**
 * Saves a file as `folder/<name>`, or unpacks a directory into `folder/<dirname>`. The data goes
 * to a `.part` file first, so a failed transfer leaves nothing behind.
 */
internal class FolderDestination(
    private val folder: Path,
    private val offer: ReceiveEvent.FileOffered,
) : Destination {
    // Computed in open(), so an unsafe name fails the transfer instead of the caller of accept.
    private val target by lazy {
        Path(folder, safeFileName(if (offer.isDirectory) offer.name.removeSuffix(".zip") else offer.name))
    }
    private val part by lazy { Path(folder, "${target.name}${if (offer.isDirectory) ".zip" else ""}.part") }

    override fun open(): RawSink {
        for (path in listOf(target, part)) {
            if (SystemFileSystem.exists(path)) throw IOException("Refusing to overwrite $path")
        }
        return SystemFileSystem.sink(part)
    }

    override suspend fun finish(): Path {
        if (!offer.isDirectory) {
            SystemFileSystem.atomicMove(part, target)
            return target
        }
        try {
            unzip(part, target, offer.unpackedSize ?: Long.MAX_VALUE, offer.fileCount ?: Int.MAX_VALUE)
        } finally {
            discard()
        }
        return target
    }

    override fun discard() = SystemFileSystem.delete(part, mustExist = false)
}
