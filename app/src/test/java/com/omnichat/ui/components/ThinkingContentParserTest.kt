package com.omnichat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingContentParserTest {
    @Test
    fun `plain content has no thinking`() {
        assertEquals(ParsedMessageContent(null, "hello", true), parseMessageContent("hello"))
    }

    @Test
    fun `think block is separated`() {
        assertEquals(ParsedMessageContent("step", "answer", true), parseMessageContent("<think>step</think>answer"))
    }

    @Test
    fun `other tags and case insensitive attributes are supported`() {
        val result = parseMessageContent("before <ANALYSIS foo=bar>why</analysis> after")
        assertEquals("why", result.thinking)
        assertEquals("before  after", result.mainBody)
    }

    @Test
    fun `multiple thinking blocks are combined and body is retained`() {
        val result = parseMessageContent("before<think>one</think>middle<reasoning>two</reasoning>after")
        assertEquals("one\ntwo", result.thinking)
        assertEquals("beforemiddleafter", result.mainBody)
    }

    @Test
    fun `prefix body before thinking is retained`() {
        val result = parseMessageContent("prefix<thinking>thought</thinking>body")
        assertEquals("thought", result.thinking)
        assertEquals("prefixbody", result.mainBody)
    }

    @Test
    fun `unfinished thinking is marked unfinished`() {
        val result = parseMessageContent("<think>still thinking")
        assertEquals("still thinking", result.thinking)
        assertEquals("", result.mainBody)
        assertFalse(result.isThinkingFinished)
    }

    @Test
    fun `unfinished tag fragment is not treated as body`() {
        val result = parseMessageContent("answer <thi")
        assertEquals("answer", result.mainBody)
        assertEquals(null, result.thinking)
        assertFalse(result.isThinkingFinished)
    }

    @Test
    fun `unfinished closing tag fragment is not treated as thinking`() {
        val result = parseMessageContent("<think>thought</thi")
        assertEquals("thought", result.thinking)
        assertEquals("", result.mainBody)
        assertFalse(result.isThinkingFinished)
    }
}
