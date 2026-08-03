# Project Knowledge 与项目会话改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有项目原型改造成具备 Project Knowledge 资源管理、单文件项目记忆、项目内独立会话和严格项目工具/MCP权限的完整流程。

**Architecture:** 保留现有 `Project`、`ProjectKnowledge`、`Session.projectId`、`ProjectTools` 和项目页面作为基础，集中补齐数据一致性、文件路径绑定、来源字段、项目级 MCP 配置和项目会话权限上下文。项目会话由 `projectId` 识别；工具执行层在导出和执行两处都应用项目白名单，项目资产读取始终通过资产 ID 与当前项目归属校验。

**Tech Stack:** Kotlin 2.2.10, Android/Compose Material 3, Room, Coroutines/Flow, existing `ToolRegistry`/`ToolExecutor`, `JsDocumentReader`, Android Storage Access Framework, JUnit/Robolectric project test conventions.

## Global Constraints

- Android SDK 36, minSdk 26, JDK 21+.
- 不引入 DI 框架；沿用 `AndroidViewModel`、`AppRepository` 和现有 Room 架构。
- Room 迁移必须显式、顺序执行，不能用 destructive migration 丢失 v4+ 用户数据。
- 用户上传文件复制到应用私有目录；原始 URI 不能作为项目资产的唯一存储。
- 用户资产类型为图片、PDF、DOC、DOCX、TXT、MD；Agent 创建类型为 TXT、MD、DOC、DOCX。
- Project Knowledge 列表只展示名称、类型、来源、创建时间，不展示大小；用户可删除但不能修改、重命名或覆盖。
- 每个项目只有一个共享的 `project_memory.md`；用户只读，Agent 可读写；项目记忆与全局记忆隔离。
- 项目会话只在项目页面内展示，不进入普通聊天侧边栏；同项目多个会话共享资产和项目记忆。
- 项目会话只允许项目资产工具、项目记忆工具，以及“全局当前启用且未被项目禁用”的 MCP 工具。
- 普通文件、全局记忆、联网、UI、定时器、子 Agent、工作流和其他内置工具在项目会话中禁用。
- 不实现 MCP 直接访问项目资产桥接、在线资产预览、用户编辑资产或多记忆文件。
- 当前工作区已有未提交项目相关改动；实现时保留并审查这些改动，不还原用户现有修改。

---

## File Map

