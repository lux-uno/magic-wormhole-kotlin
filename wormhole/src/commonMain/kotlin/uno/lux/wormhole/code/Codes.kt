package uno.lux.wormhole.code

import uno.lux.wormhole.InvalidCodeException
import uno.lux.wormhole.crypto.randomBytes

internal object Codes {
    private val codePattern = Regex("""^(\d+)-[^\s-]\S*$""")

    /** Chooses [count] words, alternating odd and even PGP lists (like magic-wormhole). */
    fun generateWords(count: Int, random: (Int) -> ByteArray = ::randomBytes): String {
        require(count >= 1) { "A code needs at least one word" }
        val bytes = random(count)
        return (0 until count).joinToString("-") { i ->
            val index = bytes[i].toInt() and 0xff
            if (i % 2 == 0) PgpWords.odd[index] else PgpWords.even[index]
        }
    }

    fun build(nameplate: String, wordCount: Int, random: (Int) -> ByteArray = ::randomBytes): String =
        "$nameplate-${generateWords(wordCount, random)}"

    /** Returns the nameplate of [code], or throws [InvalidCodeException]. */
    fun nameplateOf(code: String): String {
        val match = codePattern.find(code) ?: throw InvalidCodeException(code)
        return match.groupValues[1]
    }
}
