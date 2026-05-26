package com.example.workspace

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Unit tests for deleteTeam self-join deadlock fix (Fix #3).
 *
 * Two complementary strategies are used:
 *
 * 1. **Structural / static tests** — parse TeamManager.kt source to confirm that
 *    `callerJob` is captured *before* `withContext(NonCancellable)` and that the
 *    null-safe branch (`if (callerJob == null)`) is present.  This mirrors the
 *    approach used in SpawnWithDependenciesDeadlineTest.
 *
 * 2. **Behavioral / logic tests** — exercise the self-join detection algorithm
 *    (parent-chain walk) in isolation using plain `kotlinx.coroutines.Job` objects.
 *    No Android dependencies are required, so these run on the JVM test runner.
 *
 * Validates: Requirements REQ-3.1, REQ-3.2, REQ-3.3, REQ-3.4
 */
class DeleteTeamSelfJoinTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — source-code location
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Locates TeamManager.kt relative to common test working-directory roots.
     */
    private fun findTeamManagerSource(): File {
        val candidates = listOf(
            File("src/main/java/com/example/workspace/TeamManager.kt"),        // cwd = app/
            File("app/src/main/java/com/example/workspace/TeamManager.kt"),    // cwd = project root
            File("../app/src/main/java/com/example/workspace/TeamManager.kt"), // cwd = one level up
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "Cannot locate TeamManager.kt. Tried:\n" +
                    candidates.joinToString("\n") { "  ${it.absolutePath}" }
            )
    }

    /**
     * Extracts the body of `deleteTeam()` from the source text.
     *
     * Strategy: find the line containing `suspend fun deleteTeam()`, then collect
     * lines until the brace depth returns to zero.
     */
    private fun extractDeleteTeamBody(source: String): String {
        val lines = source.lines()

        val startIndex = lines.indexOfFirst { it.contains("suspend fun deleteTeam()") }
        check(startIndex >= 0) { "Could not find 'suspend fun deleteTeam()' in TeamManager.kt" }

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

            if (foundOpenBrace && depth == 0) break
        }

        return bodyLines.joinToString("\n")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Structural tests (static source-code verification)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Test 1 (structural): `callerJob` must be captured BEFORE `withContext(NonCancellable)`.
     *
     * `withContext(NonCancellable)` replaces `coroutineContext[Job]` with the
     * NonCancellable sentinel.  Capturing callerJob inside the withContext block
     * would always yield null/NonCancellable, making self-join detection useless.
     * Fix #3 moves the capture to before the withContext call.
     *
     * Validates: REQ-3.1
     */
    @Test
    fun testCallerJobCapturedBeforeWithContextNonCancellable() {
        val source = findTeamManagerSource().readText()
        val body = extractDeleteTeamBody(source)

        // Both tokens must be present
        assertTrue(
            "Expected 'callerJob' to be declared in deleteTeam(), but it was not found.",
            body.contains("callerJob")
        )
        assertTrue(
            "Expected 'withContext(NonCancellable)' to be present in deleteTeam().",
            body.contains("withContext(NonCancellable)")
        )

        // callerJob declaration must appear before withContext(NonCancellable)
        val callerJobIndex = body.indexOf("val callerJob")
        val withContextIndex = body.indexOf("withContext(NonCancellable)")

        assertTrue(
            "Expected 'val callerJob' to appear BEFORE 'withContext(NonCancellable)' in deleteTeam().\n" +
                "callerJob index=$callerJobIndex, withContext index=$withContextIndex.\n" +
                "Fix #3 requires callerJob to be captured before entering the NonCancellable context.",
            callerJobIndex in 0 until withContextIndex
        )
    }

    /**
     * Test 2 (structural): null-safe branch for `callerJob == null` must be present.
     *
     * When deleteTeam() is called from a non-coroutine context (e.g. GlobalScope),
     * `coroutineContext[Job]` returns null.  The fix adds an explicit null check so
     * that isSelf defaults to false (no self-join risk) rather than crashing or
     * incorrectly walking a null parent chain.
     *
     * Validates: REQ-3.2, REQ-3.3
     */
    @Test
    fun testNullCallerJobBranchIsPresent() {
        val source = findTeamManagerSource().readText()
        val body = extractDeleteTeamBody(source)

        assertTrue(
            "Expected 'callerJob == null' null-check branch in deleteTeam(), but it was not found.\n" +
                "Fix #3 requires explicit handling of the null callerJob case to avoid self-join deadlock.",
            body.contains("callerJob == null")
        )

        // The null branch must set isSelf to false
        val nullBranchIndex = body.indexOf("callerJob == null")
        val falseAfterNull = body.indexOf("false", nullBranchIndex)
        assertTrue(
            "Expected 'false' to appear after 'callerJob == null' (null branch sets isSelf = false).",
            falseAfterNull > nullBranchIndex
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Behavioral / logic tests (pure coroutine Job, no Android deps)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Replicates the self-join detection algorithm from deleteTeam() in isolation.
     *
     * This is the exact logic from the fixed implementation:
     *   - callerJob == null  → isSelf = false  (no parent-chain walk)
     *   - callerJob != null  → walk parent chain; isSelf = true iff job found
     */
    private fun isSelfJoin(callerJob: Job?, targetJob: Job): Boolean {
        if (callerJob == null) return false
        var current: Job? = callerJob
        while (current != null) {
            if (current === targetJob) return true
            current = current.parent
        }
        return false
    }

    /**
     * Test 3 (behavioral): when callerJob is null, isSelf is always false.
     *
     * Covers the case where deleteTeam() is called from a non-coroutine context
     * (e.g. a plain thread or GlobalScope with no Job).  There is no self-join
     * risk, so all jobs should be joined normally.
     *
     * Validates: REQ-3.2, REQ-3.4
     */
    @Test
    fun testNullCallerJobIsNeverSelf() = runTest {
        // Create a real Job to act as the "teammate job"
        val teammateJob = launch { /* idle */ }
        try {
            // callerJob == null → isSelf must be false regardless of the target job
            assertFalse(
                "When callerJob is null, isSelf must be false (no self-join risk).",
                isSelfJoin(callerJob = null, targetJob = teammateJob)
            )
        } finally {
            teammateJob.cancel()
        }
    }

    /**
     * Test 4 (behavioral): when callerJob IS the target job, isSelf is true.
     *
     * Simulates the Orchestrator calling deleteTeam() from within its own coroutine.
     * The Orchestrator's Job is in the teammate jobs map, so the parent-chain walk
     * must detect the match and return true (skip join to avoid deadlock).
     *
     * Validates: REQ-3.3
     */
    @Test
    fun testCallerJobMatchesTargetIsSelf() = runTest {
        // The caller's own job is the coroutine context Job
        val callerJob = coroutineContext[Job]!!

        // Simulate: the Orchestrator's job is registered in teammateJobs
        val targetJob = callerJob  // same reference — direct self-join scenario

        assertTrue(
            "When callerJob === targetJob, isSelf must be true (skip join to avoid deadlock).",
            isSelfJoin(callerJob = callerJob, targetJob = targetJob)
        )
    }

    /**
     * Test 5 (behavioral): when callerJob is a child of the target job, isSelf is true.
     *
     * Simulates the case where deleteTeam() is called from a child coroutine launched
     * inside the Orchestrator's scope.  The parent-chain walk must traverse up to the
     * Orchestrator Job and detect the match.
     *
     * Validates: REQ-3.3
     */
    @Test
    fun testCallerJobIsChildOfTargetIsSelf() = runTest {
        // parentJob represents the Orchestrator's registered job
        lateinit var parentJob: Job
        parentJob = launch {
            // childJob represents the coroutine that calls deleteTeam()
            val childJob = launch {
                // From inside the child, callerJob = childJob, targetJob = parentJob
                val callerJob = coroutineContext[Job]!!
                assertTrue(
                    "When callerJob is a child of targetJob, isSelf must be true.",
                    isSelfJoin(callerJob = callerJob, targetJob = parentJob)
                )
            }
            childJob.join()
        }
        parentJob.join()
    }

    /**
     * Test 6 (behavioral): when callerJob is unrelated to the target job, isSelf is false.
     *
     * Simulates the case where deleteTeam() is called from an external scope
     * (e.g. WorkspaceViewModel's viewModelScope).  The caller's Job is not in the
     * parent chain of any teammate job, so isSelf must be false and all jobs are joined.
     *
     * Validates: REQ-3.4
     */
    @Test
    fun testUnrelatedCallerJobIsNotSelf() = runTest {
        // externalJob represents the viewModelScope coroutine calling deleteTeam()
        val externalJob = launch { /* external caller */ }
        // teammateJob represents an Orchestrator/Sub-Agent job in the team
        val teammateJob = launch { /* teammate */ }

        try {
            assertFalse(
                "When callerJob is unrelated to targetJob, isSelf must be false (join normally).",
                isSelfJoin(callerJob = externalJob, targetJob = teammateJob)
            )
        } finally {
            externalJob.cancel()
            teammateJob.cancel()
        }
    }

    /**
     * Test 7 (behavioral): deleteTeam from orchestrator coroutine completes without deadlock.
     *
     * Verifies the core property: the self-join detection algorithm, when applied to
     * a job that IS in the caller's parent chain, correctly returns true (skip join),
     * allowing the operation to complete within the timeout.
     *
     * This is the pure-logic equivalent of the integration test described in the design:
     * "deleteTeam from orchestrator coroutine completes without deadlock".
     *
     * Validates: REQ-3.3
     */
    @Test
    fun testDeleteTeamFromOrchestratorCoroutineDoesNotDeadlock() = runTest {
        withTimeout(5000L) {
            // Simulate: orchestratorJob is registered in teammateJobs
            lateinit var orchestratorJob: Job
            orchestratorJob = launch {
                // Inside the orchestrator coroutine, callerJob = this coroutine's Job
                val callerJob = coroutineContext[Job]!!

                // The self-join detection must identify orchestratorJob as "self"
                val isSelf = isSelfJoin(callerJob = callerJob, targetJob = orchestratorJob)

                // Because isSelf == true, we skip join() — no deadlock
                if (!isSelf) {
                    // This branch would cause a deadlock in the real deleteTeam()
                    // The test fails if we reach here
                    fail("isSelf should be true for the orchestrator's own job — join would deadlock")
                }
                // Reaching here means the algorithm correctly skips the self-join
            }
            orchestratorJob.join()
        }
        // If withTimeout completes without TimeoutCancellationException, no deadlock occurred
    }

    /**
     * Test 8 (behavioral): deleteTeam from external scope joins all jobs.
     *
     * Verifies that when the caller is external (not in any teammate's parent chain),
     * isSelf is false for all teammate jobs, meaning all jobs would be joined normally.
     *
     * Validates: REQ-3.4
     */
    @Test
    fun testDeleteTeamFromExternalScopeJoinsAllJobs() = runTest {
        withTimeout(5000L) {
            // Simulate: orchestrator + 1 sub-agent jobs registered in teammateJobs
            val orchestratorJob = launch { /* orchestrator work */ }
            val subAgentJob = launch { /* sub-agent work */ }

            // External caller (e.g. viewModelScope) — unrelated to both jobs
            val externalCallerJob = coroutineContext[Job]!!

            // For each teammate job, isSelf must be false → all jobs are joined
            val jobsToJoin = listOf(orchestratorJob, subAgentJob)
            val skippedJobs = mutableListOf<Job>()

            for (job in jobsToJoin) {
                val isSelf = isSelfJoin(callerJob = externalCallerJob, targetJob = job)
                if (isSelf) {
                    skippedJobs.add(job)
                } else {
                    // In the real deleteTeam(), this is where job.join() is called.
                    // The jobs are already completing (launched above), so join() returns quickly.
                    job.join()
                }
            }

            assertTrue(
                "External caller should not skip any jobs — all jobs must be joined. " +
                    "Skipped: ${skippedJobs.size}",
                skippedJobs.isEmpty()
            )

            // All jobs must be completed (isActive == false) after joining
            assertFalse("orchestratorJob should be completed", orchestratorJob.isActive)
            assertFalse("subAgentJob should be completed", subAgentJob.isActive)
        }
    }
}
