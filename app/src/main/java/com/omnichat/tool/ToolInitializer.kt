package com.omnichat.tool

import android.content.Context
import android.util.Log
import com.omnichat.tool.builtin.*
import com.omnichat.tool.builtin.ConsolidateMemoryTool

/**
 * 工具初始化器。
 *
 * 负责在应用启动时注册所有内置工具到 ToolRegistry。
 */
object ToolInitializer {
    private const val TAG = "ToolInitializer"

    @Volatile
    private var initialized = false

    /**
     * 注册所有内置工具。
     * 此方法应在应用启动时调用，仅执行一次。
     */
    fun initialize(context: Context) {
        if (initialized) {
            Log.w(TAG, "ToolInitializer already initialized, skipping")
            return
        }

        synchronized(this) {
            if (initialized) return

            Log.i(TAG, "Initializing builtin tools...")

            // ══════════════════════════════════════════════════════════════
            // 注册核心工具
            // ══════════════════════════════════════════════════════════════

            ToolRegistry.registerAll(
                GetCurrentTimeTool,
                AskUserTool
            )

            // ══════════════════════════════════════════════════════════════
            // 注册文件工具
            // ══════════════════════════════════════════════════════════════

            ToolRegistry.registerAll(
                FileReadTool,
                FileWriteTool,
                FileAppendTool,
                FileDeleteTool,
                FileListTool,
                FileSearchTool,
                FileInfoTool,
                FileMoveTool,
                FileCopyTool,
                FileMkdirTool
            )

            // ══════════════════════════════════════════════════════════════
            // 注册 UI 工具
            // ══════════════════════════════════════════════════════════════

            ToolRegistry.registerAll(
                GetUiCapabilitiesTool,
                AdjustUiTool,
                ResetUiToDefaultTool,
                ColorSchemeTool
            )

            // ══════════════════════════════════════════════════════════════
            // 注册 UI 文本工具
            // ══════════════════════════════════════════════════════════════

            ToolRegistry.registerAll(
                ListUiTextsTool,
                SetUiTextsTool
            )

            // ══════════════════════════════════════════════════════════════
            // 注册记忆工具
            // ══════════════════════════════════════════════════════════════

            ToolRegistry.registerAll(
                SearchMemoryTool,
                MarkRemindedTool,
                ConsolidateMemoryTool
            )

            // ══════════════════════════════════════════════════════════════
            // 注册效率工具（定时器）
            // ══════════════════════════════════════════════════════════════

            ToolRegistry.registerAll(
                CreateTimerTool,
                CancelTimerTool,
                ListTimersTool,
                SetToolDisplayModeTool
            )

            // ══════════════════════════════════════════════════════════════
            // 注册工具组管理
            // ══════════════════════════════════════════════════════════════

            ToolRegistry.registerAll(
                ListMcpToolGroupsTool,
                ConfigureMcpToolGroupsTool
            )

            // ══════════════════════════════════════════════════════════════
            // 注册 SubAgent 工具
            // ══════════════════════════════════════════════════════════════

            ToolRegistry.registerAll(
                DelegateTaskTool,
                CheckTaskStatusTool,
                SendAgentMessageTool,
                ReadAgentInboxTool,
                RunWorkflowTool,
                ExportSessionLogTool
            )

            // ══════════════════════════════════════════════════════════════
            // 注册文档工具
            // ══════════════════════════════════════════════════════════════

            ToolRegistry.registerAll(
                CreateDocumentTool,
                DocumentReadTool
            )

            // ══════════════════════════════════════════════════════════════
            // 注册项目工具
            // ══════════════════════════════════════════════════════════════

            ToolRegistry.registerAll(
                ProjectListKnowledgeTool,
                ProjectReadKnowledgeTool,
                ProjectCreateKnowledgeTool,
                ProjectReadMemoryTool,
                ProjectUpdateMemoryTool
            )

            initialized = true

            Log.i(TAG, "Builtin tools initialized: ${ToolRegistry.size()} tools registered")
            Log.d(TAG, "Registered tools: ${ToolRegistry.getAll().map { it.name }.sorted().joinToString(", ")}")
        }
    }

    /**
     * 检查是否已初始化。
     */
    fun isInitialized(): Boolean = initialized

    /**
     * 重置（仅用于测试）。
     */
    fun reset() {
        synchronized(this) {
            ToolRegistry.clear()
            initialized = false
            Log.i(TAG, "ToolInitializer reset")
        }
    }
}
