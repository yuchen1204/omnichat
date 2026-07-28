package com.omnichat.skill

import com.omnichat.data.SkillEntity

/**
 * Skill 意图匹配引擎。
 *
 * 根据用户输入匹配启用的 Skill。当前使用关键词匹配，
 * 后续可升级为语义匹配（基于 embedding 的意图分类）。
 */
object SkillMatcher {

    /**
     * 根据用户输入匹配启用的 Skill。
     *
     * @param userMessage 用户输入的文本
     * @param enabledSkills 已启用的 Skill 列表
     * @return 匹配到的 Skill 列表，按匹配度降序排列
     */
    fun matchByIntent(userMessage: String, enabledSkills: List<SkillEntity>): List<SkillEntity> {
        if (userMessage.isBlank()) return emptyList()

        val message = userMessage.lowercase()

        // 为每个 Skill 计算匹配得分
        val scored = enabledSkills.mapNotNull { skill ->
            val patterns = skill.getTriggerPatternList()
            if (patterns.isEmpty()) return@mapNotNull null

            val matchCount = patterns.count { pattern ->
                pattern.isNotBlank() && message.contains(pattern.lowercase())
            }

            if (matchCount > 0) {
                // 按匹配数降序，匹配数相同则按更新时间降序
                skill to matchCount
            } else {
                null
            }
        }

        return scored
            .sortedWith(compareByDescending<Pair<SkillEntity, Int>> { it.second }
                .thenByDescending { it.first.updatedAt })
            .map { it.first }
    }
}