- `app/src/main/java/com/omnichat/data/Entities.kt`：补充项目配置、资产本地路径/来源等持久化字段。
- `app/src/main/java/com/omnichat/data/Daos.kt`：增加项目资产、项目配置和项目会话所需的精确查询/更新接口。
- `app/src/main/java/com/omnichat/data/AppDatabase.kt`：增加从当前 v59 到目标版本的显式迁移并更新 schema。
- `app/src/main/java/com/omnichat/data/Repository.kt`：集中处理资产复制/删除、项目目录清理、项目记忆原子写入、项目级 MCP 配置和级联删除。
- `app/src/main/java/com/omnichat/tool/builtin/ProjectTools.kt`：重构项目工具为当前项目作用域，补齐资产创建/读取和记忆增删查改，禁止 Agent 删除资产。
- `app/src/main/java/com/omnichat/tool/ToolInitializer.kt`：确保项目工具按项目会话权限筛选，避免仅靠工具组暴露。
- `app/src/main/java/com/omnichat/tool/ToolRegistry.kt`、`app/src/main/java/com/omnichat/tool/ToolExecutor.kt`：接入项目权限上下文；若现有接口不适合，新增最小的 `ProjectToolScope`/会话过滤接口。
- `app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt`：提供全局启用 MCP 与项目禁用服务器的交集查询/执行校验。
- `app/src/main/java/com/omnichat/ui/viewmodel/ChatViewModel.kt`：创建项目会话但不自动切换普通导航；构建项目会话工具上下文；注入项目资产索引和项目记忆，跳过全局记忆；排除普通会话列表。
- `app/src/main/java/com/omnichat/ui/screens/ProjectScreen.kt`：项目列表/创建/打开/删除入口，导航到详情而不是自动创建会话。
- `app/src/main/java/com/omnichat/ui/screens/ProjectDetailScreen.kt`：详情页入口、Project Knowledge、只读 Project Memory、MCP 设置、项目会话列表和右下角新会话按钮。
- `app/src/main/java/com/omnichat/ui/screens/ProjectKnowledgeScreen.kt`：新增资源页面，文件选择器只允许规定扩展名，展示来源/类型/时间，用户删除确认。
- `app/src/main/java/com/omnichat/ui/screens/ProjectMemoryScreen.kt`：新增只读 Markdown 记忆查看页。
- `app/src/main/java/com/omnichat/ui/screens/ProjectMcpSettingsScreen.kt`：新增项目级 MCP 服务器筛选页面，默认继承全局启用服务器。
- `app/src/main/java/com/omnichat/ui/screens/MainScreen.kt`、`SessionSidebarPanel.kt`：调整项目入口、项目页面/聊天页面返回链路，确保普通侧边栏永不显示项目会话。
- `app/src/main/assets/ui_text_keys.json`：通过既有 `generateUiTextKeys` 任务更新，不手工维护生成内容。
- `app/src/test/java/com/omnichat/data/ProjectRepositoryTest.kt`：资产、项目记忆和级联清理测试。
- `app/src/test/java/com/omnichat/tool/ProjectToolScopeTest.kt`：项目工具归属和白名单测试。
- `app/src/test/java/com/omnichat/ui/viewmodel/ProjectSessionTest.kt`：项目会话列表隔离和上下文测试；按现有测试基础设施调整实际测试目录/fixture。

---

### Task 1: 固化项目数据模型、来源和数据库迁移

**Files:**
- Modify: `app/src/main/java/com/omnichat/data/Entities.kt: Project, ProjectKnowledge, Session`
- Modify: `app/src/main/java/com/omnichat/data/Daos.kt: ProjectDao, ProjectKnowledgeDao, SessionDao`
- Modify: `app/src/main/java/com/omnichat/data/AppDatabase.kt: database version and migrations`
- Create: `app/schemas/com.omnichat.data.AppDatabase/<new-version>.json` via Room schema generation
- Test: `app/src/test/java/com/omnichat/data/ProjectSchemaTest.kt`

**Interfaces:**
- Produces `Project.disabledMcpServerIds: String` as a JSON array string, `ProjectKnowledge.localFileName: String`, and `ProjectKnowledge.source: String` with values `USER_UPLOAD`/`AGENT_CREATED`.
- Produces DAO methods `getKnowledgeByProjectFlow(projectId)`, `getKnowledgeById(id)`, `getKnowledgeByIdForProject(id, projectId)`, `getProjectMcpDisabledIds(projectId)`, and `updateProjectMcpDisabledIds(projectId, json, updatedAt)`.
- Keeps `Session.projectId: Long?`; ordinary session queries continue using `WHERE projectId IS NULL`.

- [ ] **Step 1: Write failing schema/model tests**

```kotlin
@Test
fun projectAssetStoresSourceAndStableLocalFileName() {
    val asset = ProjectKnowledge(
        projectId = 7L,
        fileName = "notes.md",
        fileType = "md",
        localFileName = "asset_12.md",
        source = "USER_UPLOAD"
    )
    assertEquals("asset_12.md", asset.localFileName)
    assertEquals("USER_UPLOAD", asset.source)
}

@Test
fun projectDefaultsToInheritingAllMcpServers() {
    assertEquals("[]", Project(name = "demo").disabledMcpServerIds)
}
```

- [ ] **Step 2: Run the focused tests and confirm they fail**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.data.ProjectSchemaTest"`

Expected: FAIL because the new fields and migration are not present.

