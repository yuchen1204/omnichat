package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.UISettings
import com.example.mcp.BuiltinToolHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("OmniChat", appName)
  }

  @Test
  fun `test ui settings flow emission`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    val dao = db.uiSettingsDao()

    val emitted = mutableListOf<UISettings?>()
    val job = launch {
      dao.getSettingsFlow().collect {
        emitted.add(it)
      }
    }

    delay(200)
    
    val setting = UISettings(id = 1L, uiStrings = "{\"test_key\":\"test_val\"}")
    dao.upsertSettings(setting)

    delay(200)
    job.cancel()

    // Assert that the flow emitted the inserted setting
    val latest = emitted.lastOrNull()
    assertNotNull(latest)
    assertEquals("{\"test_key\":\"test_val\"}", latest?.uiStrings)
    db.close()
  }

  @Test
  fun `test builtin tool set_ui_texts`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = AppDatabase.getDatabase(context)
    val dao = db.uiSettingsDao()

    // Ensure database starts with clean settings
    dao.upsertSettings(UISettings(id = 1L, uiStrings = "{}"))

    val emitted = mutableListOf<UISettings?>()
    val job = launch {
      dao.getSettingsFlow().collect {
        emitted.add(it)
      }
    }

    delay(200)

    val arguments = JSONObject().apply {
      put("updates", JSONObject().apply {
        put("chat.input.hint", "Write something...")
      })
    }

    BuiltinToolHandler.handleBuiltinTool(context, "set_ui_texts", arguments)

    delay(200)
    job.cancel()

    val latest = emitted.lastOrNull()
    assertNotNull(latest)
    val stringsMap = com.example.ui.theme.UiStrings.fromJson(latest?.uiStrings ?: "{}").overrides
    assertEquals("Write something...", stringsMap["chat.input.hint"])
    
    // Clean up
    dao.upsertSettings(UISettings(id = 1L, uiStrings = "{}"))
  }
}
