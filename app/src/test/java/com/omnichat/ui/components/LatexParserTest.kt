package com.omnichat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexParserTest {

    @Test
    fun `findBlocks returns empty list for plain text`() {
        assertTrue(LatexParser.findBlocks("Hello, world!").isEmpty())
        assertTrue(LatexParser.findBlocks("").isEmpty())
    }

    @Test
    fun `findBlocks detects inline math`() {
        val blocks = LatexParser.findBlocks("The value of \$\\pi\$ is 3.14")
        assertEquals(1, blocks.size)
        assertEquals("\\pi", blocks[0].expression)
        assertFalse(blocks[0].isDisplay)
    }

    @Test
    fun `findBlocks detects display math`() {
        val blocks = LatexParser.findBlocks("The equation: \$\$E = mc^2\$\$ is famous")
        assertEquals(1, blocks.size)
        assertEquals("E = mc^2", blocks[0].expression)
        assertTrue(blocks[0].isDisplay)
    }

    @Test
    fun `findBlocks detects multiple math blocks`() {
        val blocks = LatexParser.findBlocks("\$a\$ and \$b\$ and \$\$c\$\$")
        assertEquals(3, blocks.size)
        assertEquals("a", blocks[0].expression)
        assertFalse(blocks[0].isDisplay)
        assertEquals("b", blocks[1].expression)
        assertFalse(blocks[1].isDisplay)
        assertEquals("c", blocks[2].expression)
        assertTrue(blocks[2].isDisplay)
    }

    @Test
    fun `findBlocks skips escaped dollar signs`() {
        val blocks = LatexParser.findBlocks("Price is \\\$5.00, but \$x\$ is math")
        assertEquals(1, blocks.size)
        assertEquals("x", blocks[0].expression)
    }

    @Test
    fun `hasLatex detects both inline and display math`() {
        assertTrue(LatexParser.hasLatex("\$\$E = mc^2\$\$"))
        assertTrue(LatexParser.hasLatex("\$x\$"))
        assertFalse(LatexParser.hasLatex("Just text"))
        assertFalse(LatexParser.hasLatex("Unclosed \$dollar"))
    }

    @Test
    fun `hasIncompleteLatex detects unclosed delimiters`() {
        assertTrue(LatexParser.hasIncompleteLatex("The value of \$x"))
        assertFalse(LatexParser.hasIncompleteLatex("The value of \$x\$"))
        assertFalse(LatexParser.hasIncompleteLatex("No math here"))
        assertTrue(LatexParser.hasIncompleteLatex("\$\$E = mc^2"))
    }

    @Test
    fun `splitByLatex returns segments correctly`() {
        val segments: List<Any> = LatexParser.splitByLatex("Before \$x\$ after")
        assertEquals(3, segments.size)
        assertEquals("Before ", segments[0])
        assertTrue(segments[1] is LatexBlock)
        assertEquals("x", (segments[1] as LatexBlock).expression)
        assertEquals(" after", segments[2])
    }

    @Test
    fun `splitByLatex returns just text when no latex`() {
        val segments: List<Any> = LatexParser.splitByLatex("Just text")
        assertEquals(1, segments.size)
        assertEquals("Just text", segments[0])
    }

    @Test
    fun `findBlocks respects display math over inline`() {
        val blocks = LatexParser.findBlocks("\$\$E = mc^2\$\$")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].isDisplay)
    }
}