- [ ] **Step 3: Add fields, indexes, and DAO queries**

Use nullable-safe/defaulted Room columns so existing v59 rows migrate without data loss:

```kotlin
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val disabledMcpServerIds: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "project_knowledge",
    indices = [Index(value = ["projectId"]), Index(value = ["projectId", "id"])]
)
data class ProjectKnowledge(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val fileName: String,
    val fileType: String,
    val fileSize: Long = 0,
    val localFileName: String = "",
    val source: String = "USER_UPLOAD",
    val createdAt: Long = System.currentTimeMillis()
)
```

Add `getKnowledgeByIdForProject` so a caller cannot read an asset by global ID alone.

- [ ] **Step 4: Add explicit v59→v60 migration and regenerate schema**

The migration must execute:

```sql
ALTER TABLE projects ADD COLUMN disabledMcpServerIds TEXT NOT NULL DEFAULT '[]';
ALTER TABLE project_knowledge ADD COLUMN localFileName TEXT NOT NULL DEFAULT '';
ALTER TABLE project_knowledge ADD COLUMN source TEXT NOT NULL DEFAULT 'USER_UPLOAD';
```

Register `MIGRATION_59_60` after `MIGRATION_58_59`, set database version to 60, and do not add a destructive fallback for v4+.

- [ ] **Step 5: Run tests and schema validation**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.data.ProjectSchemaTest"`

Expected: PASS; Room schema export contains v60 columns and migration validation succeeds.

- [ ] **Step 6: Commit the data-layer deliverable**

```bash
git add app/src/main/java/com/omnichat/data/Entities.kt app/src/main/java/com/omnichat/data/Daos.kt app/src/main/java/com/omnichat/data/AppDatabase.kt app/schemas
git commit -m "feat: add project asset scope metadata"
```

### Task 2: Centralize project file storage and project memory service

**Files:**
- Create: `app/src/main/java/com/omnichat/data/ProjectFileStore.kt`
- Modify: `app/src/main/java/com/omnichat/data/Repository.kt`
- Test: `app/src/test/java/com/omnichat/data/ProjectFileStoreTest.kt`

**Interfaces:**
- Produces `ProjectFileStore.assetFile(projectId: Long, assetId: Long, originalName: String): File`, `copyIntoProject(context, projectId, sourceUri, originalName, source): ProjectKnowledge`, `deleteAsset(asset: ProjectKnowledge)`, `deleteProjectDirectory(projectId: Long)`.
- Produces repository methods `createProjectAssetFromUri`, `createAgentProjectAsset`, `readProjectAssetFile`, `deleteUserProjectAsset`, `readProjectMemory`, and `updateProjectMemory`.
- All file paths are generated from database IDs/localFileName; no UI/tool code searches by suffix or accepts arbitrary external paths.

- [ ] **Step 1: Write failing storage tests**

```kotlin
@Test
fun generatedAssetPathStaysInsideProjectDirectory() {
    val file = ProjectFileStore.assetFile(4L, 9L, "../secret.txt")
    assertTrue(file.canonicalPath.startsWith(projectRoot(4L).canonicalPath))
    assertEquals("asset_9.txt", file.name)
}

@Test
fun memoryUpdateUsesAtomicReplacementAndPreservesContentOnFailure() = runTest {
    store.writeMemory(4L, "old")
    store.replaceMemory(4L, "new")
    assertEquals("new", store.readMemory(4L))
}
```

- [ ] **Step 2: Run the focused tests and confirm failure**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.data.ProjectFileStoreTest"`

Expected: FAIL because `ProjectFileStore` and repository APIs do not exist.

- [ ] **Step 3: Implement canonical private paths and MIME validation**

Allow exactly these extensions: `jpg`, `jpeg`, `png`, `gif`, `bmp`, `webp`, `pdf`, `doc`, `docx`, `txt`, `md`. Reject all others before copying. Sanitize display names and create an internal name `asset_<id>.<ext>` after obtaining the inserted row ID; use a temporary file during the copy and delete it on failure.

