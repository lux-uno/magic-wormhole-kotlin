package uno.lux.wormhole

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Talks to the real `wormhole` CLI (magic-wormhole) through the public relay.
 * Runs only with WORMHOLE_INTEROP=1.
 */
class InteropTest {
    @BeforeTest
    fun onlyWhenRequested() {
        assumeTrue("Set WORMHOLE_INTEROP=1 to run interop tests", System.getenv("WORMHOLE_INTEROP") == "1")
    }

    private fun cli(vararg args: String): Process =
        ProcessBuilder(listOf("wormhole") + args).redirectErrorStream(false).start()

    @Test
    fun kotlinSendsTextToTheCli() = runBlocking {
        withTimeout(60_000) {
            val code = CompletableDeferred<String>()
            val sender = async {
                Wormhole().sendText("hello from kotlin").collect {
                    if (it is SendEvent.CodeAllocated) code.complete(it.code)
                }
            }
            val output = withContext(Dispatchers.IO) {
                val p = cli("receive", "--hide-progress", code.await())
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                p.waitFor(30, TimeUnit.SECONDS)
                assertEquals(0, p.exitValue(), err)
                out
            }
            sender.await()
            assertEquals("hello from kotlin", output.trim())
        }
    }

    @Test
    fun cliSendsTextToKotlin() = runBlocking {
        withTimeout(60_000) {
            val p = withContext(Dispatchers.IO) { cli("send", "--hide-progress", "--text", "hello from python") }
            val code = withContext(Dispatchers.IO) {
                val err = p.errorStream.bufferedReader()
                generateSequence { err.readLine() }
                    .first { it.startsWith("Wormhole code is:") }
                    .substringAfter(":").trim()
            }
            val events = Wormhole().receive(code).toList()
            assertEquals(listOf<ReceiveEvent>(ReceiveEvent.TextReceived("hello from python")), events)
            withContext(Dispatchers.IO) { p.waitFor(30, TimeUnit.SECONDS) }
            assertEquals(0, p.exitValue())
        }
    }

    @Test
    fun kotlinToKotlinOverThePublicRelay() = runBlocking {
        withTimeout(60_000) {
            val code = CompletableDeferred<String>()
            val sender = async {
                Wormhole().sendText("round trip").collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
            }
            val events = Wormhole().receive(code.await()).toList()
            sender.await()
            assertEquals(listOf<ReceiveEvent>(ReceiveEvent.TextReceived("round trip")), events)
        }
    }
}
