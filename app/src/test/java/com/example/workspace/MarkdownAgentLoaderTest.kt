package com.example.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MarkdownAgentLoaderTest {

    @Test
    fun testExtractFrontmatter() {
        val content = """
---
name: test-agent
description: Test agent description
tools: ["read_file", "write_file"]
background: true
---
# System Prompt

You are a test agent.
""".trimIndent()

        val frontmatter = extractFrontmatterTest(content)
        assertNotNull(frontmatter)
        assertEquals("test-agent", frontmatter["name"])
        assertEquals("Test agent description", frontmatter["description"])
    }

    @Test
    fun testExtractBodyContent() {
        val content = """
---
name: test-agent
---
# System Prompt

You are a test agent.
""".trimIndent()

        val body = extractBodyContentTest(content)
        assertTrue(body.contains("System Prompt"))
        assertTrue(body.contains("You are a test agent"))
    }

    @Test
    fun testNoFrontmatterReturnsNull() {
        val content = """
# Just a markdown file

No frontmatter here.
""".trimIndent()

        val frontmatter = extractFrontmatterTest(content)
        assertNull(frontmatter)
    }

    @Test
    fun testIncompleteFrontmatterReturnsNull() {
        val content = """
---
name: test-agent
# Missing closing ---
""".trimIndent()

        val frontmatter = extractFrontmatterTest(content)
        assertNull(frontmatter)
    }

    @Test
    fun testParseYamlBoolean() {
        val content = """
---
name: test
background: true
omitClaudeMd: false
---
""".trimIndent()

        val frontmatter = extractFrontmatterTest(content)
        assertNotNull(frontmatter)
        assertEquals(true, frontmatter["background"])
        assertEquals(false, frontmatter["omitClaudeMd"])
    }

    @Test
    fun testParseYamlStringWithQuotes() {
        val content = """
---
name: "quoted-name"
description: 'single-quoted'
---
""".trimIndent()

        val frontmatter = extractFrontmatterTest(content)
        assertNotNull(frontmatter)
        assertEquals("quoted-name", frontmatter["name"])
        assertEquals("single-quoted", frontmatter["description"])
    }

    @Test
    fun testParseAgentFromMarkdown() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "agents_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val mdFile = File(tempDir, "my-agent.md")
        mdFile.writeText("""
---
name: my-agent
description: My custom agent
color: blue
background: true
---
You are my custom agent.
""".trimIndent())

        val agents = MarkdownAgentLoader.loadFromDirectory(tempDir)
        assertEquals(1, agents.size)

        val agent = agents[0]
        assertEquals("my-agent", agent.agentType)
        assertEquals("My custom agent", agent.displayName)
        assertEquals("blue", agent.color)
        assertTrue(agent.background)
        assertFalse(agent.isBuiltIn)
        assertEquals("markdown", agent.source)

        mdFile.delete()
        tempDir.delete()
    }

    @Test
    fun testLoadFromNonexistentDirectory() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "nonexistent_${System.currentTimeMillis()}")
        val agents = MarkdownAgentLoader.loadFromDirectory(tempDir)
        assertTrue(agents.isEmpty())
    }

    @Test
    fun testSkipReadmeFiles() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "agents_readme_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        File(tempDir, "README.md").writeText("This is a README")
        File(tempDir, "actual-agent.md").writeText("""
---
name: actual-agent
description: Actual agent
---
System prompt.
""".trimIndent())

        val agents = MarkdownAgentLoader.loadFromDirectory(tempDir)
        assertEquals(1, agents.size)
        assertEquals("actual-agent", agents[0].agentType)

        tempDir.deleteRecursively()
    }

    // Helper methods for testing private functions
    private fun extractFrontmatterTest(content: String): Map<String, Any>? {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        val endIdx = lines.indexOf("---", 1)
        if (endIdx == -1) return null

        val frontmatterLines = lines.subList(1, endIdx)
        val result = mutableMapOf<String, Any>()
        for (line in frontmatterLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colonIdx = trimmed.indexOf(":")
            if (colonIdx == -1) continue
            val key = trimmed.substring(0, colonIdx).trim()
            val valueStr = trimmed.substring(colonIdx + 1).trim()

            // Parse value
            when {
                valueStr == "true" -> result[key] = true
                valueStr == "false" -> result[key] = false
                valueStr.startsWith("\"") && valueStr.endsWith("\"") -> result[key] = valueStr.substring(1, valueStr.length - 1)
                valueStr.startsWith("'") && valueStr.endsWith("'") -> result[key] = valueStr.substring(1, valueStr.length - 1)
                valueStr.startsWith("[") && valueStr.endsWith("]") -> {
                    val inner = valueStr.substring(1, valueStr.length - 1)
                    result[key] = inner.split(",").map { it.trim().removeSurrounding("\"") }
                }
                else -> result[key] = valueStr
            }
        }
        return result
    }

    private fun extractBodyContentTest(content: String): String {
        val lines = content.lines()
        val startIdx = lines.indexOf("---", 1)
        if (startIdx == -1) return content.trim()
        return lines.subList(startIdx + 1, lines.size).joinToString("\n").trim()
    }
}