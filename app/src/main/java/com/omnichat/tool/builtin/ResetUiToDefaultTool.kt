package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.data.AppDatabase
import com.omnichat.data.AppRepository
import com.omnichat.data.UISettings
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject

/** Restores every persisted UI appearance setting to its default value. */
object ResetUiToDefaultTool : BuiltinTool(
    name = "reset_ui_to_default",
    description = "Reset every persisted UI appearance setting to the app defaults.",
    group = "ui_appearance",
    isReadOnly = false,
    isConcurrencySafe = false,
    searchHint = "reset UI appearance to defaults"
) {
    override val inputSchema: JSONObject = schema { }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        AppRepository(AppDatabase.getDatabase(context)).upsertUISettings(UISettings())
        return successResponse("UI settings reset to defaults.")
    }
}
