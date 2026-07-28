package com.omnichat.skill

import android.content.Context
import android.net.Uri
import com.omnichat.data.AppDatabase
import com.omnichat.data.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Skill 管理器。
 *
 * 业务逻辑层，负责：
 * - 初始化：从数据库加载 Skill 到 Registry
 * - 安装内置 Skill
 * - 安装/卸载用户 Skill
 * - 匹配用户输入
 * - 启用/禁用 Skill
 */
class SkillManager(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val skillDao = database.skillDao()

    /**
     * 初始化：加载所有 Skill 到内存注册表，安装内置 Skill。
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            // 安装内置 Skill（如果尚未安装）
            installBuiltinSkills()

            // 从数据库加载所有 Skill 到注册表
            val allSkills = skillDao.getAllSkills()
            SkillRegistry.clear()
            SkillRegistry.registerAll(allSkills)
        }
    }

    /**
     * 安装内置 Skill。
     * 如果数据库中已存在同 skillId 的条目，则更新；否则插入。
     */
    private suspend fun installBuiltinSkills() {
        val builtins = BuiltinSkills.getAll()
        for (builtin in builtins) {
            val existing = skillDao.getBySkillId(builtin.skillId)
            if (existing != null) {
                // 更新内置 Skill 的提示词等（保留用户的自定义启用/禁用状态）
                skillDao.update(
                    existing.copy(
                        name = builtin.name,
                        description = builtin.description,
                        version = builtin.version,
                        author = builtin.author,
                        triggerPatterns = builtin.triggerPatterns,
                        systemPrompt = builtin.systemPrompt,
                        requiredToolGroups = builtin.requiredToolGroups,
                        workflowTemplateId = builtin.workflowTemplateId,
                        isBuiltin = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                skillDao.insert(builtin)
            }
        }
    }

    /**
     * 从 .skill.md 文件内容安装 Skill。
     */
    suspend fun installFromContent(content: String): Result<SkillEntity> {
        return withContext(Dispatchers.IO) {
            val result = SkillInstaller.installFromContent(content)
            result.fold(
                onSuccess = { skill ->
                    // 检查是否已存在同 skillId 的 Skill
                    val existing = skillDao.getBySkillId(skill.skillId)
                    if (existing != null) {
                        // 更新已有 Skill
                        skillDao.update(skill.copy(id = existing.id, isBuiltin = existing.isBuiltin))
                        SkillRegistry.update(skill.copy(id = existing.id, isBuiltin = existing.isBuiltin))
                    } else {
                        val id = skillDao.insert(skill)
                        val saved = skill.copy(id = id)
                        SkillRegistry.register(saved)
                        Result.success(saved)
                    }
                    Result.success(skill)
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * 从 Uri 安装 Skill。
     * 自动识别 .skill.md 单文件和 .zip 压缩包。
     * 返回安装的 Skill 列表。
     */
    suspend fun installFromUri(uri: Uri): Result<List<SkillEntity>> {
        return withContext(Dispatchers.IO) {
            val result = SkillInstaller.installFromUri(context, uri)
            result.fold(
                onSuccess = { skills ->
                    val installed = mutableListOf<SkillEntity>()
                    for (skill in skills) {
                        val existing = skillDao.getBySkillId(skill.skillId)
                        if (existing != null) {
                            skillDao.update(skill.copy(id = existing.id, isBuiltin = existing.isBuiltin))
                            SkillRegistry.update(skill.copy(id = existing.id, isBuiltin = existing.isBuiltin))
                            installed.add(skill.copy(id = existing.id))
                        } else {
                            val id = skillDao.insert(skill)
                            val saved = skill.copy(id = id)
                            SkillRegistry.register(saved)
                            installed.add(saved)
                        }
                    }
                    Result.success(installed)
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * 启用/禁用 Skill。
     */
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            skillDao.setEnabled(id, enabled)
            // 同步更新 Registry
            val skill = skillDao.getById(id) ?: return@withContext
            if (enabled) {
                SkillRegistry.register(skill)
            } else {
                SkillRegistry.update(skill)
            }
        }
    }

    /**
     * 删除 Skill（内置 Skill 不可删除）。
     */
    suspend fun delete(id: Long): Boolean {
        return withContext(Dispatchers.IO) {
            val skill = skillDao.getById(id) ?: return@withContext false
            if (skill.isBuiltin) return@withContext false
            skillDao.delete(skill)
            SkillRegistry.remove(skill.skillId)
            true
        }
    }

    /**
     * 根据用户输入匹配已启用的 Skill。
     */
    fun matchByIntent(userMessage: String): List<SkillEntity> {
        return SkillMatcher.matchByIntent(userMessage, SkillRegistry.getEnabled())
    }

    /**
     * 获取所有 Skill（Flow，用于 UI 响应式更新）。
     */
    fun getAllSkillsFlow() = skillDao.getAllSkillsFlow()

    /**
     * 刷新注册表（从数据库重新加载）。
     */
    suspend fun refreshRegistry() {
        withContext(Dispatchers.IO) {
            val allSkills = skillDao.getAllSkills()
            SkillRegistry.clear()
            SkillRegistry.registerAll(allSkills)
        }
    }
}