- [ ] **Step 4: Implement repository-owned asset lifecycle**

Copy the URI into a temporary file, insert the metadata, rename/copy to the ID-based final path, then persist `localFileName`. On any failure remove the temporary/final file and do not leave a successful database row. Agent-created assets must accept content bytes/text or an existing generated document stream, never a caller-supplied arbitrary destination path.

- [ ] **Step 5: Implement serialized atomic Markdown memory writes**

Use a per-project `Mutex` map in `ProjectFileStore`; write to `project_memory.md.tmp`, flush/close, then rename to `project_memory.md`. `readProjectMemory` returns an empty string when absent. `updateProjectMemory` supports full replacement plus append/replace/delete operations used by the tool layer.

- [ ] **Step 6: Test delete and project cascade behavior**

Verify deleting an asset removes both row and file, and deleting a project removes the project directory after deleting sessions and asset rows. Verify a failed copy does not create an orphan row.

- [ ] **Step 7: Run tests and commit**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.data.ProjectFileStoreTest"`

Expected: PASS.

```bash
git add app/src/main/java/com/omnichat/data/ProjectFileStore.kt app/src/main/java/com/omnichat/data/Repository.kt app/src/test/java/com/omnichat/data/ProjectFileStoreTest.kt
git commit -m "feat: centralize project asset storage"
```

### Task 3: Enforce project tool scope and complete project tools

**Files:**
- Modify: `app/src/main/java/com/omnichat/tool/Tool.kt`
- Modify: `app/src/main/java/com/omnichat/tool/ToolRegistry.kt`
- Modify: `app/src/main/java/com/omnichat/tool/ToolExecutor.kt`
- Modify: `app/src/main/java/com/omnichat/tool/ToolInitializer.kt`
- Modify: `app/src/main/java/com/omnichat/tool/builtin/ProjectTools.kt`
- Test: `app/src/test/java/com/omnichat/tool/ProjectToolScopeTest.kt`

**Interfaces:**
- Produces `ProjectToolScope(sessionId: Long, projectId: Long, allowedMcpServerIds: Set<Long>)`.
- Produces `ToolRegistry.toolsForSession(scope: ProjectToolScope?): List<Tool>` and `ToolExecutor.execute(..., projectScope: ProjectToolScope?)` behavior that rejects out-of-scope tools before `doExecute`.
- Project built-ins exposed in a project session are exactly `project_list_knowledge`, `project_read_knowledge`, `project_create_knowledge`, `project_read_memory`, `project_update_memory`; project creation/list/delete and Agent asset deletion are not exposed in project sessions.

- [ ] **Step 1: Write failing whitelist and ownership tests**

```kotlin
@Test
fun projectSessionExposesOnlyProjectToolsAndAllowedMcp() {
    val names = registry.toolsForSession(scope).map { it.name }.toSet()
    assertEquals(
        setOf("project_list_knowledge", "project_read_knowledge",
              "project_create_knowledge", "project_read_memory", "project_update_memory",
              "mcp_server_tool"), names
    )
}

@Test
fun assetFromAnotherProjectIsRejected() = runTest {
    val result = ProjectReadKnowledgeTool.execute(context, json("knowledge_id" to otherProjectAssetId), sessionId)
    assertEquals("permission", result.optString("error_type"))
}
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.tool.ProjectToolScopeTest"`

Expected: FAIL because current tools accept a caller-supplied project ID and `project_read_knowledge` does not verify ownership.

- [ ] **Step 3: Add scope filtering at registry/export and executor boundaries**

For a project session, reject every builtin tool not in the explicit project allowlist. For an ordinary session, keep existing behavior and do not expose project-scoped tools unless the existing product path explicitly needs project administration. Do not rely on prompt text or tool group `project` alone.

- [ ] **Step 4: Refactor tools to derive project ID from the session scope**

