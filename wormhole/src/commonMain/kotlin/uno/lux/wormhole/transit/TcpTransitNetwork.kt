package uno.lux.wormhole.transit

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineDispatcher

/** Dispatcher for blocking socket work on this platform. */
internal expect val transitDispatcher: CoroutineDispatcher

/** Non-loopback IP addresses of this device, for direct hints. Empty when unknown. */
internal expect fun localIpAddresses(): List<String>

/** Whether this platform should listen for direct connections. */
internal expect val canListenForDirectConnections: Boolean

/** [TransitNetwork] over real TCP sockets (ktor-network). Call [close] when the transfer ends. */
internal class TcpTransitNetwork : TransitNetwork {
    private val selector = SelectorManager(transitDispatcher)

    override suspend fun connect(host: String, port: Int): TransitSocket =
        TcpSocket(aSocket(selector).tcp().connect(host, port))

    override suspend fun listen(): TransitListener? {
        if (!canListenForDirectConnections) return null
        val server = aSocket(selector).tcp().bind("0.0.0.0", 0)
        return TcpListener(server)
    }

    override fun localAddresses(): List<String> = localIpAddresses()

    fun close() = selector.close()

    private class TcpSocket(private val socket: Socket) : TransitSocket {
        override val input: ByteReadChannel = socket.openReadChannel()
        override val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = false)
        override fun close() = socket.close()
    }

    private class TcpListener(private val server: ServerSocket) : TransitListener {
        override val port: Int = (server.localAddress as InetSocketAddress).port
        override suspend fun accept(): TransitSocket = TcpSocket(server.accept())
        override fun close() = server.close()
    }
}
