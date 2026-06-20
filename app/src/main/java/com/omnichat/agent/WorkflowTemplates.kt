package com.omnichat.agent

/**
 * Predefined workflow templates for common use cases.
 */
object WorkflowTemplates {

    data class Template(
        val id: String,
        val name: String,
        val description: String,
        val steps: List<TemplateStep>,
        val defaultTimeouts: TemplateTimeouts = TemplateTimeouts()
    )

    data class TemplateStep(
        val id: String,
        val agentType: String,
        val taskTemplate: String,  // Supports {{param}} placeholders
        val dependsOn: List<String> = emptyList()
    )

    data class TemplateTimeouts(
        val runningTimeoutMs: Long = 10 * 60 * 1000L,  // 10 minutes
        val idleTimeoutMs: Long = 30 * 60 * 1000L      // 30 minutes
    )

    // Built-in templates
    val RESEARCH_AND_REPORT = Template(
        id = "research_and_report",
        name = "研究并报告",
        description = "检索信息并生成报告",
        steps = listOf(
            TemplateStep("research", "researcher", "{{task}}"),
            TemplateStep("report", "general", "根据研究结果生成报告")
        )
    )

    val CODE_AND_REVIEW = Template(
        id = "code_and_review",
        name = "编码并审查",
        description = "编写代码并进行审查",
        steps = listOf(
            TemplateStep("code", "coder", "{{task}}"),
            TemplateStep("review", "reviewer", "审查代码并提供反馈")
        )
    )

    val FULL_DEV_CYCLE = Template(
        id = "full_dev_cycle",
        name = "完整开发周期",
        description = "研究→编码→审查的完整流程",
        steps = listOf(
            TemplateStep("research", "researcher", "研究需求相关信息：{{task}}"),
            TemplateStep("code", "coder", "根据研究结果实现功能"),
            TemplateStep("review", "reviewer", "审查代码质量，如发现问题请发送消息给 code 步骤")
        )
    )

    val ALL_TEMPLATES = listOf(RESEARCH_AND_REPORT, CODE_AND_REVIEW, FULL_DEV_CYCLE)

    /**
     * Instantiate a template with parameters.
     */
    fun instantiateTemplate(
        templateId: String,
        params: Map<String, String>
    ): List<WorkflowStep>? {
        val template = ALL_TEMPLATES.find { it.id == templateId } ?: return null

        return template.steps.map { ts ->
            val task = replaceParams(ts.taskTemplate, params)
            WorkflowStep(
                id = ts.id,
                agentType = ts.agentType,
                task = task,
                dependsOn = ts.dependsOn,
                timeoutMs = template.defaultTimeouts.runningTimeoutMs,
                maxIdleMs = template.defaultTimeouts.idleTimeoutMs
            )
        }
    }

    private fun replaceParams(template: String, params: Map<String, String>): String {
        var result = template
        params.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
