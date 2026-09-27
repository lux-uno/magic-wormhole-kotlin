package uno.lux.wormhole

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * How a received directory (a zip) is unpacked. Set it in [WormholeConfig.folderUnpacker].
 *
 * - [streaming] (the default) unpacks while the data arrives: no temporary copy of the zip, and
 *   the folder is ready when the last byte arrives.
 * - [temporaryFile] saves the zip to a temporary file first and unpacks it when the transfer is
 *   done. It needs twice the disk space, but reads any zip the other one does not.
 */
public interface FolderUnpacker {
    /**
     * Starts unpacking a zip of [zipSize] bytes into [target]. The library then writes the zip's
     * bytes to the returned [ZipReceiver], and finally calls [ZipReceiver.finish] or
     * [ZipReceiver.discard]. [maxBytes] and [maxFiles] limit what the zip may hold; see [unzip].
     */
    public suspend fun start(
        target: UnzipTarget,
        zipSize: Long,
        maxBytes: Long,
        maxFiles: Int,
    ): ZipReceiver

    public companion object {
        /** Unpacks while the data arrives. */
        public fun streaming(): FolderUnpacker = StreamingUnpacker

        /** Saves the zip in [directory] first and unpacks it after the transfer. */
        public fun temporaryFile(directory: Path = SystemTemporaryDirectory): FolderUnpacker =
            TemporaryFileUnpacker(directory)
    }
}

/** Receives the bytes of one zip for a [FolderUnpacker]. */
public interface ZipReceiver {
    /** The next bytes of the zip, in order. */
    public suspend fun write(bytes: ByteArray)

    /**
     * Called after all bytes arrived and their checksum matched. Finishes unpacking. Calls
     * [onUnpacking] first when unpacking still takes a while.
     */
    public suspend fun finish(onUnpacking: suspend () -> Unit)

    /** Called when the transfer failed. Stops unpacking and removes temporary data. */
    public suspend fun discard()
}

private data object StreamingUnpacker : FolderUnpacker {
    override suspend fun start(
        target: UnzipTarget,
        zipSize: Long,
        maxBytes: Long,
        maxFiles: Int,
    ): ZipReceiver = StreamingZipReceiver(target, maxBytes, maxFiles)
}

/**
 * Feeds the zip through a channel to a [StreamingZipReader] on an IO thread. The reader blocks that
 * thread while it waits for data, because the inflater reads a blocking [kotlinx.io.Source].
 */
private class StreamingZipReceiver(
    target: UnzipTarget,
    maxBytes: Long,
    maxFiles: Int,
) : ZipReceiver {
    private val channel = Channel<ByteArray>(capacity = 16)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val reader =
        scope.async {
            val job = currentCoroutineContext().job
            StreamingZipReader(ChannelSource(channel).buffered(), target, maxBytes, maxFiles) { job.ensureActive() }
                .readAll()
        }

    override suspend fun write(bytes: ByteArray) {
        // The reader only ends early when it fails. Then fail with its error instead of waiting
        // forever for it to take the bytes.
        select {
            channel.onSend(bytes) {}
            reader.onAwait {}
        }
        if (reader.isCompleted) reader.await()
    }

    override suspend fun finish(onUnpacking: suspend () -> Unit) {
        channel.close()
        try {
            reader.await()
        } finally {
            scope.cancel()
        }
    }

    override suspend fun discard() {
        scope.cancel()
        channel.cancel()
    }
}

/** Blocking reads from [channel]. The channel's end is the end of the data. */
private class ChannelSource(
    private val channel: ReceiveChannel<ByteArray>,
) : RawSource {
    private val pending = Buffer()

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        while (pending.exhausted()) {
            val chunk = runBlocking { channel.receiveCatching() }.getOrNull() ?: return -1
            pending.write(chunk)
        }
        return pending.readAtMostTo(sink, byteCount)
    }

    override fun close() = Unit
}

private data class TemporaryFileUnpacker(
    val directory: Path,
) : FolderUnpacker {
    override suspend fun start(
        target: UnzipTarget,
        zipSize: Long,
        maxBytes: Long,
        maxFiles: Int,
    ): ZipReceiver = TemporaryFileZipReceiver(directory, target, maxBytes, maxFiles)
}

private class TemporaryFileZipReceiver(
    directory: Path,
    private val target: UnzipTarget,
    private val maxBytes: Long,
    private val maxFiles: Int,
) : ZipReceiver {
    private val path = Path(directory, "wormhole-${Random.nextLong().toULong().toString(16)}.zip")
    private val sink =
        run {
            SystemFileSystem.createDirectories(directory)
            SystemFileSystem.sink(path)
        }
    private val buffer = Buffer()

    override suspend fun write(bytes: ByteArray) {
        buffer.write(bytes)
        sink.write(buffer, buffer.size)
    }

    override suspend fun finish(onUnpacking: suspend () -> Unit) {
        try {
            sink.close()
            onUnpacking()
            unzipFile(path, target, maxBytes, maxFiles)
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    override suspend fun discard() {
        runCatching { sink.close() }
        runCatching { SystemFileSystem.delete(path, mustExist = false) }
    }
}
