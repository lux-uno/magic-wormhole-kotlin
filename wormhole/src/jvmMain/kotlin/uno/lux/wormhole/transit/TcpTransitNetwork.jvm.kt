package uno.lux.wormhole.transit

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.net.Inet4Address
import java.net.NetworkInterface

internal actual val transitDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual val canListenForDirectConnections: Boolean = true

internal actual fun localIpAddresses(): List<String> =
    try {
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { it.hostAddress }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }
