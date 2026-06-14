# Design: 平板横屏适配 + 模型切换 Bug 修复

## Overview

两个独立任务：
1. 修复模型切换需要点两次的 bug
2. 全面响应式布局适配平板横屏使用

---

## Task 1: 模型切换 Bug 修复

### 问题

用户在聊天界面切换模型时，同一 provider 内切换需要点两次，切换其他 provider 也需要点两次。

### 根因

`ChatViewModel.setSessionOverrideModel()` 调用 `repository.setDefaultProvider(provider.id)`，而该 DAO 方法是 **toggle 语义**：

```kotlin
// ModelConfigDao.setDefaultProvider
suspend fun setDefaultProvider(id: Long) {
    val c = getConfigById(id) ?: return
    val wasDefault = c.isDefaultProvider
    clearDefaultProvider()
    if (!wasDefault) {
        updateConfig(c.copy(isDefaultProvider = true))
    }
}
```

当 provider 已经是 default 时（`wasDefault = true`），clear 后不会重新设置，导致第一次点击清除 default，第二次点击才重新设置。

### 修复方案

在 `ChatViewModel.setSessionOverrideModel()` 中，不再调用 `setDefaultProvider`（toggle），而是直接用 `updateConfig` 同时设置 `selectedModelId` 和 `isDefaultProvider`。

同时将两个 DB 操作（`updateConfig` + `setDefaultProvider` 的调用）包装为 `@Transaction`，防止 Flow 发射中间状态。

### 涉及文件

- `app/src/main/java/com/omnichat/ui/viewmodel/ChatViewModel.kt` — 修改 `setSessionOverrideModel`
- `app/src/main/java/com/omnichat/data/Daos.kt` — 可能需要新增 `@Transaction` 方法
- `app/src/main/java/com/omnichat/data/Repository.kt` — 暴露事务方法

---

## Task 2: 平板横屏全面响应式适配

### 目标

- 窄屏（手机竖屏）：保持现有抽屉式侧边栏
- 宽屏（平板横屏）：侧边栏常驻，聊天/设置内容限制最大宽度并居中

### 架构

1. **添加依赖**：`androidx.compose.material3:material3-window-size-class`
2. **屏幕分类**：`MainActivity` 计算 `WindowSizeClass`，通过 `CompositionLocal` 向下传递
3. **布局切换**：`MainScreen` 根据 `WindowWidthSizeClass` 切换抽屉模式

### 具体改动

#### ① MainActivity + CompositionLocal 层

```kotlin
// MainActivity.kt
val windowSizeClass = calculateWindowSizeClass(this)
setContent {
    MyApplicationTheme {
        CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
            MainScreen(...)
        }
    }
}
```

新增 `LocalWindowSizeClass` CompositionLocal（放在 theme 包或单独文件）。

#### ② MainScreen 侧边栏切换

- **Compact / Medium** → `ModalNavigationDrawer`（当前行为，抽屉滑出）
- **Expanded** → `PermanentNavigationDrawer`（侧边栏常驻左侧）

`PermanentNavigationDrawer` 的 `drawerContent` 放 `SessionSidebarPanel`，`content` 放当前的 `Scaffold`。抽屉宽度 280dp 保持不变。

#### ③ 聊天区域最大宽度约束

`ChatScreen` 外层 `Column` 加 `widthIn(max = 720.dp)` + `Alignment.CenterHorizontally`，让聊天气泡和输入框在宽屏上居中且不会拉伸到全屏宽度。`LazyColumn` 和底部发送区域都继承这个约束。

#### ④ 设置页面适配

`SettingsView` 的内容区域同样加 `widthIn(max = 720.dp)` + 居中。`ScrollableTabRow` 在宽屏上自然伸缩即可。

#### ⑤ 对话框宽度约束

所有 `fillMaxWidth()` 的对话框改为 `widthIn(max = 560.dp)` + `fillMaxWidth()`，确保在宽屏上有合理宽度，在窄屏上仍能撑满。

#### ⑥ 辅助 UI 调整

- `EmptyChatGreeting` 卡片加最大宽度
- 图片预览保持 `Row` 布局（宽度受限后自然不会太挤）

### 涉及文件

| 文件 | 改动 |
|------|------|
| `app/build.gradle.kts` | 添加 material3-window-size-class 依赖 |
| `app/src/main/java/com/omnichat/MainActivity.kt` | 计算 WindowSizeClass，提供 CompositionLocal |
| 新增 `WindowSizeLocal.kt` | `LocalWindowSizeClass` 定义 |
| `app/src/main/java/com/omnichat/ui/screens/MainScreen.kt` | 根据 sizeClass 切换 Modal/PermanentDrawer；对话框宽度约束 |
| `app/src/main/java/com/omnichat/ui/screens/ChatScreen.kt` | 聊天内容 maxWidth 约束 + 居中 |
| `app/src/main/java/com/omnichat/ui/screens/SessionSidebarPanel.kt` | 无变化（宽度 280dp 不变） |