When a session has `projectId`, ignore or reject a mismatching `project_id` argument. Every asset read/list/create/update query must use both `assetId` and `projectId`. Remove `project_delete_knowledge` and arbitrary `project_upload_knowledge` from the project-session export. Preserve project administration only for the user UI/repository path.

- [ ] **Step 5: Implement asset readers through `ProjectFileStore`**

Use `localFileName` to resolve the file. Reuse `JsDocumentReader` for PDF, DOC, and DOCX; read TXT/MD as text. For images, return the existing model-compatible image input representation rather than exposing an absolute filesystem path as a permission bypass. Tool result metadata must not include file size.

- [ ] **Step 6: Implement Agent asset creation and memory CRUD**

`project_create_knowledge` accepts a filename and content/document payload, supports TXT/MD/DOC/DOCX, always creates a new asset row, and marks source `AGENT_CREATED`. Memory tools support read, append, replace-range/section, and delete-range/section on the one Markdown file, with serialized atomic writes. User-created assets remain non-editable.

- [ ] **Step 7: Run tests and commit**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.tool.ProjectToolScopeTest"`

Expected: PASS.

```bash
git add app/src/main/java/com/omnichat/tool app/src/main/java/com/omnichat/tool/builtin/ProjectTools.kt app/src/test/java/com/omnichat/tool/ProjectToolScopeTest.kt
git commit -m "feat: enforce project session tool scope"
```

### Task 4: Add project-level MCP inheritance and filtering

**Files:**
- Modify: `app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt`
- Modify: `app/src/main/java/com/omnichat/data/Repository.kt`
- Modify: `app/src/main/java/com/omnichat/tool/ToolRegistry.kt`
- Modify: `app/src/main/java/com/omnichat/tool/ToolExecutor.kt`
- Create/Modify: `app/src/test/java/com/omnichat/mcp/ProjectMcpScopeTest.kt`

**Interfaces:**
- Produces `Repository.getProjectDisabledMcpServerIds(projectId): Set<Long>` and `setProjectDisabledMcpServerIds(projectId, ids: Set<Long>)`.
- Produces `McpRuntimeManager.getProjectEnabledTools(projectId): List<Tool>` representing global enabled servers minus project disabled IDs.

- [ ] **Step 1: Write failing inheritance tests**

```kotlin
@Test
fun projectMcpScopeIsGlobalEnabledMinusProjectDisabled() = runTest {
    // global: 1, 2, 3; project disabled: 2
    assertEquals(setOf(1L, 3L), manager.enabledServerIdsForProject(8L))
}

@Test
fun globallyDisabledServerCannotBeReenabledByProject() = runTest {
    // global: 1; project disabled is empty; server 2 is absent
    assertEquals(setOf(1L), manager.enabledServerIdsForProject(8L))
}
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.mcp.ProjectMcpScopeTest"`

Expected: FAIL because project-level disabled-server state and intersection query are missing.

- [ ] **Step 3: Implement JSON config persistence and intersection query**

Parse invalid JSON as an empty set, normalize IDs, and persist only valid server IDs. The effective set must be recomputed from current global state each time rather than copied into the project, so global disable takes effect immediately and global newly enabled servers are inherited unless explicitly disabled.

- [ ] **Step 4: Apply MCP checks at tool export and execution**

Filter remote MCP adapters before model-facing tool export and reject an execution whose server ID is not in the current effective set. Project-level disabled IDs must not affect ordinary sessions.

- [ ] **Step 5: Run tests and commit**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.mcp.ProjectMcpScopeTest"`

Expected: PASS.

```bash
git add app/src/main/java/com/omnichat/mcp/McpRuntimeManager.kt app/src/main/java/com/omnichat/data/Repository.kt app/src/main/java/com/omnichat/tool app/src/test/java/com/omnichat/mcp/ProjectMcpScopeTest.kt
git commit -m "feat: filter project MCP servers"
```

