# Markdown 渲染优化方案

本方案旨在提升 OmniChat 的 Markdown 渲染性能和视觉体验，解决流式输出过程中的“抽搐”感，并增强交互功能。

## 核心变更

1. **分块渲染 (Chunked Rendering)**：将流式文本切割为已锁定的稳定块和活跃的流动块，大幅降低长文本渲染开销。
2. **流式性能优化**：将 UI 更新节流从 80ms 降低至 50ms，配合分块渲染，体感更流畅。
3. **视觉与交互增强**：
    - 在消息气泡中增加显式的“复制”功能。
    - 优化代码块的配色方案，使其在深色/浅色模式下更清晰。
    - 深度思考 (Thinking) 过程也支持 Markdown 渲染和流式分块。

---

## Proposed Changes

### 1. 核心渲染组件

#### [MarkdownChunkParser.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/components/MarkdownChunkParser.kt)

- **[NEW]** 实现 Markdown 边界检测算法，支持代码块、表格、列表、标题和段落的自动切割。
- 确保只有“已闭合”或“已完成”的内容才会被锁定，防止未完成的语法导致渲染异常。

#### [ChunkedStreamingText.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/components/ChunkedStreamingText.kt)

- **[NEW]** 基于 `MarkdownChunkParser` 的分块渲染容器。
- 对已锁定的块使用 `remember` 缓存，避免重复解析。
- 对活跃块进行智能判断：若是复杂语法（如未闭合的代码块或表格），临时使用纯文本渲染以保证流畅度。

---

### 2. UI 逻辑与交互

#### [MainScreen.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/screens/MainScreen.kt)

- **StreamingBubble**: 接入 `ChunkedStreamingText`，移除旧有的简易表格检测。
- **BubbleMessage**: 为助手消息增加“复制”功能按钮，并优化 Markdown 样式配置。
- **ThinkingProcessPanel**: 深度思考内容现在支持 Markdown 渲染。
- **Icons**: 引入 `ContentCopy` 图标。

#### [ChatViewModel.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt)

- 将 `startAssistantResponse` 中的 UI 更新节流从 80ms 优化为 50ms。

---

## Verification Plan

### Automated Tests
- 运行 `./gradlew testDebugUnitTest` 确保不破坏现有逻辑（如果有相关测试）。

### Manual Verification
1. **流式输出测试**：发送一个包含长表格、多个代码块和长列表的请求，观察输出过程是否依然流畅，是否有“抽搐”现象。
2. **复制功能验证**：长按消息或点击复制按钮，验证剪贴板内容是否正确。
3. **深浅色模式**：切换系统主题，验证代码块背景和文字颜色是否对比度适中。
4. **思考模式渲染**：验证 AI 思考过程中的 Markdown（如加粗、代码）是否能正确显示。
