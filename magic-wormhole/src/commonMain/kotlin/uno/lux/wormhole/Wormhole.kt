package uno.lux.wormhole

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import uno.lux.wormhole.code.Codes
import uno.lux.wormhole.rendezvous.RendezvousClient
import uno.lux.wormhole.rendezvous.RendezvousTransport
import uno.lux.wormhole.rendezvous.WebSocketTransport
import uno.lux.wormhole.transit.DirectHint
import uno.lux.wormhole.transit.TcpTransitNetwork
import uno.lux.wormhole.transit.TransitHints
import uno.lux.wormhole.transit.TransitNetwork
import uno.lux.wormhole.zip.Crc32
import uno.lux.wormhole.zip.ZipFileEntry
import uno.lux.wormhole.zip.ZipWriter

/** Default Magic Wormhole rendezvous (mailbox) server, as used by the current `wormhole` CLI. */
public const val DEFAULT_RENDEZVOUS_URL: String = "wss://relay.magic-wormhole.io/v1"

/** Default Magic Wormhole transit relay. */
public const val DEFAULT_TRANSIT_RELAY: String = "tcp:transit.magic-wormhole.io:4001"

/** Application ID used by the `wormhole` CLI and wormhole-william for text and file transfers. */
public const val DEFAULT_APP_ID: String = "lothar.com/wormhole/text-or-file-xfer"

/** Settings for a [Wormhole]. The defaults are compatible with the `wormhole` CLI. */
public data class WormholeConfig(
    val rendezvousUrl: String = DEFAULT_RENDEZVOUS_URL,
    /** Transit relay as `tcp:host:port`, or null to use direct connections only. */
    val transitRelay: String? = DEFAULT_TRANSIT_RELAY,
    val appId: String = DEFAULT_APP_ID,
    /** Number of words after the nameplate in generated codes. */
    val codeLength: Int = 2,
    /** How received directories are unpacked. See [FolderUnpacker]. */
    val folderUnpacker: FolderUnpacker = FolderUnpacker.streaming(),
) {
    init {
        require(codeLength >= 1) { "codeLength must be at least 1" }
        transitRelay?.let(DirectHint::parseRelayUrl)
    }
}

/** Progress of a send. */
public sealed interface SendEvent {
    /** Give this code to the receiver. */
    public data class CodeAllocated(
        val code: String,
    ) : SendTextEvent,
        SendFileEvent

    public data class Progress(
        val sentBytes: Long,
        val totalBytes: Long,
    ) : SendFileEvent

    /** The receiver got everything. This is the last event. */
    public data object Completed : SendTextEvent, SendFileEvent
}

/** Events emitted by [Wormhole.sendText]. */
public sealed interface SendTextEvent : SendEvent

/** Events emitted by [Wormhole.sendFile] and [Wormhole.sendDirectory]. */
public sealed interface SendFileEvent : SendEvent

/** Progress of a receive. */
public sealed interface ReceiveEvent {
    /** A text message arrived. This is the last event. */
    public data class TextReceived(
        val text: String,
    ) : ReceiveEvent

    /**
     * The sender offers a file. Call [acceptInto], [accept] or [reject]; the flow waits until you do.
     *
     * A directory (or several files) arrives as a zip file named `<dirname>.zip`: [isDirectory]
     * is true, and [fileCount] and [unpackedSize] tell what is inside. [acceptInto] unpacks it for
     * you. With [accept], unpack it with [unzip], using [unpackedSize] and [fileCount] as limits.
     */
    public class FileOffered internal constructor(
        public val name: String,
        public val size: Long,
        public val isDirectory: Boolean,
        private val decision: CompletableDeferred<Destination?>,
        /** Number of files in the directory, if the sender said. Null for a single file. */
        public val fileCount: Int? = null,
        /** Total size of the files in the directory, if the sender said. Null for a single file. */
        public val unpackedSize: Long? = null,
        private val unpacker: FolderUnpacker = FolderUnpacker.streaming(),
    ) : ReceiveEvent {
        /**
         * Saves the file with [saver]. A directory is unpacked into a folder of [saver]; with
         * [unpack] false it is saved as the zip file [name] instead. The saver gets only the last
         * part of the offered name, so a sender cannot choose where files go.
         */
        public fun acceptInto(
            saver: FileSaver,
            unpack: Boolean = true,
        ) {
            decision.complete(SaverDestination(saver, this, unpack, unpacker))
        }

        /** Saves the file in [folder], or unpacks the directory into it. See [FileSaver.folder]. */
        public fun acceptInto(folder: Path) {
            acceptInto(FileSaver.folder(folder))
        }

        /** Receives the file into [sink]. The library closes [sink] when the transfer ends. */
        public fun accept(sink: RawSink) {
            decision.complete(SinkDestination(sink))
        }

        /** Declines the file. The flow then completes. */
        public fun reject() {
            decision.complete(null)
        }

        override fun toString(): String =
            "FileOffered(name=$name, size=$size, isDirectory=$isDirectory, fileCount=$fileCount, unpackedSize=$unpackedSize)"
    }

