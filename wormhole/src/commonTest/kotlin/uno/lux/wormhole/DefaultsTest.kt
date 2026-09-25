package uno.lux.wormhole

import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultsTest {
    @Test
    fun defaultsMatchTheReferenceImplementations() {
        assertEquals("ws://relay.magic-wormhole.io:4000/v1", DEFAULT_RENDEZVOUS_URL)
        assertEquals("tcp:transit.magic-wormhole.io:4001", DEFAULT_TRANSIT_RELAY)
        assertEquals("lothar.com/wormhole/text-or-file-xfer", DEFAULT_APP_ID)
    }
}