### Task 5: Rebuild Project Knowledge and Project Memory UI flow

**Files:**
- Create: `app/src/main/java/com/omnichat/ui/screens/ProjectKnowledgeScreen.kt`
- Create: `app/src/main/java/com/omnichat/ui/screens/ProjectMemoryScreen.kt`
- Create: `app/src/main/java/com/omnichat/ui/screens/ProjectMcpSettingsScreen.kt`
- Modify: `app/src/main/java/com/omnichat/ui/screens/ProjectDetailScreen.kt`
- Modify: `app/src/main/java/com/omnichat/ui/screens/ProjectScreen.kt`
- Modify: `app/src/main/java/com/omnichat/ui/screens/MainScreen.kt`
- Test: `app/src/test/java/com/omnichat/ui/ProjectFlowTest.kt` or existing Compose test location

**Interfaces:**
- Project detail navigation exposes `onKnowledge`, `onMemory`, `onMcpSettings`, and `onCreateProjectSession` callbacks.
- Knowledge screen consumes `Flow<List<ProjectKnowledge>>`, `onUpload(Uri)`, and `onDelete(ProjectKnowledge)`; it never displays `fileSize` and has no edit/rename action.
- Memory screen consumes `String` only and renders it read-only.

- [ ] **Step 1: Write failing UI/state tests**

```kotlin
@Test
fun knowledgeRowShowsSourceButNotSize() {
    composeRule.setContent { KnowledgeFileRow(asset = sampleAsset) }
    composeRule.onNodeWithText("用户上传").assertIsDisplayed()
    composeRule.onNodeWithText("123 KB").assertDoesNotExist()
}

@Test
fun detailScreenOffersProjectKnowledgeAndBottomRightNewSessionAction() {
    composeRule.setContent { ProjectDetailScreen(/* test fixtures */) }
    composeRule.onNodeWithText("Project Knowledge").assertIsDisplayed()
    composeRule.onNodeWithText("创建新会话").assertIsDisplayed()
}
```

- [ ] **Step 2: Run tests and confirm failure**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.ui.ProjectFlowTest"`

Expected: FAIL because current detail screen combines knowledge/session/settings tabs, displays file size, and has no separate resource/memory/MCP routes.

- [ ] **Step 3: Implement Project Knowledge screen**

Use `ActivityResultContracts.OpenMultipleDocuments` with the exact MIME set (`image/*`, `application/pdf`, `application/msword`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `text/plain`, `text/markdown`). Copy selected URIs through Repository/`ProjectFileStore`; do not perform file copying directly in Compose. Display filename, normalized type label, source label, and formatted creation time. Confirm before user deletion and show an error if database/file cleanup fails.

- [ ] **Step 4: Implement read-only Project Memory screen**

Load the single project memory file via Repository, render Markdown using the existing text/Markdown component, show an empty state when missing, and expose no text editor or destructive action.

- [ ] **Step 5: Implement project MCP settings screen**

List only globally enabled MCP servers, default each to enabled unless its ID is in the project disabled set, persist changes through the Repository, and explain that global disable always wins.

- [ ] **Step 6: Update detail/list navigation and remove conflicting actions**

Project detail becomes an overview with explicit **Project Knowledge**, **Project Memory**, **MCP 设置**, and project-session list. Put **创建新会话** in the bottom-right floating action button position. Remove download/edit actions from asset rows; keep user delete. Project creation opens detail without automatically creating a first session. Project deletion remains a confirmed cascade action.

- [ ] **Step 7: Run UI tests and commit**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.ui.ProjectFlowTest"`

Expected: PASS.

```bash
git add app/src/main/java/com/omnichat/ui/screens app/src/main/java/com/omnichat/ui/screens/MainScreen.kt app/src/test/java/com/omnichat/ui
git commit -m "feat: add project knowledge flow"
```

### Task 6: Integrate project sessions into ChatViewModel and navigation isolation

