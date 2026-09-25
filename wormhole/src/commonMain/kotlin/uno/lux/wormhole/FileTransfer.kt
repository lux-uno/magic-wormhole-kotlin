// File transfer over transit, following magic-wormhole's cmd_send.py / cmd_receive.py (MIT).
package uno.lux.wormhole

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.kotlincrypto.hash.sha2.SHA256
import uno.lux.wormhole.transit.DirectHint
import uno.lux.wormhole.transit.Transit
import uno.lux.wormhole.transit.TransitHints
import uno.lux.wormhole.transit.TransitNetwork
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal object FileTransfer {
    // magic-wormhole always derives the transit key from this app ID (its issue #339).
    private const val TRANSIT_KEY_PURPOSE = "lothar.com/wormhole/text-or-file-xfer/transit-key"
    private const val CHUNK_SIZE = 16 * 1024L
    private val PROGRESS_INTERVAL = 100.milliseconds

    suspend fun send(
        session: WormholeSession,
        network: TransitNetwork,
        relay: DirectHint?,
        name: String,
        size: Long,
        source: RawSource,
        onProgress: suspend (Long) -> Unit,
    ) {
        val transit = Transit(Transit.Role.SENDER, session.deriveKey(TRANSIT_KEY_PURPOSE), network, relay)
        try {
            session.send(buildJsonObject { put("transit", transit.start()) })
            session.send(buildJsonObject {
                put("offer", buildJsonObject {
                    put("file", buildJsonObject {
                        put("filename", name)
                        put("filesize", size)
                    })
                })
            })

            var peerHints = TransitHints(emptyList(), emptyList())
            while (true) {
                val message = session.receive()
                message.throwIfPeerError()
                (message["transit"] as? JsonObject)?.let { peerHints = TransitHints.parse(it) }
                val answer = message["answer"] as? JsonObject ?: continue
                if (answer.stringValue("file_ack") != "ok") {
                    throw WormholeProtocolException("Unexpected answer to a file offer: $answer")
                }
                break
            }

            val pipe = transit.connect(peerHints)
            try {
                val hasher = SHA256()
                val buffer = Buffer()
                val throttle = ProgressThrottle(onProgress)
                var sent = 0L
                while (true) {
                    val n = source.readAtMostTo(buffer, CHUNK_SIZE)
                    if (n == -1L) break
                    if (n == 0L) continue
                    val chunk = buffer.readByteArray()
                    hasher.update(chunk)
                    pipe.send(chunk)
                    sent += chunk.size
                    if (sent > size) throw WormholeException("The file is larger than announced")
                    throttle.report(sent)
                }
                if (sent != size) throw WormholeException("The file is smaller than announced ($sent of $size bytes)")
                throttle.finish(sent)

                val ack = parse(pipe.receive())
                if (ack.stringValue("ack") != "ok") throw TransitException("The receiver did not confirm the file: $ack")
                val remoteHash = ack.stringValue("sha256")
                if (remoteHash != null && remoteHash != hasher.digest().toHexString()) {
                    throw TransitException("The receiver got different data (SHA-256 mismatch)")
                }
            } finally {
                pipe.close()
            }
        } finally {
            transit.close()
        }
    }

    suspend fun receive(
        session: WormholeSession,
        network: TransitNetwork,
        relay: DirectHint?,
        senderHints: TransitHints?,
        size: Long,
        sink: RawSink,
        onProgress: suspend (Long) -> Unit,
    ) {
        val transit = Transit(Transit.Role.RECEIVER, session.deriveKey(TRANSIT_KEY_PURPOSE), network, relay)
        try {
            session.send(buildJsonObject { put("transit", transit.start()) })
            session.send(buildJsonObject { put("answer", buildJsonObject { put("file_ack", "ok") }) })

            val pipe = transit.connect(senderHints ?: TransitHints(emptyList(), emptyList()))
            try {
                val hasher = SHA256()
                val buffer = Buffer()
                val throttle = ProgressThrottle(onProgress)
                var received = 0L
                while (received < size) {
                    val record = pipe.receive()
                    if (received + record.size > size) throw TransitException("The sender sent more data than announced")
                    hasher.update(record)
                    buffer.write(record)
                    sink.write(buffer, buffer.size)
                    received += record.size
                    throttle.report(received)
                }
                sink.flush()
                throttle.finish(received)
                val ack = buildJsonObject {
                    put("ack", "ok")
                    put("sha256", hasher.digest().toHexString())
                }
                pipe.send(ack.toString().encodeToByteArray())
            } finally {
                pipe.close()
            }
        } finally {
            transit.close()
        }
    }

    private fun parse(bytes: ByteArray): JsonObject = try {
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    } catch (e: SerializationException) {
        throw WormholeProtocolException("Malformed transit message", e)
    } catch (e: IllegalArgumentException) {
        throw WormholeProtocolException("Malformed transit message", e)
    }

    /** Reports progress at most every [PROGRESS_INTERVAL], plus once at the end. */
    private class ProgressThrottle(private val onProgress: suspend (Long) -> Unit) {
        private var last = TimeSource.Monotonic.markNow()
        private var first = true

        suspend fun report(bytes: Long) {
            if (first || last.elapsedNow() >= PROGRESS_INTERVAL) {
                first = false
                last = TimeSource.Monotonic.markNow()
                onProgress(bytes)
            }
        }

        suspend fun finish(bytes: Long) = onProgress(bytes)
    }
}
