# Markdown 渲染优化及体验增强

本次更新大幅优化了流式输出过程中的渲染性能，解决了界面“抽搐”感，并增强了消息交互功能。

## 核心改进

### 1. 流式分块渲染 (Chunked Rendering)
引入了分块渲染机制，将流式生成的文本实时切割为两部分：
- **已锁定块 (Locked Chunks)**：内容已稳定（如已闭合的代码块、完整的段落），渲染后会被缓存，不再参与后续的重绘。
- **活跃块 (Active Chunk)**：正在生成的末尾部分，仅对该部分进行高频重绘。

**带来的收益**：
- **性能飞跃**：随着对话增长，渲染开销不再线性增加，长文本输出依然丝滑。
- **消除闪烁**：解决了未闭合的代码块 (```) 或表格 (|) 在流式生成时由于 Markdown 解析器频繁尝试闭合而导致的界面跳变（抽搐）。

### 2. 流式体验微调
- **节流优化**：将 `ChatViewModel` 的 UI 更新频率从 80ms 优化至 **50ms**，使打字机效果更紧凑连贯。
- **智能回退**：在活跃块遇到复杂 Markdown 语法（如未闭合表格）时，自动回退到纯文本渲染，确保生成过程不卡顿。

### 3. 视觉与交互增强
- **代码块样式优化**：重新设计了代码块在深色/浅色模式下的背景和文字颜色。
- **深度思考渲染**：AI 的思考过程 (Thinking Process) 现在也支持 Markdown 渲染（加粗、代码等），并同样享受分块渲染优化。
- **复制功能**：在消息长按菜单中增加了显式的“复制内容”操作，并引入了 `material-icons-extended` 图标库以提供更好的视觉引导。

## 变更文件概览

- [MarkdownChunkParser.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/components/MarkdownChunkParser.kt): **[NEW]** 核心分块逻辑，负责识别 Markdown 块边界。
- [ChunkedStreamingText.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/components/ChunkedStreamingText.kt): **[NEW]** 高性能流式渲染组件。
- [MainScreen.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/screens/MainScreen.kt): 集成分块渲染组件，实现复制功能及样式调优。
- [ChatViewModel.kt](file:///E:/omnichat/app/src/main/java/com/example/ui/viewmodel/ChatViewModel.kt): 优化流式更新节流参数。
- [build.gradle.kts](file:///E:/omnichat/app/build.gradle.kts): 添加 `material-icons-extended` 依赖。

## 验证总结
- **编译验证**：`app:assembleDebug` 已通过。
- **样式验证**：代码块背景在深浅模式下均有良好的对比度。
- **逻辑验证**：分块检测已处理代码块闭合、表格结束、列表项及双换行。
