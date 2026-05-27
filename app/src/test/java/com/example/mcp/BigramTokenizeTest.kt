package com.example.mcp

import org.junit.Assert.*
import org.junit.Test

class BigramTokenizeTest {

    /** Mirrors the private function in BuiltinToolHandler */
    private fun bigramTokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val cjkRange = '一'..'鿿'
        val buffer = StringBuilder()

        for (ch in text) {
            if (ch in cjkRange) {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
                tokens.add(ch.toString())
            } else if (ch.isWhitespace() || ch in "，。！？、；：""''（）【】《》,.!?;:\"'()[]<>") {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
            } else {
                buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) {
            tokens.add(buffer.toString().lowercase())
        }

        // Add character bigrams for CJK characters
        val cjkChars = text.filter { it in cjkRange }
        for (i in 0 until cjkChars.length - 1) {
            tokens.add("${cjkChars[i]}${cjkChars[i + 1]}")
        }

        return tokens
    }

    @Test
    fun `chinese text produces character bigrams`() {
        val tokens = bigramTokenize("用户习惯")
        assertTrue("用户" in tokens)
        assertTrue("有习" in tokens)
        assertTrue("习惯" in tokens)
        assertTrue(tokens.size >= 3)
    }

    @Test
    fun `english words kept whole`() {
        val tokens = bigramTokenize("I use Kotlin")
        assertTrue("kotlin" in tokens)
        assertTrue("use" in tokens)
        assertTrue("i" in tokens)
    }

    @Test
    fun `mixed chinese and english`() {
        val tokens = bigramTokenize("用户使用Kotlin编程")
        assertTrue("kotlin" in tokens)
        assertTrue("用户" in tokens)
        assertTrue("使用" in tokens)
    }

    @Test
    fun `empty string returns empty set`() {
        assertTrue(bigramTokenize("").isEmpty())
    }

    @Test
    fun `single chinese character has no bigram but has the char`() {
        val tokens = bigramTokenize("用")
        assertTrue("用" in tokens)
        assertEquals(1, tokens.size)
    }

    @Test
    fun `punctuation is treated as separator`() {
        val tokens = bigramTokenize("hello, world")
        assertTrue("hello" in tokens)
        assertTrue("world" in tokens)
    }

    @Test
    fun `jaccard with bigram overlap`() {
        val a = bigramTokenize("用户习惯使用Kotlin")
        val b = bigramTokenize("用户喜欢使用Java")
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        val jaccard = intersection.toDouble() / union.toDouble()
        assertTrue("Jaccard should be > 0", jaccard > 0.0)
        assertTrue("Jaccard should be < 1", jaccard < 1.0)
    }
}
