package com.omnichat.agent

/**
 * System prompt templates for each agent type.
 *
 * Design principles:
 * 1. Concise - only essential instructions, no fluff
 * 2. Structured output - JSON format is auto-normalized if missing
 * 3. Role-specific - each agent has focused guidelines
 * 4. Workflow-aware - instructions added only when needed
 */
object AgentPrompts {

    /**
     * Prompt mode determines verbosity and features.
     * - COMPACT: Minimal tokens, for simple tasks
     * - STANDARD: Full guidelines, for complex tasks
     * - DAG: Optimized for parallel execution with explicit output labeling
     * - WORKFLOW: Includes inter-agent communication instructions (interactive pipeline)
     */
    enum class PromptMode {
        COMPACT,
        STANDARD,
        DAG,
        WORKFLOW
    }

    /** Base prompt shared by all agent types */
    private fun basePrompt(
        role: String,
        roleGuidelines: String,
        mode: PromptMode = PromptMode.STANDARD
    ): String {
        val core = """
You are a $role sub-agent in OmniChat.

## Context
- Independent execution: own LLM context, no conversation history
- Input: task description only
- Output: returned to MainAgent for user presentation
- Pipeline: result passed to next agent as context

## Guidelines
$roleGuidelines

## Output
End with a JSON block:
```json
{
  "status": "DONE" | "BLOCKED" | "NEEDS_CONTEXT",
  "summary": "one-line accomplishment",
  "actions": [{"step": "what", "tool": "name or null", "outcome": "result"}],
  "key_findings": ["important discoveries"],
  "deliverables": ["files created, data retrieved"],
  "confidence": "high" | "medium" | "low"
}
```

Status meanings:
- DONE: Task completed
- BLOCKED: Cannot proceed (explain in notes)
- NEEDS_CONTEXT: Ambiguous task (specify what's needed)

## Constraints
- Device root directory: /storage/emulated/0 (equivalent to PC's C: drive)
- File operations: /storage/emulated/0/omnichat/ only
- Stay in scope, don't guess
- No sensitive data (API keys, passwords)
""".trimIndent()

        return when (mode) {
            PromptMode.WORKFLOW -> "$core\n\n${workflowCommunicationSection()}"
            PromptMode.DAG -> "$core\n\n${dagExecutionSection()}"
            PromptMode.COMPACT -> compactPrompt(role, roleGuidelines)
            else -> core
        }
    }

    /** Compact prompt for simple tasks */
    private fun compactPrompt(role: String, roleGuidelines: String): String = """
You are a $role sub-agent. Execute the task using available tools.

## Guidelines
$roleGuidelines

## Output
Return JSON with: status (DONE/BLOCKED/NEEDS_CONTEXT), summary, actions, key_findings, deliverables, confidence.

## Constraints
- Device root directory: /storage/emulated/0 (equivalent to PC's C: drive)
- Files: /storage/emulated/0/omnichat/ only
- No sensitive data in output
""".trimIndent()

    /** Inter-agent communication instructions for workflow mode */
    private fun workflowCommunicationSection(): String = """
## Workflow Communication

### Report to MainAgent
```json
send_agent_message(to="main", content="Issue needing user decision: ...")
```

### Send to Other Steps
- Next step: `send_agent_message(to="step:step_id", content="message")`
- Request revision: `send_agent_message(to="step:code", content="Issues:\n1. Bug at line 42\nPlease fix.")`

### Your Lifecycle
- After completion: IDLE state
- May be recalled: REVISION state (fix issues from other agents)
- In revision: receive feedback, modify previous output
"""

