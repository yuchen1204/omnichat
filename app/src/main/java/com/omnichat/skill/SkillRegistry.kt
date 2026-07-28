package com.omnichat.skill

import com.omnichat.data.SkillEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Skill 内存注册表。
 *
 * 管理所有已加载的 Skill，支持注册、查找、匹配。
 * 类似 [com.omnichat.tool.ToolRegistry] 的设计模式，但面向 Skill。
 */
object SkillRegistry {

    private val skills = ConcurrentHashMap<String, SkillEntity>()  // skillId -> Skill

    /** 注册单个 Skill */
    fun register(skill: SkillEntity) {
        skills[skill.skillId] = skill
    }

    /** 批量注册 */
    fun registerAll(skills: List<SkillEntity>) {
        skills.forEach { register(it) }
    }

    /** 按 skillId 查找 */
    fun get(skillId: String): SkillEntity? = skills[skillId]

    /** 获取所有 Skill */
    fun getAll(): List<SkillEntity> = skills.values.toList()

    /** 获取所有已启用的 Skill */
    fun getEnabled(): List<SkillEntity> = skills.values.filter { it.isEnabled }

    /** 移除指定 Skill */
    fun remove(skillId: String) {
        skills.remove(skillId)
    }

    /** 清除所有 */
    fun clear() {
        skills.clear()
    }

    /** 更新指定 Skill */
    fun update(skill: SkillEntity) {
        skills[skill.skillId] = skill
    }

    /** 获取数量 */
    fun size(): Int = skills.size
}