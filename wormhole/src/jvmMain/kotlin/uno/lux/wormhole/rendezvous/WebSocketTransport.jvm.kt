package uno.lux.wormhole.rendezvous

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun createWebSocketHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(WebSockets)
}
