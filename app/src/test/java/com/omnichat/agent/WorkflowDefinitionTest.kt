package com.omnichat.agent

import com.omnichat.tool.builtin.RunWorkflowTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDefinitionTest {

    @Test
    fun `pipeline supplies prior result when dependency omitted`() {
        val steps = listOf(
            WorkflowStep(id = "research", agentType = "researcher", task = "Research"),
            WorkflowStep(id = "report", agentType = "writer", task = "Report")
        )

        assertNull(WorkflowEngine.validateSteps(steps, "pipeline"))
        val task = WorkflowEngine.buildTaskWithContext(
            task = steps[1].task,
            dependsOn = listOf(steps[0].id),
            contextVariables = mapOf("research" to "research output"),
            steps = steps
        )

        assert(task.contains("research output"))
    }

    @Test
    fun `workflow validation rejects invalid dependency graphs before execution`() {
        val duplicate = listOf(
            WorkflowStep(id = "same", agentType = "general", task = "one"),
            WorkflowStep(id = "same", agentType = "general", task = "two")
        )
        val forwardDependency = listOf(
            WorkflowStep(id = "first", agentType = "general", task = "one", dependsOn = listOf("second")),
            WorkflowStep(id = "second", agentType = "general", task = "two")
        )

        assertEquals("Duplicate step id: 'same'", WorkflowEngine.validateSteps(duplicate, "dag"))
        assertEquals(
            "In pipeline mode, steps[0].dependsOn must only reference earlier steps: second",
            WorkflowEngine.validateSteps(forwardDependency, "pipeline")
        )
    }

    @Test
    fun `run workflow schema requires mode and each direct step core fields`() {
        val schema = RunWorkflowTool.inputSchema
        val rootRequired = schema.getJSONArray("required")
        val stepSchema = schema.getJSONObject("properties")
            .getJSONObject("steps")
            .getJSONObject("items")
        val stepRequired = stepSchema.getJSONArray("required")

        val requiredStepFields = (0 until stepRequired.length()).map { stepRequired.getString(it) }.toSet()

        assertTrue((0 until rootRequired.length()).any { rootRequired.getString(it) == "mode" })
        assertTrue(requiredStepFields.containsAll(setOf("id", "agentType", "task")))
    }

    @Test
    fun `run workflow accepts retry and result variable step options`() {
        val arguments = JSONObject(
            """
            {
              "mode": "pipeline",
              "steps": [
                {
                  "id": "first",
                  "agentType": "general",
                  "task": "Do work",
                  "resultVariable": "first_result",
                  "maxRetries": 2,
                  "wakeUpOnMessage": false
                }
              ]
            }
            """.trimIndent()
        )

        assertNull(RunWorkflowTool.validateInput(arguments))
    }
}
