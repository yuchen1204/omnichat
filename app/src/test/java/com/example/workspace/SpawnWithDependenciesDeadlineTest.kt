package com.example.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Structural unit test for spawnWithDependencies global deadline fix (Fix #2).
 *
 * Uses static source-code verification to confirm that `globalDeadline` is
 * initialized once before the outer loop, and that the per-batch `batchDeadline`
 * variable no longer exists in the method body.
 *
 * Validates: Requirements REQ-2.1, REQ-2.2
 */
class SpawnWithDependenciesDeadlineTest {

    /**
     * Locates TeamManager.kt relative to the project root.
     *
     * The test runner's working directory is the module root (`app/`), so we
     * walk up one level to reach the project root and then descend into the
     * known source path.
     */
    private fun findTeamManagerSource(): File {
        // Try common working-directory roots: module root (app/) or project root
        val candidates = listOf(
            File("src/main/java/com/example/workspace/TeamManager.kt"),          // cwd = app/
            File("app/src/main/java/com/example/workspace/TeamManager.kt"),      // cwd = project root
            File("../app/src/main/java/com/example/workspace/TeamManager.kt"),   // cwd = one level up
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "Cannot locate TeamManager.kt. Tried:\n" +
                    candidates.joinToString("\n") { "  ${it.absolutePath}" }
            )
    }

    /**
     * Extracts the body of `spawnWithDependencies` from the source text.
     *
     * Strategy: find the line containing `fun spawnWithDependencies(`, then
     * collect lines until the brace depth returns to zero (i.e., the function
     * closing brace is reached).
     */
    private fun extractSpawnWithDependenciesBody(source: String): String {
        val lines = source.lines()

        // Find the function declaration line
        val startIndex = lines.indexOfFirst { it.contains("fun spawnWithDependencies(") }
        check(startIndex >= 0) { "Could not find 'fun spawnWithDependencies(' in TeamManager.kt" }

        val bodyLines = mutableListOf<String>()
        var depth = 0
        var foundOpenBrace = false

        for (i in startIndex until lines.size) {
            val line = lines[i]
            bodyLines.add(line)

            for (ch in line) {
                when (ch) {
                    '{' -> { depth++; foundOpenBrace = true }
                    '}' -> depth--
                }
            }

            // Once we've seen the opening brace and depth returns to 0, the function ends
            if (foundOpenBrace && depth == 0) break
        }

        return bodyLines.joinToString("\n")
    }

    /**
     * Verifies that `globalDeadline` is declared before `while (allAgents.isNotEmpty())`
     * and that `batchDeadline` does not appear anywhere in the function body.
     *
     * This is a structural/static test that confirms Fix #2 is correctly in place:
     * the deadline is computed once at method entry (shared across all batches) rather
     * than being reset on every outer-loop iteration.
     *
     * Validates: REQ-2.1, REQ-2.2
     */
    @Test
    fun testGlobalDeadlineSharedAcrossBatches() {
        val sourceFile = findTeamManagerSource()
        val source = sourceFile.readText()

        // Extract just the spawnWithDependencies function body for targeted assertions
        val functionBody = extractSpawnWithDependenciesBody(source)

        // ── Assertion 1: globalDeadline must exist in the function ──────────────
        assertTrue(
            "Expected 'globalDeadline' to be declared in spawnWithDependencies, but it was not found.\n" +
                "Fix #2 requires a single global deadline computed before the outer loop.",
            functionBody.contains("globalDeadline")
        )

        // ── Assertion 2: globalDeadline must appear BEFORE the outer while loop ─
        val globalDeadlineIndex = functionBody.indexOf("globalDeadline")
        val whileLoopIndex = functionBody.indexOf("while (allAgents.isNotEmpty())")

        assertTrue(
            "Expected 'while (allAgents.isNotEmpty())' to be present in spawnWithDependencies.",
            whileLoopIndex >= 0
        )

        assertTrue(
            "Expected 'globalDeadline' to be initialized BEFORE 'while (allAgents.isNotEmpty())', " +
                "but globalDeadline appears at index $globalDeadlineIndex and the while loop at $whileLoopIndex.\n" +
                "Fix #2 requires the deadline to be set once at method entry, not per-batch.",
            globalDeadlineIndex < whileLoopIndex
        )

        // ── Assertion 3: batchDeadline must NOT appear in the function body ─────
        assertFalse(
            "Expected 'batchDeadline' to be absent from spawnWithDependencies, but it was found.\n" +
                "Fix #2 removes the per-batch deadline variable; only 'globalDeadline' should exist.",
            functionBody.contains("batchDeadline")
        )
    }
}
