package uno.lux.wormhole.dilation

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import uno.lux.wormhole.DEFAULT_APP_ID
import uno.lux.wormhole.WormholeSession
import uno.lux.wormhole.rendezvous.FakeMailboxServer
import uno.lux.wormhole.rendezvous.RendezvousClient
import uno.lux.wormhole.transit.FakeInternet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DilationManagerMultiplexingTest {
    private val appId = DEFAULT_APP_ID

    /**
     * The managers' background readers are launched in [TestScope.backgroundScope] (not the test's
     * main scope), so `runTest` cancels them automatically when the test body finishes instead of
     * waiting for them to complete on their own, which they never do.
     */
    private suspend fun TestScope.connectedPair(internet: FakeInternet): Pair<DilationManager, DilationManager> {
        val server = FakeMailboxServer()
        val a = RendezvousClient.connect(server.transport, "ws://fake", appId, backgroundScope)
        val b = RendezvousClient.connect(server.transport, "ws://fake", appId, backgroundScope)
        a.bind()
        b.bind()
        val nameplate = a.allocate()
        a.open(a.claim(nameplate))
        b.open(b.claim(nameplate))
        val sa = WormholeSession(a, appId)
        val sb = WormholeSession(b, appId)

        val leader = DilationManager(sa, side = "ffffffffffffffff")
        val follower = DilationManager(sb, side = "0000000000000000")
        val leaderNetwork = internet.network(listOf("10.0.0.1"))
        val followerNetwork = internet.network(listOf("10.0.0.2"))

        coroutineScope {
            val ka = async { sa.exchangeKeys("1-purple-sausages") }
            sb.exchangeKeys("1-purple-sausages")
            ka.await()

            val startLeader = async { leader.start() }
            follower.start()
            startLeader.await()

            val connectLeader =
                async { leader.connect(leaderNetwork, relay = null, scope = backgroundScope) }
            follower.connect(followerNetwork, relay = null, scope = backgroundScope)
            connectLeader.await()
        }

        return leader to follower
    }

    @Test
    fun openDataAndCloseArriveInOrderAndAreAcked() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val (leader, follower) = connectedPair(internet)

            val scid = leader.openSubchannel("test-protocol")
            leader.sendData(scid, "hello".encodeToByteArray())
            leader.sendData(scid, "world".encodeToByteArray())
            leader.closeSubchannel(scid)

            val opened = follower.events.receive()
            assertIs<DilationInboundEvent.Opened>(opened)
            assertEquals(scid, opened.scid)
            assertEquals("test-protocol", opened.subprotocol)

            val first = follower.events.receive()
            assertIs<DilationInboundEvent.DataReceived>(first)
            assertEquals("hello", first.payload.decodeToString())

            val second = follower.events.receive()
            assertIs<DilationInboundEvent.DataReceived>(second)
            assertEquals("world", second.payload.decodeToString())

            val closed = follower.events.receive()
            assertIs<DilationInboundEvent.Closed>(closed)
            assertEquals(scid, closed.scid)
        }

    @Test
    fun leaderUsesOddSubchannelIdsAndFollowerUsesEven() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val (leader, follower) = connectedPair(internet)

            val leaderScid1 = leader.openSubchannel("a")
            val leaderScid2 = leader.openSubchannel("b")
            val followerScid1 = follower.openSubchannel("c")

            assertEquals(1, leaderScid1)
            assertEquals(3, leaderScid2)
            assertEquals(2, followerScid1)
        }

    @Test
    fun outboundRecordsAreRetiredOnceAcked() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val (leader, follower) = connectedPair(internet)

            leader.sendData(leader.openSubchannel("p"), "x".encodeToByteArray())
            // Drain the events so the follower's ACKs (sent from its read loop) have run.
            follower.events.receive() // Opened
            follower.events.receive() // DataReceived

            waitUntil { leader.outboundQueueSizeForTests() == 0 }
        }

    @Test
    fun theInboundWatermarkAdvancesAsRecordsArrive() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val (leader, follower) = connectedPair(internet)

            val scid = leader.openSubchannel("p") // seqnum 0
            follower.events.receive()
            waitUntil { follower.highestInboundAckedForTests() == 0 }

            leader.sendData(scid, "first".encodeToByteArray()) // seqnum 1
            follower.events.receive()
            waitUntil { follower.highestInboundAckedForTests() == 1 }
        }

    private suspend fun waitUntil(condition: () -> Boolean) {
        var attempts = 0
        while (!condition() && attempts < 1000) {
            yield()
            attempts++
        }
        assertTrue(condition(), "Condition was not met in time")
    }
}