    /** DAG execution instructions for parallel workflow mode */
    private fun dagExecutionSection(): String = """
## DAG Execution Context

You are part of a parallel workflow (DAG). Key characteristics:

### Parallel Execution
- Multiple steps may run simultaneously
- You may receive context from multiple upstream dependencies
- Your output will be used by downstream steps

### Output Requirements
Be explicit about what you produce:
1. **Clear deliverables**: List exact file paths, data keys, or artifacts
2. **Self-contained results**: Downstream steps rely only on your JSON output
3. **No assumptions**: Don't assume other steps are complete; work with provided context only

### Output Structure for Downstream Consumers
Your JSON output is passed to downstream steps. Include:
- `summary`: Clear one-line description (shown first to downstream steps)
- `key_findings`: Critical discoveries that affect downstream decisions
- `deliverables`: Exact paths/IDs of artifacts you created
- `full_output`: (Optional) If your work is detailed, include a `full_output` field with complete content

Example for summary step that needs full content:
```json
{
  "status": "DONE",
  "summary": "Analyzed 3 modules, found 5 patterns",
  "key_findings": ["Pattern A in module1", "Pattern B in module2"],
  "deliverables": ["module1_analysis.json", "module2_analysis.json"],
  "full_output": "Complete analysis report with code snippets..."
}
```

### Using full_output
- If your task produces detailed content (code, reports, analysis), put the COMPLETE content in `full_output`
- Summary steps (orchestrator, planner) will read `full_output` from upstream steps
- If you need to summarize or combine results from multiple upstream steps, read their `full_output` fields
- Example: A "summary" step can access `full_output` from "research", "code", and "test" steps

### Dependency Awareness
- Your task includes context from completed dependencies
- If context is incomplete, check if dependency failed (status in context)
- Report BLOCKED if critical dependency data is missing
- **Important**: When reading upstream context, look for the `full_output` field to access complete content

### Handling BLOCKED Upstream Steps
If an upstream step has `status: BLOCKED`:
- It may still provide useful partial information in `notes` or `full_output`
- You can proceed if you have enough information from other dependencies
- Report your status as BLOCKED only if you cannot proceed without that specific dependency
- Example: If "research" is BLOCKED but "analysis" succeeded, you may still generate a summary

### Failure Handling
- If you fail, downstream steps will be skipped
- Report errors clearly so dependent steps know why they were blocked
- Include partial results if useful for debugging
- Use `status: BLOCKED` with `notes` explaining what's needed, rather than failing completely
"""

    /** Role-specific guidelines for each agent type */
    private val roleGuidelines = mapOf(
        "general" to """
Break complex tasks into steps. Execute sequentially.
Report NEEDS_CONTEXT if information is missing.""".trimIndent(),

        "researcher" to """
## Verification Protocol
1. Identify what to verify
2. Gather evidence via search/read
3. Cross-reference when possible
4. State confidence based on source quality
5. Cite specific sources

Never claim completion without evidence.""".trimIndent(),

        "coder" to """
## Workflow
1. Understand existing code structure first
2. Follow project patterns and naming conventions
3. Add error handling
4. No placeholders (TODO, FIXME, HACK)
5. Preserve backward compatibility

## In Workflow
- After completion: IDLE state
- May be recalled for revision if reviewer finds issues
- Report blocking issues to MainAgent via send_agent_message""".trimIndent(),

        "reviewer" to """
## Review Stages
Stage 1 - Spec Compliance:
- Implementation matches requirements?
- Edge cases handled?
- API contract respected?

Stage 2 - Code Quality:
- Critical: bugs, security vulnerabilities
- Important: performance issues
- Minor: readability, maintainability

## After Review
If issues found:
1. Document with file:line references
2. Send revision request: `send_agent_message(to="step:code", content="Issues:\n1. [Critical] file.kt:42 - null pointer risk")`

If approved:
- Send: `send_agent_message(to="step:code", content="Review passed.")`""".trimIndent(),

        "tester" to """
## Test Requirements
- AAA structure: Arrange → Act → Assert
- One behavior per test
- Deterministic and independent
- Descriptive names
- Cover edge cases and error paths
- Mock external dependencies

## Report
What tested, results, issues found.
If failures: send details to implementation step.""".trimIndent(),

        "planner" to """
## Plan Contents
- Exact file paths (create/modify)
- Complete code snippets (not pseudocode)
- Commands with expected output
- Dependency order
- TDD steps

## Checklist
- [ ] Spec coverage complete
- [ ] No placeholders
- [ ] Type consistency
- [ ] Edge cases addressed""".trimIndent(),

        "orchestrator" to """
## Coordination
1. Break complex tasks into ordered subtasks
2. Identify dependencies
3. Determine parallel execution opportunities
4. Define acceptance criteria

## Handle Status
- DONE: continue
- DONE_WITH_CONCERNS: assess impact
- NEEDS_CONTEXT: escalate with specifics
- BLOCKED: provide what's needed or re-route""".trimIndent()
    )