    public data class Progress(
        val receivedBytes: Long,
        val totalBytes: Long,
    ) : ReceiveEvent

    /** A directory was received and is now being unpacked. [FileReceived] follows. */
    public data object Unpacking : ReceiveEvent

    /**
     * The whole file was received and verified. This is the last event. [saved] tells where
     * [FileOffered.acceptInto] saved the file or unpacked the directory. It is null after
     * [FileOffered.accept].
     */
    public data class FileReceived(
        val saved: SavedFile?,
    ) : ReceiveEvent
}

/**
 * A file to send. [open] returns a new source with the [size] bytes of the file each time it is
 * called; the library calls it only when the transfer runs, and closes the source.
 *
 * For [Wormhole.sendFile], [name] is the file name. For [Wormhole.sendDirectory], it is the path
 * inside the directory, with `/` between folders, for example `photos/2024/beach.jpg`.
 */
public class OutgoingFile(
    public val name: String,
    public val size: Long,
    public val open: () -> RawSource,
) {
    init {
        require(size >= 0) { "Negative size for $name" }
    }

    override fun toString(): String = "OutgoingFile(name=$name, size=$size)"

    internal fun checksummed(): ZipFileEntry {
        val crc = Crc32()
        val buffer = Buffer()
        open().use { source ->
            while (source.readAtMostTo(buffer, CHUNK) != -1L) crc.update(buffer.readByteArray())
        }
        return ZipFileEntry(name, size, crc.value, open)
    }

    internal companion object {
        private const val CHUNK = 64 * 1024L

        fun checkPath(path: String) {
            val parts = path.split('/')
            require(path.isNotEmpty() && '\\' !in path && parts.none { it.isEmpty() || it == "." || it == ".." }) {
                "Invalid path in directory: \"$path\""
            }
        }
    }
}

/**
 * Entry point of the library. Each call returns a cold [Flow]: the transfer starts when you
 * collect it and is cancelled when you stop collecting. Failures are thrown as
 * [WormholeException] subclasses.
 */
