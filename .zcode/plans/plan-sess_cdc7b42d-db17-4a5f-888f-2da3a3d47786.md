## 修复计划：Issues 1-3

### 分支策略
基于 `main` 创建新分支 `fix/memory-architecture-1-3`

---

### Issue 1：记忆同步异步化 — 使用 Channel 替代 Mutex

**当前问题**：`triggerMemorySync()` 在 `startAssistantResponse()` 末尾被调用，`viewModelScope.launch` 虽不阻塞 UI，但：
- 每次回复后都启动新协程，`Mutex.tryLock()` 静默丢弃并发请求
- 没有请求合并机制，快速连续发送消息时只有第一条会触发同步

**修改方案**：用 `Channel<Boolean>(Channel.CONFLATED)` 替代 `Mutex`

**涉及文件**：`ChatViewModel.kt`

**具体改动**：

1. **添加 Channel 字段**（替换 `memorySyncMutex`）：
   ```kotlin
   private val memorySyncChannel = Channel<Boolean>(Channel.CONFLATED)
   ```

2. **在 `init` 块中启动后台处理循环**：
   ```kotlin
   viewModelScope.launch {
       for (force in memorySyncChannel) {
           isMemorySyncing = true
           try {
               executeMemorySync(force)
           } finally {
               isMemorySyncing = false
           }
       }
   }
   ```

3. **将同步逻辑抽取为 `executeMemorySync(force: Boolean)` 私有方法**（原 `triggerMemorySync` 的 lambda 体）

4. **简化 `triggerMemorySync`**：
   ```kotlin
   fun triggerMemorySync(force: Boolean = false) {
       if (_selectedSessionId.value == null) return
       memorySyncChannel.trySend(force)
   }
   ```

5. **删除** `memorySyncMutex` 字段和 `import kotlinx.coroutines.sync.Mutex`

**效果**：
- `triggerMemorySync` 立刻返回，无协程开销
- `CONFLATED` Channel 天然合并短时间内多次请求
- 单协程串行处理，无需 Mutex
- `isMemorySyncing` 状态仍保持，UI 可展示同步进度

---

### Issue 2：`SearchMemoryTool` 委托给 `MemoryEngine.searchMemory`

**当前问题**：
- `SearchMemoryTool` 只使用 Jaccard 相似度，不支持 embedding 语义搜索
- `SearchMemoryTool` 与 `MemoryEngine.searchMemory` 有大量重复代码（候选集获取、Jaccard 评分、BFS 展开）

**修改方案**：`SearchMemoryTool.doExecute` 创建 `MemoryEngine` 实例并委托给 `memoryEngine.searchMemory()`

**涉及文件**：`MemoryTools.kt`、`MemoryEngine.kt`（需确认 `MemorySearchResult` 可见性）

**具体改动**：

1. **`MemoryTools.kt` — `SearchMemoryTool.doExecute` 重构**：
   - 获取候选集和 totalCount 的逻辑保留（用于计算 totalCount 和限制搜索范围）
   - 创建 `MemoryEngine` 实例：`val memoryEngine = MemoryEngine(repository, ApiClient)`
   - 调用 `memoryEngine.searchMemory(query, tagFilter, limit, depth, totalCount)` 获取结果
   - 用 `MemorySearchResult` 中的 `scored` 和 `expandedMemories` 格式化输出

2. **`MemoryEngine.kt` — 确认 `MemorySearchResult` 和 `ScoredMemoryItem` 是 `public` 可见性**（它们是 `data class`，当前无访问修饰符，默认为 `public`，`MemoryTools.kt` 可通过 `com.omnichat.memory.MemorySearchResult` 引用）

**效果**：
- 消除重复代码
- 工具搜索自动使用 embedding 语义评分（当配置了 embedding 模型时）
- 保持向后兼容（工具参数不变）

---

### Issue 3：`selectRelevantMemories` 避免全表加载

**当前问题**：每次生成系统提示词时 `repository.getAllMemories()` 加载所有记忆到内存

**修改方案**：新增 DAO 方法，只加载置钉记忆 + top-k 未置钉记忆

**涉及文件**：`Daos.kt`、`Repository.kt`、`MemoryEngine.kt`

**具体改动**：

1. **`Daos.kt` — `MemoryItemDao` 新增 3 个查询**：
   ```kotlin
   @Query("SELECT * FROM memory_items WHERE pinned = 1 ORDER BY confidence DESC, updatedAt DESC")
   suspend fun getPinnedMemories(): List<MemoryItem>

   @Query("SELECT * FROM memory_items WHERE pinned = 0 ORDER BY confidence DESC, updatedAt DESC LIMIT :limit")
   suspend fun getTopUnpinnedMemories(limit: Int): List<MemoryItem>

   @Query("SELECT COUNT(*) FROM memory_items")
   suspend fun getMemoryCount(): Int
   ```

2. **`Repository.kt` 新增 3 个方法**：
   ```kotlin
   suspend fun getPinnedMemories(): List<MemoryItem> = memoryItemDao.getPinnedMemories()
   suspend fun getTopUnpinnedMemories(limit: Int): List<MemoryItem> = memoryItemDao.getTopUnpinnedMemories(limit)
   suspend fun getMemoryCount(): Int = memoryItemDao.getMemoryCount()
   ```

3. **`MemoryEngine.kt` — `selectRelevantMemories` 重构**：
   - 替换 `val allMemories = repository.getAllMemories()` 为：
     ```kotlin
     val pinned = repository.getPinnedMemories()
     val unpinned = repository.getTopUnpinnedMemories(limit * 2) // 加载 2 倍于需要的数量，给 embedding 排序留余地
     ```
   - 删除 `allMemories.filter { it.pinned }` 和 `allMemories.filter { !it.pinned }` 的行
   - 更新关联逻辑引用

4. **`MemoryEngine.kt` — `getTotalMemoryCount` 重构**：
   - 替换 `repository.getAllMemories().size` 为 `repository.getMemoryCount()`

---

### 不修改的范围

以下问题属于本次修复范围之外，仅记录：
- FTS5 索引同步问题（Issue 6）：SQLite 触发器已存在，功能正常
- 关联边去重（Issue 5）：需要数据库迁移增加唯一索引，影响面较大
- 其他架构优化（Issues 4-15）：按优先级后续处理

### 验证方式

1. 编译检查：`./gradlew assembleDebug` 通过
2. 记忆同步：发送消息后检查 `isMemorySyncing` 状态变化和记忆是否正确持久化
3. 记忆搜索：调用 `search_memory` 工具，验证返回结果与之前一致
4. 搜索质量：配置 embedding 模型后，验证语义搜索生效
5. 系统提示注入：验证记忆注入数量正确，不包含全表加载