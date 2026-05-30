package com.example.workspace

import org.json.JSONObject

/**
 * 结构化消息协议 — 用于 Agent 间的结构化通信。
 *
 * 对齐 Claude Code 的 SendMessageTool 结构化消息：
 * - shutdown_request: 请求 Agent 关闭
 * - shutdown_response: Agent 响应关闭请求（approve/reject）
 * - plan_approval_response: 计划审批响应
 */
sealed class StructuredMessage {
    abstract fun toJson(): JSONObject

    /**
     * 关闭请求消息。
     *
     * Team lead 发送给 teammate，请求其关闭。
     * Teammate 可以拒绝并提供理由。
     */
    data class ShutdownRequest(
        /** 请求 ID，用于匹配响应 */
        val requestId: String,
        /** 发送者名称 */
        val from: String,
        /** 关闭理由 */
        val reason: String? = null,
    ) : StructuredMessage() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", "shutdown_request")
            put("requestId", requestId)
            put("from", from)
            if (reason != null) put("reason", reason)
        }

        companion object {
            fun fromJson(obj: JSONObject): ShutdownRequest = ShutdownRequest(
                requestId = obj.getString("requestId"),
                from = obj.getString("from"),
                reason = obj.optString("reason", null),
            )
        }
    }

    /**
     * 关闭响应消息。
     *
     * Teammate 响应 shutdown_request。
     * 如果 approve=true，teammate 将退出。
     * 如果 approve=false，teammate 继续运行并提供 reason。
     */
    data class ShutdownResponse(
        /** 对应的请求 ID */
        val requestId: String,
        /** 发送者名称 */
        val from: String,
        /** 是否同意关闭 */
        val approve: Boolean,
        /** 拒绝理由（当 approve=false 时必需） */
        val reason: String? = null,
        /** Pane ID（用于 tmux 模式清理） */
        val paneId: String? = null,
        /** Backend type */
        val backendType: String? = null,
    ) : StructuredMessage() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", "shutdown_response")
            put("requestId", requestId)
            put("from", from)
            put("approve", approve)
            if (reason != null) put("reason", reason)
            if (paneId != null) put("paneId", paneId)
            if (backendType != null) put("backendType", backendType)
        }

        companion object {
            fun fromJson(obj: JSONObject): ShutdownResponse = ShutdownResponse(
                requestId = obj.getString("requestId"),
                from = obj.getString("from"),
                approve = obj.getBoolean("approve"),
                reason = obj.optString("reason", null),
                paneId = obj.optString("paneId", null),
                backendType = obj.optString("backendType", null),
            )
        }
    }

    /**
     * 计划审批响应消息。
     *
     * Team lead 发送给 teammate，审批或拒绝其计划。
     * 用于 plan_mode_required=true 的 Agent。
     */
    data class PlanApprovalResponse(
        /** 对应的请求 ID */
        val requestId: String,
        /** 发送者名称 */
        val from: String,
        /** 是否批准 */
        val approved: Boolean,
        /** 反馈（拒绝时提供） */
        val feedback: String? = null,
        /** 继承的权限模式 */
        val permissionMode: String? = null,
    ) : StructuredMessage() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("type", "plan_approval_response")
            put("requestId", requestId)
            put("from", from)
            put("approved", approved)
            if (feedback != null) put("feedback", feedback)
            if (permissionMode != null) put("permissionMode", permissionMode)
        }

        companion object {
            fun fromJson(obj: JSONObject): PlanApprovalResponse = PlanApprovalResponse(
                requestId = obj.getString("requestId"),
                from = obj.getString("from"),
                approved = obj.getBoolean("approved"),
                feedback = obj.optString("feedback", null),
                permissionMode = obj.optString("permissionMode", null),
            )
        }
    }

    companion object {
        /**
         * 从 JSON 解析结构化消息。
         */
        fun fromJson(json: String): StructuredMessage? {
            return try {
                val obj = JSONObject(json)
                val type = obj.getString("type")
                when (type) {
                    "shutdown_request" -> ShutdownRequest.fromJson(obj)
                    "shutdown_response" -> ShutdownResponse.fromJson(obj)
                    "plan_approval_response" -> PlanApprovalResponse.fromJson(obj)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 结构化消息工厂方法。
 */
object StructuredMessageFactory {
    private var requestCounter = 0

    fun generateRequestId(type: String, target: String): String {
        requestCounter++
        return "${type}_${target}_${System.currentTimeMillis()}_${requestCounter}"
    }

    fun createShutdownRequest(from: String, reason: String? = null): StructuredMessage.ShutdownRequest =
        StructuredMessage.ShutdownRequest(
            requestId = generateRequestId("shutdown", from),
            from = from,
            reason = reason,
        )

    fun createShutdownApproved(
        requestId: String,
        from: String,
        paneId: String? = null,
        backendType: String? = null,
    ): StructuredMessage.ShutdownResponse =
        StructuredMessage.ShutdownResponse(
            requestId = requestId,
            from = from,
            approve = true,
            paneId = paneId,
            backendType = backendType,
        )

    fun createShutdownRejected(
        requestId: String,
        from: String,
        reason: String,
    ): StructuredMessage.ShutdownResponse =
        StructuredMessage.ShutdownResponse(
            requestId = requestId,
            from = from,
            approve = false,
            reason = reason,
        )

    fun createPlanApproved(
        requestId: String,
        from: String,
        permissionMode: String? = null,
    ): StructuredMessage.PlanApprovalResponse =
        StructuredMessage.PlanApprovalResponse(
            requestId = requestId,
            from = from,
            approved = true,
            permissionMode = permissionMode,
        )

    fun createPlanRejected(
        requestId: String,
        from: String,
        feedback: String,
    ): StructuredMessage.PlanApprovalResponse =
        StructuredMessage.PlanApprovalResponse(
            requestId = requestId,
            from = from,
            approved = false,
            feedback = feedback,
        )
}
