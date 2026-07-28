package com.omnichat.skill

import com.omnichat.data.SkillEntity
import org.json.JSONArray

/**
 * 内置 Skill 定义。
 *
 * 这些 Skill 在应用启动时自动安装，不可删除。
 */
object BuiltinSkills {

    /**
     * 获取所有内置 Skill 的定义。
     */
    fun getAll(): List<SkillEntity> = listOf(
        weeklyReportSkill(),
        deepResearchSkill(),
        uiAdjustSkill()
    )

    /**
     * 📝 周报生成器
     * 自动收集本周工作记录，生成结构化周报。
     */
    private fun weeklyReportSkill() = SkillEntity(
        skillId = "weekly-report",
        name = "周报生成器",
        description = "自动收集本周工作记录，生成结构化周报",
        version = "1.0.0",
        author = "omnichat",
        triggerPatterns = JSONArray(
            listOf("周报", "weekly report", "写周报", "工作总结", "周报生成", "本周工作")
        ).toString(),
        systemPrompt = """你是一个周报生成助手，擅长收集本周工作记录并生成结构化周报。

## 工作流程
1. 首先使用 `search_memory` 搜索本周相关的记忆和工作记录
2. 根据搜索结果，整理出本周完成的主要工作
3. 生成结构化的周报，包含以下部分：
   - 📋 **本周工作摘要**：概述本周主要工作内容
   - ✅ **完成事项**：列出已完成的各项任务
   - 🔄 **进行中**：列出仍在进行中的工作
   - 📌 **下周计划**：列出下周的工作安排
   - 💡 **备注/问题**：其他需要记录的内容

## 格式要求
- 使用 Markdown 格式输出
- 条理清晰，便于阅读
- 如果找不到相关记忆，请如实告知用户并建议用户手动输入""",
        requiredToolGroups = JSONArray(listOf("memory")).toString(),
        isBuiltin = true
    )

    /**
     * 🔍 深度研究
     * 使用 SubAgent 进行多步搜索和研究。
     */
    private fun deepResearchSkill() = SkillEntity(
        skillId = "deep-research",
        name = "深度研究",
        description = "使用 SubAgent 进行多步搜索和研究，适用于复杂调研任务",
        version = "1.0.0",
        author = "omnichat",
        triggerPatterns = JSONArray(
            listOf("研究", "调研", "分析", "调查", "research", "investigate", "深度研究")
        ).toString(),
        systemPrompt = """你是一个深度研究助手，擅长将复杂的研究任务分解为多个子任务并使用 SubAgent 执行。

## 工作流程
1. **分析研究需求**：理解用户的研究目标，明确研究范围和深度
2. **分解研究任务**：将大问题拆分为多个可独立研究的子问题
3. **使用 `delegate_task` 工具**：为每个子问题创建 SubAgent 任务
   - 使用 `agentType: "researcher"` 进行研究型任务
   - 为每个子任务提供清晰的指令和背景信息
4. **汇总研究结果**：收集所有 SubAgent 的输出，整理成综合报告
5. **输出格式**：使用 Markdown 输出结构化研究结果

## 提示
- 对于复杂的研究课题，可以并行派发多个 SubAgent 任务
- 使用 `check_task_status` 跟踪任务进度
- 如果某个子任务的结果需要进一步深入，可以继续向下派发""",
        requiredToolGroups = JSONArray(listOf("subagent", "memory")).toString(),
        isBuiltin = true
    )

    /**
     * 🎨 UI 调整
     * 调整应用界面外观、主题和样式。
     */
    private fun uiAdjustSkill() = SkillEntity(
        skillId = "ui-adjust",
        name = "UI 调整",
        description = "调整应用界面外观、主题和样式，包括颜色、字体、布局等",
        version = "1.0.0",
        author = "omnichat",
        triggerPatterns = JSONArray(
            listOf("改界面", "换主题", "改颜色", "调样式", "改字体", "UI", "主题", "皮肤", "美化")
        ).toString(),
        systemPrompt = """你是一个 UI 调整助手，擅长根据用户的需求调整应用界面外观。

## 可用工具
- `get_ui_capabilities`：获取当前 UI 可调整的配置项
- `adjust_ui`：调整具体的 UI 参数（颜色、字体、布局等）
- `color_scheme`：保存/应用/删除配色方案预设
- `reset_ui_to_default`：重置所有 UI 设置为默认值

## 工作流程
1. 先使用 `get_ui_capabilities` 了解当前可调整的配置项
2. 根据用户的需求，使用 `adjust_ui` 进行调整
3. 如果用户想保存配色方案，使用 `color_scheme(action="save")`
4. 如果用户想应用已有方案，使用 `color_scheme(action="apply")`
5. 调整后告知用户具体改了什么，以及如何恢复

## 注意
- 每次调整一次即可，不要多次重复调整
- 调整前可以先获取当前状态，了解已有配置""",
        requiredToolGroups = JSONArray(listOf("ui_appearance")).toString(),
        isBuiltin = true
    )
}