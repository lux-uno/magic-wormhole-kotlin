package uno.lux.wormhole.rendezvous

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessagesTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun clientMessagesSerializeToTheServerProtocol() {
        val cases =
            mapOf(
                ClientMessage.Bind("appid", "side1", listOf("kotlin", "0.1")) to
                    """{"type":"bind","appid":"appid","side":"side1","client_version":["kotlin","0.1"],"id":"0001"}""",
                ClientMessage.Allocate to """{"type":"allocate","id":"0001"}""",
                ClientMessage.Claim("4") to """{"type":"claim","nameplate":"4","id":"0001"}""",
                ClientMessage.Release("4") to """{"type":"release","nameplate":"4","id":"0001"}""",
                ClientMessage.Open("mb1") to """{"type":"open","mailbox":"mb1","id":"0001"}""",
                ClientMessage.Add("pake", byteArrayOf(0x7b, 0x7d)) to
                    """{"type":"add","phase":"pake","body":"7b7d","id":"0001"}""",
                ClientMessage.Close(
                    "mb1",
                    "happy",
                ) to """{"type":"close","mailbox":"mb1","mood":"happy","id":"0001"}""",
                ClientMessage.Ping(3) to """{"type":"ping","ping":3,"id":"0001"}""",
            )
        for ((message, expected) in cases) {
            assertEquals(json(expected), json(message.toJson("0001")), message.toString())
        }
    }

    @Test
    fun clientMessagesRoundTrip() {
        val add = ClientMessage.parse(ClientMessage.Add("0", byteArrayOf(1, 2, 3)).toJson("ab"))
        assertIs<ClientMessage.Add>(add)
        assertEquals("0", add.phase)
        assertContentEquals(byteArrayOf(1, 2, 3), add.body)
        assertEquals(ClientMessage.Claim("12"), ClientMessage.parse(ClientMessage.Claim("12").toJson("ab")))
    }

    @Test
    fun parsesWelcome() {
        val m = ServerMessage.parse("""{"type":"welcome","welcome":{"motd":"hi"},"server_tx":1.5}""")
        assertEquals(ServerMessage.Welcome(motd = "hi", error = null), m)
        val e = ServerMessage.parse("""{"type":"welcome","welcome":{"error":"go away"}}""")
        assertEquals(ServerMessage.Welcome(motd = null, error = "go away"), e)
    }

    @Test
    fun parsesSimpleReplies() {
        assertEquals(ServerMessage.Allocated("7"), ServerMessage.parse("""{"type":"allocated","nameplate":"7"}"""))
        assertEquals(ServerMessage.Claimed("abc"), ServerMessage.parse("""{"type":"claimed","mailbox":"abc"}"""))
        assertEquals(ServerMessage.Released, ServerMessage.parse("""{"type":"released"}"""))
        assertEquals(ServerMessage.Closed, ServerMessage.parse("""{"type":"closed"}"""))
        assertEquals(ServerMessage.Ack, ServerMessage.parse("""{"type":"ack","id":"12ab","server_tx":2.0}"""))
        assertEquals(ServerMessage.Pong(4), ServerMessage.parse("""{"type":"pong","pong":4}"""))
    }

    @Test
    fun parsesMailboxMessage() {
        val m =
            ServerMessage.parse(
                """{"type":"message","side":"s1","phase":"pake","body":"7b7d","id":"77","server_rx":1.0,"server_tx":1.1}""",
            )
        assertIs<ServerMessage.Message>(m)
        assertEquals("s1", m.side)
        assertEquals("pake", m.phase)
        assertContentEquals("{}".encodeToByteArray(), m.body)
    }

    @Test
    fun parsesError() {
        val m = ServerMessage.parse("""{"type":"error","error":"crowded","orig":{"type":"claim"}}""")
        assertEquals(ServerMessage.Error("crowded"), m)
    }

    @Test
    fun unknownTypesAreKept() {
        assertEquals(ServerMessage.Unknown("future"), ServerMessage.parse("""{"type":"future","x":1}"""))
    }
}
