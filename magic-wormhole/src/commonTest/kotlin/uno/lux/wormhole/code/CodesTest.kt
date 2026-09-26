package uno.lux.wormhole.code

import uno.lux.wormhole.InvalidCodeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodesTest {
    @Test
    fun wordListsHave256UniqueWordsEach() {
        assertEquals(256, PgpWords.even.toSet().size)
        assertEquals(256, PgpWords.odd.toSet().size)
        assertEquals("aardvark", PgpWords.even[0])
        assertEquals("adroitness", PgpWords.odd[0])
        assertEquals("zulu", PgpWords.even[255])
        assertEquals("yucatan", PgpWords.odd[255])
    }

    @Test
    fun wordsAlternateStartingWithTheOddList() {
        val bytes = byteArrayOf(0, 1, 2)
        val words = Codes.generateWords(3) { bytes.copyOf(it) }
        assertEquals("adroitness-absurd-aftermath", words)
    }

    @Test
    fun buildJoinsNameplateAndWords() {
        val code = Codes.build("7", 2) { ByteArray(it) { 0xff.toByte() } }
        assertEquals("7-yucatan-zulu", code)
    }

    @Test
    fun randomCodesHaveTheRequestedLength() {
        val code = Codes.build("42", 4)
        val parts = code.split("-")
        assertEquals(5, parts.size)
        assertTrue(parts[1] in PgpWords.odd && parts[2] in PgpWords.even)
    }

    @Test
    fun nameplateIsTheNumericPrefix() {
        assertEquals("4", Codes.nameplateOf("4-purple-sausages"))
        assertEquals("123", Codes.nameplateOf("123-a"))
    }

    @Test
    fun invalidCodesAreRejected() {
        for (bad in listOf("", "purple-sausages", "4", "4-", "-4-purple", "4 purple", "4-purple sausages")) {
            assertFailsWith<InvalidCodeException>(bad) { Codes.nameplateOf(bad) }
        }
    }
}