**Files:**
- Modify: `app/src/main/java/com/omnichat/ui/viewmodel/ChatViewModel.kt`
- Modify: `app/src/main/java/com/omnichat/ui/screens/MainScreen.kt`
- Modify: `app/src/main/java/com/omnichat/ui/screens/SessionSidebarPanel.kt`
- Modify: `app/src/main/java/com/omnichat/ui/screens/ProjectDetailScreen.kt`
- Modify: `app/src/main/java/com/omnichat/agent/AgentPrompts.kt`
- Test: `app/src/test/java/com/omnichat/ui/viewmodel/ProjectSessionTest.kt`

**Interfaces:**
- Produces `ChatViewModel.createProjectSession(projectId: Long, title: String): Long` behavior that creates only the requested session and returns/selects it within project navigation.
- Produces `ChatViewModel.projectScopeForSession(sessionId: Long): ProjectToolScope?`.
- `nonProjectSessions` remains the only source for the ordinary sidebar.

- [ ] **Step 1: Write failing session isolation tests**

```kotlin
@Test
fun creatingProjectSessionDoesNotAddItToOrdinarySessions() = runTest {
    val id = viewModel.createProjectSessionAndWait(projectId = 3L, title = "Research")
    assertTrue(viewModel.nonProjectSessions.value.none { it.id == id })
    assertTrue(viewModel.projectSessions.value.any { it.id == id })
}

@Test
fun projectPromptSkipsGlobalMemoryAndIncludesAssetIndex() = runTest {
    val prompt = viewModel.buildPromptForSession(projectSessionId)
    assertTrue(prompt.contains("project_list_knowledge"))
    assertFalse(prompt.contains("[CROSS_SESSION_MEMORY]"))
}
```

- [ ] **Step 2: Run tests and confirm failure**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.ui.viewmodel.ProjectSessionTest"`

Expected: FAIL because current `selectProject` creates a session automatically and the prompt/tool path still needs a complete project scope.

- [ ] **Step 3: Separate project-page selection from ordinary chat selection**

Remove automatic project-session creation from `selectProject`; selecting a project opens its detail page. `createProjectSession` must be the only path that creates a session. Project session selection must route to project chat, while ordinary sidebar callbacks cannot select a project session.

- [ ] **Step 4: Build project context at message/tool time**

Read `session.projectId` immediately before building the model request. Construct `ProjectToolScope` from the current project and current effective MCP IDs. Include only a compact asset index (ID/name/type/source/time) and project-memory instructions; do not inject all asset contents. Skip global long-term memory injection and global memory consolidation for project sessions.

- [ ] **Step 5: Apply strict tool list to every request path**

Ensure initial request, retry, streaming continuation, tool-call follow-up, and background/foreground resume all use the same project scope. Ordinary sessions retain the existing tool catalog. A project session must not reach file tools, memory tools, document tools, sub-agent/workflow tools, or UI tools through aliases or remote adapter lookup.

- [ ] **Step 6: Update prompt instructions and sidebar sources**

Add explicit prompt instructions that the Agent must use project tools for assets and the one Markdown memory file, cannot use global memory, and may use only effective MCP tools. Keep project sessions out of `SessionSidebarPanel` even after app recreation by querying `nonProjectSessions`.

- [ ] **Step 7: Run tests and commit**

Run: `gradlew.bat testDebugUnitTest --tests "com.omnichat.ui.viewmodel.ProjectSessionTest"`

Expected: PASS.

```bash
git add app/src/main/java/com/omnichat/ui/viewmodel/ChatViewModel.kt app/src/main/java/com/omnichat/ui/screens/MainScreen.kt app/src/main/java/com/omnichat/ui/screens/SessionSidebarPanel.kt app/src/main/java/com/omnichat/ui/screens/ProjectDetailScreen.kt app/src/main/java/com/omnichat/agent/AgentPrompts.kt app/src/test/java/com/omnichat/ui/viewmodel/ProjectSessionTest.kt
git commit -m "feat: isolate project chat sessions"
```

