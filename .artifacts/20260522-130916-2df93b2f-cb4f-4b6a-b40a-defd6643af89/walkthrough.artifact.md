# 聊天界面键盘缩进修复说明

成功解决了聊天界面在键盘弹出时出现的双重缩进（黑边间距）问题。

## 变更内容

### 1. AndroidManifest.xml 配置
在 [AndroidManifest.xml](file:///E:/omnichat/app/src/main/AndroidManifest.xml) 中为 `MainActivity` 添加了 `android:windowSoftInputMode="adjustResize"`。
- **作用**：确保 Android 系统以 `Insets` 方式通知 Compose 键盘高度，而不是物理压缩 Activity 的窗口。这是处理键盘缩进的标准推荐做法。

### 2. Scaffold 布局优化
修改了 [MainScreen.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/screens/MainScreen.kt) 中的 `Scaffold`：
- 将 `contentWindowInsets` 从 `WindowInsets(0)` 改为 `WindowInsets.safeDrawing`。
- **作用**：让 `Scaffold` 统一感知状态栏、导航栏以及**键盘 (IME)** 的缩进。

### 3. 消费缩进与移除冗余
- 在 `MainScreen` 的内容容器 `Box` 中添加了 `.consumeWindowInsets(paddingValues)`，防止嵌套布局重复计算。
- 移除了 `ChatView` 内部冗余的 `.imePadding()`。
- **作用**：消除了“双重填充”现象，使输入框能够平滑地紧贴键盘顶部。

## 验证结果

### 编译测试
- 运行 `./gradlew app:assembleDebug` 成功。

### 预期表现
- **键盘弹出**：输入框平滑上移，底部无黑色背景外露。
- **导航栏兼容**：即使在非全面屏模式下，输入框也能正确避开系统导航栏。
- **全屏体验**：顶部状态栏颜色保持一致，布局不再受窗口物理缩放的影响。
