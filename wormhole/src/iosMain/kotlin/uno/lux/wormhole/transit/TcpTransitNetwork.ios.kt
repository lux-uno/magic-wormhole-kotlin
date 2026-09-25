package uno.lux.wormhole.transit

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual val transitDispatcher: CoroutineDispatcher = Dispatchers.IO

// iOS: we do not advertise direct hints yet (listening needs local-network permission and
// interface enumeration). Outgoing direct connections and the relay still work.
internal actual val canListenForDirectConnections: Boolean = false

internal actual fun localIpAddresses(): List<String> = emptyList()
