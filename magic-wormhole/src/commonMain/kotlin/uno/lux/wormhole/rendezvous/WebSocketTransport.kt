package uno.lux.wormhole.rendezvous

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce

/** Creates an [HttpClient] with the WebSockets plugin and the platform's engine. */
internal expect fun createWebSocketHttpClient(): HttpClient

/** [RendezvousTransport] over a Ktor WebSocket. Each connection owns its own [HttpClient]. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal object WebSocketTransport : RendezvousTransport {
    override suspend fun connect(url: String): RendezvousConnection {
        val client = createWebSocketHttpClient()
        val session =
            try {
                client.webSocketSession(url)
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        return object : RendezvousConnection {
            override val incoming: ReceiveChannel<String> =
                session.produce {
                    for (frame in session.incoming) {
                        if (frame is Frame.Text) send(frame.readText())
                    }
                }

            override suspend fun send(text: String) = session.send(Frame.Text(text))

            override suspend fun close() {
                try {
                    session.close()
                } finally {
                    client.close()
                }
            }
        }
    }
}
