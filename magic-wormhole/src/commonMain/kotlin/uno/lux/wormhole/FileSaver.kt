package uno.lux.wormhole

import kotlinx.io.RawSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Stores received files, for [ReceiveEvent.FileOffered.acceptInto]. Use [FileSaver.folder] for a
 * folder on the file system, or implement it for other storage, such as Android's MediaStore.
 *
 * Names given to a saver are safe: one path part, without `/`, `\`, `:` or control characters,
 * and never `.` or `..`.
 */
public interface FileSaver {
    /** Creates a file for a received file of [size] bytes. */
    public suspend fun createFile(
        name: String,
        size: Long,
    ): IncomingFile

    /** Creates a folder for a received directory. */
    public suspend fun createFolder(name: String): IncomingFolder

    public companion object {
        /**
         * Saves into [directory], which is created if needed. Data goes to a hidden `.part` file
         * or folder first. On commit it gets its final name; when the name is taken, a number is
         * added: `photo (1).jpg`.
         */
        public fun folder(directory: Path): FileSaver = FolderSaver(directory)
    }
}

/**
 * A received file being written. The library writes to [sink] and closes it, then calls
 * [commit] when all bytes arrived and were verified, or [discard] when the transfer failed.
 */
public interface IncomingFile {
    public val sink: RawSink

    public suspend fun commit(): SavedFile

    public suspend fun discard()
}

/**
 * A received directory being unpacked. The library calls [createDirectory] and [createFile]
 * (paths are relative, use `/` and never contain `..`), then [commit] when all files are written,
 * or [discard] when unpacking failed.
 */
public interface IncomingFolder : UnzipTarget {
    public suspend fun commit(): SavedFile

    public suspend fun discard()
}

/** Where a received file or folder was saved. [location] is a path or URI to show or open. */
public data class SavedFile(
    val name: String,
    val location: String,
)

/** A received file could not be stored, for example because the disk is full. */
public class SaveFailedException(
    message: String,
    cause: Throwable? = null,
) : WormholeException(message, cause)

private class FolderSaver(
    private val directory: Path,
) : FileSaver {
    private val fileSystem = SystemFileSystem

    override suspend fun createFile(
        name: String,
        size: Long,
    ): IncomingFile {
        fileSystem.createDirectories(directory)
        val safeName = safeFileName(name)
        val partial = uniquePath(".$safeName.part")
        return PartialFile(fileSystem.sink(partial), partial, safeName)
    }

    override suspend fun createFolder(name: String): IncomingFolder {
        val safeName = safeFileName(name)
        val partial = uniquePath(".$safeName.part")
        fileSystem.createDirectories(partial)
        return PartialFolder(partial, safeName)
    }

    /** Moves [partial] to [name], or to a numbered name when [name] is taken. */
    private fun moveToFinalName(
        partial: Path,
        name: String,
    ): SavedFile {
        val target = uniquePath(name)
        fileSystem.atomicMove(partial, target)
        return SavedFile(target.name, target.toString())
    }

    /** A file written to [partial] that gets the name [name] on commit. */
    private inner class PartialFile(
        override val sink: RawSink,
        private val partial: Path,
        private val name: String,
    ) : IncomingFile {
        override suspend fun commit(): SavedFile {
            closeQuietly()
            return moveToFinalName(partial, name)
        }

        override suspend fun discard() {
            closeQuietly()
            fileSystem.delete(partial, mustExist = false)
        }

        private fun closeQuietly() {
            try {
                sink.close()
            } catch (_: Exception) {
            }
        }
    }

    /** A folder unpacked into [partial] that gets the name [name] on commit. */
    private inner class PartialFolder(
        private val partial: Path,
        private val name: String,
    ) : IncomingFolder {
        override fun createDirectory(path: String) {
            fileSystem.createDirectories(resolve(path))
        }

        override fun createFile(path: String): RawSink {
            val file = resolve(path)
            file.parent?.let(fileSystem::createDirectories)
            return fileSystem.sink(file)
        }

        override suspend fun commit(): SavedFile = moveToFinalName(partial, name)

        override suspend fun discard() = deleteRecursively(partial)

        private fun resolve(path: String): Path = Path(partial, *path.split('/').map(::safePathPart).toTypedArray())
    }

    private fun deleteRecursively(path: Path) {
        if (fileSystem.metadataOrNull(path)?.isDirectory == true) fileSystem.list(path).forEach(::deleteRecursively)
        fileSystem.delete(path, mustExist = false)
    }

    private fun uniquePath(name: String): Path {
        var candidate = Path(directory, name)
        var n = 1
        while (fileSystem.exists(candidate)) {
            candidate = Path(directory, numbered(name, n++))
        }
        return candidate
    }
}

private val UNSAFE_CHARACTERS = Regex("""[<>:"|?*\u0000-\u001f]""")

/**
 * The last part of a name offered by the sender, with characters that are not allowed in file
 * names replaced. Like the `wormhole` CLI, `../../a.txt` becomes `a.txt`.
 */
internal fun safeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base.replace(UNSAFE_CHARACTERS, "_").trim().trimStart('.')
    return cleaned.ifEmpty { "received-file" }
}

/** Makes one part of a path in a received folder valid. Unlike [safeFileName], keeps leading dots. */
internal fun safePathPart(name: String): String {
    val cleaned =
        name
            .replace(UNSAFE_CHARACTERS, "_")
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "_" else cleaned
}

/** "photo.jpg" + 2 -> "photo (2).jpg". */
internal fun numbered(
    name: String,
    n: Int,
): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
}