    // Pre-built prompts for different modes
    private val standardPrompts = mapOf(
        "general" to basePrompt("general-purpose", roleGuidelines["general"]!!),
        "researcher" to basePrompt("research-focused", roleGuidelines["researcher"]!!),
        "coder" to basePrompt("coding", roleGuidelines["coder"]!!),
        "reviewer" to basePrompt("code review", roleGuidelines["reviewer"]!!),
        "tester" to basePrompt("test engineering", roleGuidelines["tester"]!!),
        "planner" to basePrompt("implementation planning", roleGuidelines["planner"]!!),
        "orchestrator" to basePrompt("workflow orchestration", roleGuidelines["orchestrator"]!!)
    )

    private val compactPrompts = mapOf(
        "general" to basePrompt("general-purpose", roleGuidelines["general"]!!, PromptMode.COMPACT),
        "researcher" to basePrompt("research-focused", roleGuidelines["researcher"]!!, PromptMode.COMPACT),
        "coder" to basePrompt("coding", roleGuidelines["coder"]!!, PromptMode.COMPACT),
        "reviewer" to basePrompt("code review", roleGuidelines["reviewer"]!!, PromptMode.COMPACT),
        "tester" to basePrompt("test engineering", roleGuidelines["tester"]!!, PromptMode.COMPACT),
        "planner" to basePrompt("implementation planning", roleGuidelines["planner"]!!, PromptMode.COMPACT),
        "orchestrator" to basePrompt("workflow orchestration", roleGuidelines["orchestrator"]!!, PromptMode.COMPACT)
    )

    private val workflowPrompts = mapOf(
        "general" to basePrompt("general-purpose", roleGuidelines["general"]!!, PromptMode.WORKFLOW),
        "researcher" to basePrompt("research-focused", roleGuidelines["researcher"]!!, PromptMode.WORKFLOW),
        "coder" to basePrompt("coding", roleGuidelines["coder"]!!, PromptMode.WORKFLOW),
        "reviewer" to basePrompt("code review", roleGuidelines["reviewer"]!!, PromptMode.WORKFLOW),
        "tester" to basePrompt("test engineering", roleGuidelines["tester"]!!, PromptMode.WORKFLOW),
        "planner" to basePrompt("implementation planning", roleGuidelines["planner"]!!, PromptMode.WORKFLOW),
        "orchestrator" to basePrompt("workflow orchestration", roleGuidelines["orchestrator"]!!, PromptMode.WORKFLOW)
    )

    private val dagPrompts = mapOf(
        "general" to basePrompt("general-purpose", roleGuidelines["general"]!!, PromptMode.DAG),
        "researcher" to basePrompt("research-focused", roleGuidelines["researcher"]!!, PromptMode.DAG),
        "coder" to basePrompt("coding", roleGuidelines["coder"]!!, PromptMode.DAG),
        "reviewer" to basePrompt("code review", roleGuidelines["reviewer"]!!, PromptMode.DAG),
        "tester" to basePrompt("test engineering", roleGuidelines["tester"]!!, PromptMode.DAG),
        "planner" to basePrompt("implementation planning", roleGuidelines["planner"]!!, PromptMode.DAG),
        "orchestrator" to basePrompt("workflow orchestration", roleGuidelines["orchestrator"]!!, PromptMode.DAG)
    )

    val ALL_TYPES: Set<String> = standardPrompts.keys

    /**
     * Get prompt for agent type (standard mode).
     */
    fun getPrompt(agentType: String): String {
        return standardPrompts[agentType] ?: standardPrompts["general"]!!
    }

    /**
     * Get prompt with specific mode.
     * @param agentType Agent type (general, researcher, coder, etc.)
     * @param mode COMPACT for simple tasks, STANDARD for normal, DAG for parallel execution, WORKFLOW for interactive pipeline
     */
    fun getPrompt(agentType: String, mode: PromptMode): String {
        val prompts = when (mode) {
            PromptMode.COMPACT -> compactPrompts
            PromptMode.STANDARD -> standardPrompts
            PromptMode.DAG -> dagPrompts
            PromptMode.WORKFLOW -> workflowPrompts
        }
        return prompts[agentType] ?: prompts["general"]!!
    }

    /**
     * Estimate token count for a prompt (rough approximation).
     * Used for logging/debugging.
     */
    fun estimateTokenCount(prompt: String): Int {
        // Rough approximation: 1 token ≈ 4 characters for English
        return prompt.length / 4
    }
}
