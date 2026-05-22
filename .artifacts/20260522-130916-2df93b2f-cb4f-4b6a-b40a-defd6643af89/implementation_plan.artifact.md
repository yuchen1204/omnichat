# 修复聊天界面键盘弹起时的黑边间距问题

该计划旨在解决在聊天界面开启键盘时，输入框与键盘之间出现巨大黑边间距（双重缩进）的问题。

## 问题分析

通过分析代码和截图，确定该问题是由“双重填充 (Double Padding)”引起的：
1. **系统缩进**：由于 `MainActivity` 开启了 `enableEdgeToEdge()` 但未明确指定 `windowSoftInputMode`，在某些 Android 版本上系统可能会尝试调整窗口大小。
2. **Compose 填充**：`ChatView` 中使用了 `Modifier.imePadding()`。
3. **Scaffold 配置**：`MainScreen` 中的 `Scaffold` 设置了 `contentWindowInsets = WindowInsets(0)`，这禁用了 `Scaffold` 对系统栏（如导航栏）的默认处理，导致布局逻辑与标准不一致。

当键盘弹出时，如果系统调整了窗口大小，而 `ChatView` 又叠加了 `imePadding()`，就会导致输入框被推得过高，露出下方的背景色（黑边）。

## 拟议更改

### 1. 配置清单文件

在 `AndroidManifest.xml` 中为 `MainActivity` 添加 `windowSoftInputMode="adjustResize"`。这是 Compose 处理软键盘缩进的标准配置，确保系统通过 Insets 而不是物理调整窗口大小来通知键盘位置。

#### [AndroidManifest.xml](file:///E:/omnichat/app/src/main/AndroidManifest.xml)

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:label="@string/app_name"
    android:windowSoftInputMode="adjustResize"
    android:theme="@style/Theme.MyApplication">
```

---

### 2. 优化主屏幕布局

修改 `MainScreen.kt`，让 `Scaffold` 统一处理包括键盘在内的所有安全区域缩进（Safe Drawing Insets）。

#### [MainScreen.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/screens/MainScreen.kt)

- **Scaffold**: 移除 `contentWindowInsets = WindowInsets(0)`，改用 `WindowInsets.safeDrawing`。
- **Content Box**: 添加 `.consumeWindowInsets(paddingValues)`，确保嵌套组件不会重复计算缩进。
- **ChatView**: 移除冗余的 `.imePadding()`。

```kotlin
// MainScreen.kt 中的 Scaffold 部分
Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing, // 包含状态栏、导航栏和键盘
    topBar = { ... }
) { paddingValues ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .consumeWindowInsets(paddingValues) // 关键：消费掉已应用的缩进
    ) {
        when (currentTab) {
            "chat" -> ChatView(viewModel)
            "settings" -> SettingsView(viewModel, mcpViewModel)
        }
    }
}

// ChatView 部分
Column(
    modifier = Modifier
        .fillMaxSize()
        // .imePadding() // 移除此行，由 Scaffold 的 paddingValues 统一处理
        .background(MaterialTheme.colorScheme.background)
) { ... }
```

## 验证计划

### 自动化测试
- 运行现有的 UI 测试（如有）：`./gradlew connectedDebugAndroidTest`

### 手动验证
- **键盘联动测试**：点击聊天输入框，观察输入栏是否平滑贴合键盘顶部，且下方无黑边。
- **导航栏测试**：关闭键盘时，观察输入栏是否正确避开底部系统导航栏（由于 `safeDrawing` 包含导航栏，它应该能正确避开）。
- **多标签切换**：在“模型配置”或“长效记忆”标签下点击输入框，确认键盘处理逻辑一致。
