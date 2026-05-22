# 实现 AI 调整配色和布局的功能

给 AI 调整配色和布局的权限，允许 AI 通过内置 MCP 接口修改应用界面风格，同时实施安全性限制。

## 用户审核要求

- **布局限制**：目前计划限制圆角在 0-32dp 之间，间距倍数在 0.5-2.0 之间。是否需要更精细的布局调整权限？
- **颜色限制**：AI 可以调整主色、次色等 5 种核心颜色。如果不合规的颜色（如对比度极低）被设置，系统仅做简单的 HEX 格式校验。

## 拟议变更

### 数据层 (Room Database)

添加 `UISettings` 实体以持久化 AI 的调整。

#### [Entities.kt](file:///E:/omnichat/app/src/main/java/com/example/data/Entities.kt)
- 新增 `UISettings` 实体类，包含颜色字段（HEX 字符串）、圆角大小、间距倍数等。

#### [Daos.kt](file:///E:/omnichat/app/src/main/java/com/example/data/Daos.kt)
- 新增 `UISettingsDao` 接口。

#### [AppDatabase.kt](file:///E:/omnichat/app/src/main/java/com/example/data/AppDatabase.kt)
- 注册新实体，更新数据库版本到 13，并添加 `MIGRATION_12_13`。

#### [Repository.kt](file:///E:/omnichat/app/src/main/java/com/example/data/Repository.kt)
- 暴露 `UISettings` 的读写方法。

---

### MCP 运行时 (Builtin Tool)

在内置工具中增加 `adjust_ui` 接口。

#### [McpRuntimeManager.kt](file:///E:/omnichat/app/src/main/java/com/example/mcp/McpRuntimeManager.kt)
- 在 `builtinTools` 中添加 `adjust_ui` 工具定义。
- 在 `handleBuiltinTool` 中实现该工具的逻辑：解析参数，校验范围（Constraints），并更新数据库。

---

### UI 层 (Compose Theme)

使主题能够响应 `UISettings` 的变化。

#### [Theme.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/theme/Theme.kt)
- 修改 `MyApplicationTheme`，使其接收 `UISettings?`。
- 将 `UISettings` 的颜色应用到 `ColorScheme`。
- 使用 `UISettings` 的圆角大小更新 `MaterialTheme` 的 `Shapes`。
- (可选) 提供 `LocalUISettings` CompositionLocal 供组件使用。

#### [NEW] [SettingsViewModel.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/viewmodel/SettingsViewModel.kt)
- 创建一个新的 ViewModel 用于观察 `UISettings` Flow。

#### [MainActivity.kt](file:///E:/omnichat/app/src/main/java/com/example/MainActivity.kt)
- 在 `setContent` 中实例化 `SettingsViewModel` 并将其状态传递给 `MyApplicationTheme`。

## 验证计划

### 自动化测试
- 运行 `AppDatabaseTest` (如果存在) 验证迁移。
- 编写 `UISettingsRepositoryTest` 验证数据的读写。

### 手动验证
- **功能测试**：在聊天窗口向 AI 发送指令（如“把主题色调成嫩绿色，圆角调大一点”），确认 AI 调用了 `adjust_ui` 工具且界面发生了相应变化。
- **限制测试**：让 AI 设置非法的圆角（如 500dp）或无效的颜色，确认工具返回错误或自动修正到允许范围内。
- **重置测试**：让 AI 重置 UI，确认恢复默认配置。
