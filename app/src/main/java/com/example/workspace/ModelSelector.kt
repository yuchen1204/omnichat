package com.example.workspace

import android.util.Log
import com.example.data.AppRepository
import com.example.data.FetchedModel
import com.example.data.ModelConfig

/**
 * 模型选择器。
 *
 * 根据优先级为 Sub-Agent 选择最合适的模型：
 * 1. 显式指定的 modelConfigId/modelId（最高优先级）
 * 2. AgentPreset 中预设的模型配置
 * 3. modelHint 语义提示匹配（基于 FetchedModel 能力标记）
 * 4. Orchestrator 模型（兜底）
 */
class ModelSelector(private val repository: AppRepository) {

    companion object {
        private const val TAG = "ModelSelector"
    }

    data class ModelSelection(
        val modelConfigId: Long?,
        val modelId: String?,
        val reason: String
    )

    /**
     * 解析最终模型配置。
     */
    suspend fun resolve(
        presetModelConfigId: Long?,
        explicitConfigId: Long?,
        explicitModelId: String?,
        modelHint: ModelHint?,
        orchestratorConfig: ModelConfig
    ): ModelSelection {
        // 优先级 1：显式指定
        if (explicitConfigId != null) {
            return ModelSelection(explicitConfigId, explicitModelId, "显式指定")
        }

        // 优先级 2：Preset 配置
        if (presetModelConfigId != null) {
            return ModelSelection(presetModelConfigId, null, "Preset 配置")
        }

        // 优先级 3：Hint 匹配
        if (modelHint != null) {
            val hintResult = selectByHint(modelHint, orchestratorConfig)
            if (hintResult != null) {
                return hintResult
            }
        }

        // 优先级 4：兜底
        return ModelSelection(null, null, "使用 Orchestrator 默认模型")
    }

    private suspend fun selectByHint(hint: ModelHint, orchestratorConfig: ModelConfig): ModelSelection? {
        val allModels = repository.getAllFetchedModels()
        val allConfigs = repository.getAllConfigs()
        if (allModels.isEmpty()) return null

        return when (hint) {
            ModelHint.REASONING -> selectReasoning(allModels, orchestratorConfig)
            ModelHint.VISION -> selectVision(allModels, orchestratorConfig)
            ModelHint.FAST -> selectFast(allModels, orchestratorConfig)
            ModelHint.LARGE_CONTEXT -> selectLargeContext(allModels, orchestratorConfig)
            ModelHint.TOOLS -> selectTools(allModels, orchestratorConfig)
        }
    }

    private fun selectReasoning(models: List<FetchedModel>, orchestratorConfig: ModelConfig): ModelSelection? {
        val thinking = models.filter { it.hasThinking }
        if (thinking.isNotEmpty()) {
            val selected = preferSameProvider(thinking, orchestratorConfig.id)
            return ModelSelection(selected.providerId, selected.modelId, "推理模型 (thinking)")
        }
        // 回退：最大上下文
        val largest = models.maxByOrNull { parseContextSize(it.contextSize) }
        return largest?.let { ModelSelection(it.providerId, it.modelId, "推理模型 (回退: 最大上下文)") }
    }

    private fun selectVision(models: List<FetchedModel>, orchestratorConfig: ModelConfig): ModelSelection? {
        val vision = models.filter { it.hasVision }
        if (vision.isEmpty()) {
            Log.w(TAG, "No vision-capable models found")
            return null
        }
        val selected = preferSameProvider(vision, orchestratorConfig.id)
        return ModelSelection(selected.providerId, selected.modelId, "视觉模型")
    }

    private fun selectFast(models: List<FetchedModel>, orchestratorConfig: ModelConfig): ModelSelection? {
        // 优先选择名称含 mini/small/fast/light 的模型，其次选择上下文小的
        val sorted = models.sortedWith(compareBy(
            { !it.modelId.contains(Regex("(mini|small|fast|light)", RegexOption.IGNORE_CASE)) },
            { parseContextSize(it.contextSize) }
        ))
        val selected = preferSameProvider(sorted, orchestratorConfig.id)
        return ModelSelection(selected.providerId, selected.modelId, "快速模型")
    }

    private fun selectLargeContext(models: List<FetchedModel>, orchestratorConfig: ModelConfig): ModelSelection? {
        val sorted = models.sortedByDescending { parseContextSize(it.contextSize) }
        val largest = sorted.firstOrNull() ?: return null

        // 只在比 Orchestrator 默认模型上下文大 1.5 倍以上时才推荐
        val orchestratorModel = models.find { it.providerId == orchestratorConfig.id && it.modelId == orchestratorConfig.selectedModelId }
        val orchCtx = parseContextSize(orchestratorModel?.contextSize ?: "")
        if (orchCtx > 0 && parseContextSize(largest.contextSize) < orchCtx * 1.5) {
            return null // Orchestrator 的模型已经够大
        }

        return ModelSelection(largest.providerId, largest.modelId, "大上下文模型 (${largest.contextSize})")
    }

    private fun selectTools(models: List<FetchedModel>, orchestratorConfig: ModelConfig): ModelSelection? {
        val toolModels = models.filter { it.hasToolUse }
        if (toolModels.isEmpty()) {
            Log.w(TAG, "No explicit tool-use models found, using default")
            return null
        }
        val selected = preferSameProvider(toolModels, orchestratorConfig.id)
        return ModelSelection(selected.providerId, selected.modelId, "工具调用模型")
    }

    /**
     * 在候选模型中优先选择与 Orchestrator 相同 provider 的模型。
     */
    private fun preferSameProvider(candidates: List<FetchedModel>, orchestratorProviderId: Long): FetchedModel {
        return candidates.firstOrNull { it.providerId == orchestratorProviderId } ?: candidates.first()
    }

    /**
     * 解析上下文大小字符串为数值（单位：千 token）。
     * 支持 "128k", "128K", "128000" 等格式。
     */
    internal fun parseContextSize(sizeStr: String): Int {
        if (sizeStr.isBlank()) return 0
        val normalized = sizeStr.trim().lowercase()
        val numericPart = normalized.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        return when {
            normalized.contains("k") -> numericPart
            normalized.contains("m") -> numericPart * 1000
            numericPart > 1000 -> numericPart / 1000
            else -> numericPart
        }
    }
}
