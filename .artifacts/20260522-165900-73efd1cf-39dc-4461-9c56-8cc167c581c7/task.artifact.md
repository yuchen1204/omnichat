# 任务管理

- [x] 研究 UI 主题和设置 (Researching UI Theming and Settings)
- [x] 实现数据层变更 (Implement Data Layer Changes)
    - [x] 在 `Entities.kt` 中添加 `UISettings`
    - [x] 在 `Daos.kt` 中添加 `UISettingsDao`
    - [x] 在 `AppDatabase.kt` 中添加迁移逻辑
    - [x] 在 `Repository.kt` 中添加相关方法
- [x] 实现 MCP 工具 `adjust_ui` (Implement MCP Tool `adjust_ui`)
    - [x] 在 `McpRuntimeManager.kt` 中定义工具
    - [x] 实现工具处理逻辑并添加约束
- [x] 更新 UI 主题 (Update UI Theme)
    - [x] 在 `Theme.kt` 中支持自定义配色 and 布局
    - [x] 创建 `SettingsViewModel.kt`
    - [x] 在 `MainActivity.kt` 中应用设置
- [x] 验证功能 (Verification)
