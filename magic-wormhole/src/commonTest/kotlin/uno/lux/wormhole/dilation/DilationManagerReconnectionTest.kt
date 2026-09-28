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
import kotlin.time.Duration.Companion.seconds

class DilationManagerReconnectionTest {
    private val appId = DEFAULT_APP_ID

    /** See [DilationManagerMultiplexingTest] for why the readers must live in [backgroundScope]. */
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

        val leader = DilationManager(sa, side = "ffffffffffffffff", pingInterval = 10_000.seconds)
        val follower = DilationManager(sb, side = "0000000000000000", pingInterval = 10_000.seconds)
        val leaderNetwork = internet.network(listOf("10.0.0.1"))
        val followerNetwork = internet.network(listOf("10.0.0.2"))

        coroutineScope {
            val ka = async { sa.exchangeKeys("1-purple-sausages") }
            sb.exchangeKeys("1-purple-sausages")
            ka.await()

            val startLeader = async { leader.start() }
            follower.start()
            startLeader.await()

            val connectLeader = async { leader.connect(leaderNetwork, relay = null, scope = backgroundScope) }
            follower.connect(followerNetwork, relay = null, scope = backgroundScope)
            connectLeader.await()
        }

        return leader to follower
    }

    private suspend fun waitUntil(
        maxAttempts: Int = 10_000,
        condition: () -> Boolean,
    ) {
        var attempts = 0
        while (!condition() && attempts < maxAttempts) {
            yield()
            attempts++
        }
        assertTrue(condition(), "Condition was not met in time")
    }

    /**
     * Waits for both sides to reconnect. Compares against generation numbers captured *before* the
     * drop, since [DilationManager.state] can already read `CONNECTED` right after a drop that
     * hasn't been noticed yet (nothing has failed a read/write on the stale connection so far).
     */
    private suspend fun waitUntilBothReconnect(
        leader: DilationManager,
        leaderGenerationBeforeDrop: Int,
        follower: DilationManager,
        followerGenerationBeforeDrop: Int,
    ) = waitUntil {
        leader.generationForTests() > leaderGenerationBeforeDrop &&
            follower.generationForTests() > followerGenerationBeforeDrop &&
            leader.state == DilationManagerState.CONNECTED &&
            follower.state == DilationManagerState.CONNECTED
    }

    @Test
    fun droppingTheConnectionTriggersAnAutomaticReconnect() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val (leader, follower) = connectedPair(internet)
            val leaderGen = leader.generationForTests()
            val followerGen = follower.generationForTests()

            leader.dropConnectionForTests()
            waitUntilBothReconnect(leader, leaderGen, follower, followerGen)

            // The reconnected pair is fully usable: a fresh subchannel opens and delivers data.
            val scid = leader.openSubchannel("after-reconnect")
            val opened = follower.events.receive()
            assertIs<DilationInboundEvent.Opened>(opened)
            assertEquals(scid, opened.scid)

            leader.sendData(scid, "still works".encodeToByteArray())
            val data = follower.events.receive()
            assertIs<DilationInboundEvent.DataReceived>(data)
            assertEquals("still works", data.payload.decodeToString())
        }

    @Test
    fun theFollowerCanAlsoNoticeTheDropAndReconnects() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val (leader, follower) = connectedPair(internet)
            val leaderGen = leader.generationForTests()
            val followerGen = follower.generationForTests()

            follower.dropConnectionForTests()
            waitUntilBothReconnect(leader, leaderGen, follower, followerGen)

            val scid = follower.openSubchannel("from-follower")
            val opened = leader.events.receive()
            assertIs<DilationInboundEvent.Opened>(opened)
            assertEquals(scid, opened.scid)
        }

    @Test
    fun subchannelIdsKeepIncreasingAcrossAReconnect() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val (leader, follower) = connectedPair(internet)

            val before = leader.openSubchannel("before")
            follower.events.receive()

            val leaderGen = leader.generationForTests()
            val followerGen = follower.generationForTests()
            leader.dropConnectionForTests()
            waitUntilBothReconnect(leader, leaderGen, follower, followerGen)

            val after = leader.openSubchannel("after")
            follower.events.receive()
            assertEquals(before + 2, after)
        }

    @Test
    fun anUnackedRecordIsResentAfterReconnecting() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val (leader, follower) = connectedPair(internet)

            val scid = leader.openSubchannel("p")
            follower.events.receive() // Opened
            waitUntil { leader.outboundQueueSizeForTests() == 0 } // Open fully acked before the real test begins

            val leaderGen = leader.generationForTests()
            val followerGen = follower.generationForTests()
            leader.sendData(scid, "important".encodeToByteArray())
            leader.dropConnectionForTests() // no suspension point let the peer ack it first
            assertTrue(leader.outboundQueueSizeForTests() >= 1, "expected the DATA record to still be unacked")

            waitUntilBothReconnect(leader, leaderGen, follower, followerGen)

            val resent = follower.events.receive()
            assertIs<DilationInboundEvent.DataReceived>(resent)
            assertEquals("important", resent.payload.decodeToString())
            waitUntil { leader.outboundQueueSizeForTests() == 0 }
        }
}
