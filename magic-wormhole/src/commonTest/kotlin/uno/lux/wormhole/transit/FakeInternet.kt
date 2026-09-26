package uno.lux.wormhole.transit

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** An in-memory "internet" with TCP-like sockets and a transit relay, for tests. */
internal class FakeInternet(
    private val scope: CoroutineScope,
) {
    private val listeners = mutableMapOf<String, FakeListener>()
    private var nextPort = 40000
    private val waitingForPeer = mutableMapOf<String, Pair<String, FakeSocket>>()

    /** Every host:port that somebody tried to connect to. */
    val connectAttempts = mutableListOf<String>()

    fun network(
        addresses: List<String>,
        canListen: Boolean = true,
    ): TransitNetwork =
        object : TransitNetwork {
            override suspend fun connect(
                host: String,
                port: Int,
            ): TransitSocket {
                connectAttempts += "$host:$port"
                if (host == RELAY_HOST && port == RELAY_PORT) return connectToRelay()
                val listener =
                    listeners["$host:$port"] ?: throw IllegalStateException("Connection refused: $host:$port")
                val (client, server) = socketPair()
                listener.pending.send(server)
                return client
            }

            override suspend fun listen(): TransitListener? {
                if (!canListen) return null
                val port = nextPort++
                val listener = FakeListener(port)
                addresses.forEach { listeners["$it:$port"] = listener }
                return listener
            }

            override fun localAddresses(): List<String> = addresses
        }

    private fun connectToRelay(): TransitSocket {
        val (client, relaySide) = socketPair()
        scope.launch {
            val line = StringBuilder()
            while (true) {
                val b =
                    relaySide.input
                        .readByte()
                        .toInt()
                        .toChar()
                if (b == '\n') break
                line.append(b)
            }
            // "please relay TOKEN for side SIDE"
            val parts = line.split(" ")
            val token = parts[2]
            val side = parts[5]
            val other = waitingForPeer[token]
            if (other == null || other.first == side) {
                waitingForPeer[token] = side to relaySide
                return@launch
            }
            waitingForPeer.remove(token)
            val peer = other.second
            relaySide.output.writeFully("ok\n".encodeToByteArray())
            relaySide.output.flush()
            peer.output.writeFully("ok\n".encodeToByteArray())
            peer.output.flush()
            launch { pipe(relaySide.input, peer.output) }
            launch { pipe(peer.input, relaySide.output) }
        }
        return client
    }

    private class FakeListener(
        override val port: Int,
    ) : TransitListener {
        val pending = Channel<TransitSocket>(Channel.UNLIMITED)

        override suspend fun accept(): TransitSocket = pending.receive()

        override fun close() {
            pending.close()
        }
    }

    class FakeSocket(
        override val input: ByteReadChannel,
        override val output: ByteWriteChannel,
    ) : TransitSocket {
        override fun close() {
            input.cancel()
            output.close(null)
        }
    }

    private fun socketPair(): Pair<FakeSocket, FakeSocket> {
        val aToB = ByteChannel(autoFlush = true)
        val bToA = ByteChannel(autoFlush = true)
        return FakeSocket(bToA, aToB) to FakeSocket(aToB, bToA)
    }

    companion object {
        const val RELAY_HOST = "relay.test"
        const val RELAY_PORT = 4001
        val RELAY = DirectHint(RELAY_HOST, RELAY_PORT)
    }
}

/** Copies until either side closes, like a relay forwarding TCP traffic. */
private suspend fun pipe(
    from: ByteReadChannel,
    to: ByteWriteChannel,
) {
    try {
        from.copyTo(to)
        to.flush()
        to.close(null)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
    }
}