### Task 7: Remove stale project behavior and validate the complete feature

**Files:**
- Modify: `app/src/main/java/com/omnichat/tool/builtin/ProjectTools.kt`
- Modify: `app/src/main/java/com/omnichat/ui/screens/ProjectDetailScreen.kt`
- Modify: `app/src/main/java/com/omnichat/data/Repository.kt`
- Modify: `app/src/main/assets/ui_text_keys.json` via Gradle generation
- Test: existing project/data/tool/UI tests plus full debug unit test suite

**Interfaces:**
- No new public interface; this task reconciles old prototype paths with the final design and verifies all cross-layer invariants.

- [ ] **Step 1: Search for stale paths and forbidden capabilities**

Run:

```powershell
rg "fileSize|onDownload|project_upload_knowledge|project_delete_knowledge|fallbackToDestructiveMigration\(\)|selectProject\(projectId\).*create|CROSS_SESSION_MEMORY" app/src/main/java
```

Expected after cleanup: no Project Knowledge UI/tool output exposing size or download, no Agent asset delete/upload-by-path capability, no project-session global memory placeholder, and no new destructive v4+ migration fallback introduced by this feature.

- [ ] **Step 2: Add regression tests for cascade and permission invariants**

```kotlin
@Test
fun projectDeleteRemovesSessionsAssetsMemoryAndDirectory() = runTest { /* create fixture, delete project, assert all four are absent */ }

@Test
fun ordinarySessionCannotReadProjectAssetEvenWithAssetId() = runTest { /* execute project read using ordinary session, assert permission error */ }

@Test
fun projectSessionCannotCallGlobalMemoryOrFileTools() = runTest { /* assert executor rejects both tool names */ }
```

- [ ] **Step 3: Run the complete verification suite**

Run:

```powershell
gradlew.bat testDebugUnitTest
```

Expected: PASS for all unit tests.

- [ ] **Step 4: Generate UI text keys and build the APK**

Run:

```powershell
gradlew.bat generateUiTextKeys assembleDebug
```

Expected: `ui_text_keys.json` is regenerated and `:app:assembleDebug` completes successfully. Existing non-blocking compiler/deprecation warnings may remain, but no Kotlin, Room, resource, or packaging error is acceptable.

- [ ] **Step 5: Install and smoke-test the real flow**

Run:

```powershell
gradlew.bat installDebug
```

On the connected device manually verify: project button → create/open project → Project Knowledge → upload each supported type → list shows source/type/time without size → delete; return → Project Memory is read-only; MCP settings inherit/filter; create multiple project sessions from bottom-right button; sessions remain absent from ordinary sidebar; project chat reads an asset through the project tool, updates `project_memory.md`, and cannot invoke ordinary file/global-memory tools.

- [ ] **Step 6: Commit final cleanup**

```bash
git add app/src/main/java app/src/main/assets/ui_text_keys.json app/src/test
 git commit -m "test: validate project knowledge sessions"
```

## Self-Review Checklist

- [x] Spec coverage: data/migration (Task 1), private storage/cascade (Task 2), project tool permissions and asset/document handling (Task 3), MCP inheritance (Task 4), UI flow (Task 5), session/prompt/sidebar isolation (Task 6), cleanup and full verification (Task 7).
- [x] Placeholder scan: every task includes concrete files, interfaces, commands, expected results, and code-level behavior; no `TBD`, `TODO`, or unspecified “add appropriate handling” steps.
- [x] Type consistency: `ProjectToolScope`, `ProjectFileStore`, `ProjectKnowledge.localFileName/source`, and repository/MCP methods are introduced before their consumers.
- [x] Existing prototype compatibility: v59→v60 is additive; existing `Session.projectId` and current project tables are reused; current uncommitted files are not discarded.
- [x] Scope boundary: no online preview, MCP asset bridge, user asset editing, or additional memory files.
