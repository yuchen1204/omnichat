package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.mcp.AskUserManager
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * 询问用户工具。
 *
 * 功能：
 * - 向用户提出澄清问题
 * - 支持预设选项列表
 * - 支持单选/多选模式
 */
object AskUserTool : BuiltinTool(
    name = "ask_user",
    description = """Ask the user a clarifying question when their request is ambiguous or underspecified, or to confirm a decision. You can provide 1 to 5 options for them to choose from, or they can input their custom answer. Supports single-select (default) and multi-select modes. The function will block and wait for user response.""",
    group = "core",
    isReadOnly = true,
    isConcurrencySafe = true,
    requiresSession = true,
    searchHint = "ask user a clarifying question"
) {

    override val inputSchema = schema {
        prop("question", "string", "The clarifying question or prompt to display to the user.")
        prop("options", "array", "Optional list of 1 to 5 predefined options that the user can choose from.") {
            items { }
        }
        prop("multi_select", "boolean", "If true, the user can select multiple options (checkboxes). If false or omitted, the user can only select one option (buttons). When multi_select is true, the response is a JSON array of selected options.")
        required("question")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val question = arguments.optString("question").trim()
        if (question.isEmpty()) return "Question is required"

        val optionsArray = arguments.optJSONArray("options")
        if (optionsArray != null && optionsArray.length() > 5) {
            return "Maximum 5 options allowed"
        }

        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val question = arguments.optString("question")
        val optionsArray = arguments.optJSONArray("options")
        val multiSelect = arguments.optBoolean("multi_select", false)

        val options = mutableListOf<String>()
        if (optionsArray != null) {
            for (i in 0 until optionsArray.length()) {
                val opt = optionsArray.optString(i).trim()
                if (opt.isNotEmpty()) {
                    options.add(opt)
                }
            }
        }

        val response = AskUserManager.askUser(question, options, multiSelect)
        return successResponse(response)
    }
}