public class Wormhole internal constructor(
    private val config: WormholeConfig,
    private val rendezvousTransport: RendezvousTransport,
    private val transitNetwork: () -> TransitNetwork = { TcpTransitNetwork() },
) {
    public constructor(config: WormholeConfig = WormholeConfig()) : this(config, WebSocketTransport)

    private val relay: DirectHint? = config.transitRelay?.let(DirectHint::parseRelayUrl)

    /** Sends [text]. Emits [SendEvent.CodeAllocated], then [SendEvent.Completed]. */
    public fun sendText(text: String): Flow<SendTextEvent> =
        flow {
            coroutineScope {
                withSenderMailbox(this, this@flow::emit) { handle, session ->
                    session.send(buildJsonObject { put("offer", buildJsonObject { put("message", text) }) })
                    val answer = session.receive()
                    answer.throwIfPeerError()
                    val ack = (answer["answer"] as? JsonObject)?.stringValue("message_ack")
                    if (ack != "ok") throw WormholeProtocolException("Unexpected answer to a text offer: $answer")
                    handle.markHappy()
                }
                emit(SendEvent.Completed)
            }
        }

    /**
     * Sends the file at [path], under the name [name]. Emits the same events as the other
     * [sendFile]. Fails with [FileNotFoundException] when the flow is collected and there is no
     * file at [path].
     */
    public fun sendFile(
        path: Path,
        name: String = path.name,
    ): Flow<SendFileEvent> =
        flow {
            val metadata = SystemFileSystem.metadataOrNull(path)
            if (metadata == null || !metadata.isRegularFile) throw FileNotFoundException("No file at $path")
            emitAll(sendFile(OutgoingFile(name, metadata.size) { SystemFileSystem.source(path) }))
        }

    /**
     * Sends [file]. Use this when the data is not a file on the file system, for example an
     * Android `content://` URI. Emits
     * [SendEvent.CodeAllocated], [SendEvent.Progress] updates, then [SendEvent.Completed].
     */
    public fun sendFile(file: OutgoingFile): Flow<SendFileEvent> =
        flow {
            file.open().use { source ->
                coroutineScope {
                    withSenderMailbox(this, this@flow::emit) { handle, session ->
                        val offer = FileTransfer.fileOffer(file.name, file.size)
                        sendOverTransit(session, handle, offer, file.size, source)
                    }
                    emit(SendEvent.Completed)
                }
            }
        }

    /**
     * Sends the folder at [path] with all its files and subfolders, under the name [name]. Empty
     * folders are not sent. Emits the same events as [sendFile].
     */
    public fun sendDirectory(
        path: Path,
        name: String = path.name,
    ): Flow<SendFileEvent> = flow { emitAll(sendDirectory(name, filesIn(path))) }

    /**
     * Sends [files] as a directory called [name], the way `wormhole send <dir>` does: as a zip
     * that the receiver unpacks into a folder called [name]. Use this to send several files at
     * once. Each file is read twice (once for its checksum, once to send it), so
     * [OutgoingFile.open] must return the same bytes each time. Emits the same events as
     * [sendFile]; progress counts bytes of the zip.
     */
    public fun sendDirectory(
        name: String,
        files: List<OutgoingFile>,
    ): Flow<SendFileEvent> {
        checkDirectory(name, files)
        return flow {
            coroutineScope {
                // Checksum the files while the receiver types the code.
                val zip = async { ZipWriter(files.map { it.checksummed() }) }
                withSenderMailbox(this, this@flow::emit) { handle, session ->
                    val writer = zip.await()
                    val offer =
                        FileTransfer.directoryOffer(name, writer.size, files.sumOf { it.size }, files.size)
                    writer.source().use { source -> sendOverTransit(session, handle, offer, writer.size, source) }
                }
                emit(SendEvent.Completed)
            }
        }
    }

    private fun checkDirectory(
        name: String,
        files: List<OutgoingFile>,
    ) {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") {
            "Invalid directory name: \"$name\""
        }
        files.forEach { OutgoingFile.checkPath(it.name) }
        val duplicate = files.groupBy { it.name }.values.firstOrNull { it.size > 1 }
        require(duplicate == null) { "Duplicate path: ${duplicate?.first()?.name}" }
    }

    /** Offers [offer], then sends the [size] bytes of [source] over a transit connection. */
    private suspend fun FlowCollector<SendFileEvent>.sendOverTransit(
        session: WormholeSession,
        handle: Handle,
        offer: JsonObject,
        size: Long,
        source: RawSource,
    ) {
        val network = transitNetwork()
        try {
            FileTransfer.send(session, network, relay, offer, size, source) { sent ->
                emit(SendEvent.Progress(sent, size))
            }
            handle.markHappy()
        } finally {
            network.close()
        }
    }

    /** Receives whatever the sender offers with [code]. */
    public fun receive(code: String): Flow<ReceiveEvent> =
        flow {
            val nameplate = Codes.nameplateOf(code)
            // Set once the destination is open; it must then be finished or discarded.
            var opened: Destination? = null
            val saved =
                try {
                    coroutineScope { receiveInMailbox(this, nameplate, code) { opened = it } }
                    // After the mailbox is closed, so unpacking does not hold the connection open.
                    opened?.finish { emit(ReceiveEvent.Unpacking) }
                } catch (e: Throwable) {
                    opened?.let { withContext(NonCancellable) { it.discard() } }
                    throw e
                }
            if (opened != null) emit(ReceiveEvent.FileReceived(saved))
        }

    private suspend fun FlowCollector<ReceiveEvent>.receiveInMailbox(
        scope: CoroutineScope,
        nameplate: String,
        code: String,
        onOpened: (Destination) -> Unit,
    ) = withMailbox(scope, nameplate = nameplate, code = code) { handle, session ->
        val (offer, senderTransit) = receiveOffer(session)
        val text = offer.stringValue("message")
        if (text != null) {
            acceptText(session, handle, text)
        } else {
            receiveFile(session, handle, offer, senderTransit, onOpened)
        }
    }

    private suspend fun FlowCollector<ReceiveEvent>.acceptText(
        session: WormholeSession,
        handle: Handle,
        text: String,
    ) {
        session.send(buildJsonObject { put("answer", buildJsonObject { put("message_ack", "ok") }) })
        handle.markHappy()
        emit(ReceiveEvent.TextReceived(text))
    }

    private suspend fun FlowCollector<ReceiveEvent>.receiveFile(
        session: WormholeSession,
        handle: Handle,
        offer: JsonObject,
        senderTransit: TransitHints?,
        onOpened: (Destination) -> Unit,
    ) {
        val decision = CompletableDeferred<Destination?>()
        val offered = fileOffered(offer, decision) ?: rejectUnsupported(session, offer)
        emit(offered)
        val destination = decision.await()
        if (destination == null) {
            session.sendError("transfer rejected")
            handle.markHappy()
            return
        }
        openDestination(session, destination)
        onOpened(destination)
        receiveOverTransit(session, handle, senderTransit, offered.size, destination)
    }

    /** The file or directory in [offer], or null when the offer has neither. */
    private fun fileOffered(
        offer: JsonObject,
        decision: CompletableDeferred<Destination?>,
    ): ReceiveEvent.FileOffered? {
        val file = offer["file"] as? JsonObject
        val directory = offer["directory"] as? JsonObject
        val (name, size) =
            when {
                file != null -> {
                    file.stringValue("filename") to file.longValue("filesize")
                }

                directory != null -> {
                    directory.stringValue("dirname")?.let { "$it.zip" } to
                        directory.longValue("zipsize")
                }

                else -> {
                    return null
                }
            }
        if (name == null || size == null || size < 0) return null
        return ReceiveEvent.FileOffered(
            name,
            size,
            isDirectory = directory != null,
            decision,
            fileCount = directory?.longValue("numfiles")?.toInt(),
            unpackedSize = directory?.longValue("numbytes"),
            unpacker = config.folderUnpacker,
        )
    }

    private suspend fun rejectUnsupported(
        session: WormholeSession,
        offer: JsonObject,
    ): Nothing {
        session.sendError("unsupported offer")
        throw WormholeProtocolException("Unsupported offer: $offer")
    }

    private suspend fun openDestination(
        session: WormholeSession,
        destination: Destination,
    ) {
        try {
            destination.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // For example, the disk is full. The CLI answers the same way.
            session.sendError("transfer rejected")
            throw e
        }
    }

    private suspend fun FlowCollector<ReceiveEvent>.receiveOverTransit(
        session: WormholeSession,
        handle: Handle,
        senderTransit: TransitHints?,
        size: Long,
        destination: Destination,
    ) {
        val network = transitNetwork()
        try {
            FileTransfer.receive(session, network, relay, senderTransit, size, destination::write) { received ->
                emit(ReceiveEvent.Progress(received, size))
            }
            handle.markHappy()
        } finally {
            network.close()
        }
    }

    /** Reads phases until the sender's offer arrives. Also returns the sender's transit hints. */
    private suspend fun receiveOffer(session: WormholeSession): Pair<JsonObject, TransitHints?> {
        var transit: TransitHints? = null
        while (true) {
            val message = session.receive()
            message.throwIfPeerError()
            (message["transit"] as? JsonObject)?.let { transit = TransitHints.parse(it) }
            (message["offer"] as? JsonObject)?.let { return it to transit }
        }
    }

    // --- mailbox plumbing ---------------------------------------------------------------------

    /** Tracks the mood reported when the mailbox is closed. */
    private class Handle {
        var mood = "errory"

        fun markHappy() {
            mood = "happy"
        }
    }

    private suspend fun withSenderMailbox(
        scope: CoroutineScope,
        onCodeAllocated: suspend (SendEvent.CodeAllocated) -> Unit,
        block: suspend (Handle, WormholeSession) -> Unit,
    ) {
        val client = RendezvousClient.connect(rendezvousTransport, config.rendezvousUrl, config.appId, scope)
        val handle = Handle()
        var mailbox: String? = null
        try {
            client.bind()
            val nameplate = client.allocate()
            mailbox = client.claim(nameplate)
            client.open(mailbox)
            val code = Codes.build(nameplate, config.codeLength)
            onCodeAllocated(SendEvent.CodeAllocated(code))
            val session = WormholeSession(client, config.appId)
            exchangeKeys(session, code, handle)
            client.release(nameplate)
            block(handle, session)
        } finally {
            closeClient(client, mailbox, handle.mood)
        }
    }

    private suspend fun withMailbox(
        scope: CoroutineScope,
        nameplate: String,
        code: String,
        block: suspend (Handle, WormholeSession) -> Unit,
    ) {
        val client = RendezvousClient.connect(rendezvousTransport, config.rendezvousUrl, config.appId, scope)
        val handle = Handle()
        var mailbox: String? = null
        try {
            client.bind()
            mailbox = client.claim(nameplate)
            client.open(mailbox)
            val session = WormholeSession(client, config.appId)
            exchangeKeys(session, code, handle)
            client.release(nameplate)
            block(handle, session)
        } finally {
            closeClient(client, mailbox, handle.mood)
        }
    }

    private suspend fun exchangeKeys(
        session: WormholeSession,
        code: String,
        handle: Handle,
    ) {
        try {
            session.exchangeKeys(code)
        } catch (e: WrongCodeException) {
            handle.mood = "scary"
            throw e
        }
    }

    private suspend fun closeClient(
        client: RendezvousClient,
        mailbox: String?,
        mood: String,
    ) {
        if (mailbox != null) client.close(mailbox, mood) else client.shutdown()
    }
}

private suspend fun WormholeSession.sendError(text: String) = send(buildJsonObject { put("error", text) })

internal fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.longValue(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

/** Throws when the other side sent `{"error": "..."}`. */
internal fun JsonObject.throwIfPeerError() {
    val error = this["error"] ?: return
    val text = (error as? JsonPrimitive)?.contentOrNull ?: error.toString()
    if (text == "transfer rejected") throw TransferRejectedException()
    throw PeerErrorException(text)
}
