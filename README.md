# OmniChat 🤖💬

> 嵌入式 MCP Agent 运行时的 Android AI 助手 — 让 AI 真正掌控你的设备

<div align="center">

![Min SDK](https://img.shields.io/badge/Min%20SDK-26-green?logo=android)
![Target SDK](https://img.shields.io/badge/Target%20SDK-36-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-purple)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

## ✨ 核心特性

- **🧠 嵌入式 MCP 运行时** — 在 Android 设备上本地运行 Node.js / Python MCP 服务器，AI 可直接调用本地工具
- **🤖 多 Agent 工作区** — 编排模式的多 Agent 协作系统，支持团队管理、任务分配和 Agent 间通信
- **💾 跨会话记忆系统** — 15 分钟滚动摘要 + 长期记忆项（带置信度评分），AI 真正"记住"你的偏好
- **🎨 AI 可调整 UI** — AI 可通过 MCP 工具实时修改应用主题、颜色、字体、布局
- **📷 多媒体能力** — 相机拍照、图片选取、文档生成（docx/xlsx）、定时器
- **🔄 多模型支持** — OpenAI 兼容 API，支持 Gemini、OpenAI、DeepSeek、本地模型等
- **📡 SSE 流式输出** — 实时流式响应，打字机效果，支持 Thinking/Reasoning 模式
- **🔌 Hook 系统** — 可扩展的 Hook 机制，支持日志记录、文件权限控制等
- **🔐 自定义 Headers** — 每个模型提供商可配置自定义 HTTP 头

## 📱 应用架构

```
┌─────────────────────────────────────────────────────────────┐
│                     Compose UI Layer                         │
│  ┌──────────┐  ┌───────────┐  ┌────────────┐  ┌─────────┐  │
│  │ChatScreen│  │Workspace  │  │  Settings  │  │ Sidebar │  │
│  │          │  │  Screen   │  │   (5 tabs) │  │  Drawer │  │
│  └────┬─────┘  └─────┬─────┘  └─────┬──────┘  └────┬────┘  │
├───────┴───────────────┴──────────────┴──────────────┴───────┤
│                     ViewModel Layer                          │
│  ┌──────────────┐  ┌────────────────┐  ┌───────────────┐   │
│  │ ChatViewModel│  │WorkspaceVM     │  │SettingsVM     │   │
│  └──────┬───────┘  └───────┬────────┘  └───────┬───────┘   │
├─────────┴─────────────────┴───────────────────┴────────────┤
│                 Data / Repository Layer                      │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              AppRepository (Room DB v30 · 16 tables)   │  │
│  └───────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│                   MCP Runtime Layer                          │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐   │
│  │  NodeJsBridge │  │ PythonBridge  │  │ Remote HTTP   │   │
│  │   (JNI/TCP)   │  │  (JNI/dlopen) │  │  (SSE+Stream) │   │
│  └───────────────┘  └───────────────┘  └───────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                  Workspace (Multi-Agent)                     │
│  ┌───────────┐  ┌──────────┐  ┌──────────┐  ┌───────────┐  │
│  │TeamManager│  │TaskTools │  │AgentTool │  │AgentRunner│  │
│  └───────────┘  └──────────┘  └──────────┘  └───────────┘  │
└─────────────────────────────────────────────────────────────┘
```

单 Activity 架构（`MainActivity`），三个顶层视图：聊天、工作区、设置（含 5 个子标签页）。

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17+
- Android SDK 36
- CMake 3.22.1 + NDK 27.0.12077973

### 安装步骤

```bash
# 1. 克隆仓库
git clone https://github.com/yuchen1204/omnichat.git
cd omnichat

# 2. 构建并安装 Debug 版本
./gradlew assembleDebug
./gradlew installDebug
```

### 运行测试

```bash
# 单元测试
./gradlew testDebugUnitTest

# 单个测试类
./gradlew testDebugUnitTest --tests "com.example.YourTestClass"

# Android 仪器测试（需要设备/模拟器）
./gradlew connectedDebugAndroidTest

# 截图测试 (Roborazzi)
./gradlew verifyRoborazziDebug

# 重新生成 UI 文本键（通常自动运行）
./gradlew generateUiTextKeys
```

## 📂 项目结构

```
omnichat/
├── app/src/main/java/com/example/
│   ├── MainActivity.kt              # 入口 Activity
│   ├── data/                        # 数据层
│   │   ├── Entities.kt              # Room 实体定义 (16 个表)
│   │   ├── Daos.kt                  # DAO 接口
│   │   ├── AppDatabase.kt           # 数据库配置 (v30, 26 次迁移)
│   │   └── Repository.kt            # 数据仓库 (AppRepository)
│   ├── mcp/                         # MCP 运行时
│   │   ├── McpRuntimeManager.kt     # 运行时管理器
│   │   ├── McpScriptManager.kt      # 脚本部署管理
│   │   ├── McpPermissionManager.kt  # MCP 权限管理
│   │   ├── NodeJsBridge.kt          # Node.js JNI 桥接 (TCP)
│   │   ├── PythonBridge.kt          # Python 桥接 (stdin/stdout)
│   │   ├── PythonRuntime.kt         # Python 运行时 (dlopen)
│   │   └── BuiltinToolHandler.kt    # 内置工具处理
│   ├── hooks/                       # Hook 系统
│   │   ├── HookManager.kt           # Hook 管理器
│   │   ├── HookInterfaces.kt        # Hook 接口定义
│   │   ├── LoggingHooks.kt          # 日志 Hook
│   │   └── McpFilePermissionHook.kt # 文件权限 Hook
│   ├── network/
│   │   └── ApiClient.kt            # OpenAI 兼容 API 客户端 (SSE)
│   ├── workspace/                   # 多 Agent 工作区
│   │   ├── TeamManager.kt           # 团队管理
│   │   ├── AgentRunner.kt           # Agent 执行器
│   │   ├── TaskTools.kt             # 任务管理
│   │   ├── AgentTool.kt             # SubAgent 创建
│   │   ├── AgentDefinition.kt       # Agent 类型定义
│   │   └── SendMessageTool.kt       # Agent 间通信
│   ├── ui/
│   │   ├── screens/                 # Compose 界面
│   │   ├── viewmodel/               # ViewModel 层
│   │   ├── components/              # 可复用组件
│   │   └── theme/                   # Material 3 主题系统
│   └── TimerManager.kt             # 定时器管理
├── app/src/main/cpp/                # C++ JNI 代码
├── app/src/main/assets/
│   ├── node/                        # Node.js MCP 脚本
│   └── python/                      # Python stdlib
└── scripts/                         # 工具脚本
```

## 🛠️ 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.2.10, C++17 (JNI) |
| UI | Jetpack Compose (Material 3) |
| 数据库 | Room v2.7.0 (v30, 16 个实体, 26 次迁移) |
| 网络 | OkHttp + SSE |
| 构建 | AGP 9.1.1, KSP 2.2.10-2.0.2, CMake 3.22.1 |
| 原生运行时 | nodejs-mobile (libnode.so), Python 3.14 (dlopen) |
| 图片加载 | Coil 2.7.0 |
| 相机 | CameraX 1.5.0 |
| Markdown | compose-markdown 0.7.2 |
| 测试 | JUnit, Robolectric, Roborazzi 1.59.0 |
| CI/CD | GitHub Actions (自动 Release 构建) |
| ABI | arm64-v8a, x86_64 |

## 📊 数据库表结构 (v30 · 16 个实体)

| 实体 | 表名 | 用途 |
|------|------|------|
| `ModelConfig` | `model_configs` | API 提供商 / 模型配置 |
| `Session` | `sessions` | 聊天会话 |
| `Message` | `messages` | 聊天消息 (user/assistant/tool) |
| `MemoryItem` | `memory_items` | 跨会话记忆 (带置信度评分) |
| `PromptTemplate` | `prompt_templates` | 系统提示词模板 |
| `FetchedModel` | `fetched_models` | 模型列表缓存 |
| `SessionSummary` | `session_summaries` | 15 分钟滚动会话摘要 |
| `McpServer` | `mcp_servers` | MCP 服务器配置 |
| `UISettings` | `ui_settings` | AI 可调整的全局 UI 设置 |
| `ColorSchemePreset` | `color_scheme_presets` | 颜色方案快照 (最多 5 个) |
| `AgentPreset` | `agent_presets` | Agent 预设配置 |
| `WorkspaceSession` | `workspace_sessions` | 工作区会话 |
| `AgentInstance` | `agent_instances` | 运行中的 Agent 实例 |
| `WorkspaceMessage` | `workspace_messages` | 工作区消息 |
| `TeamTask` | `team_tasks` | 团队任务 (状态/阻塞管理) |
| `McpFilePermission` | `mcp_file_permissions` | MCP 文件访问权限 |

## 🔧 MCP 工具扩展

### 内置工具 (34 个)

| 工具 | 说明 |
|------|------|
| 文件系统 | 读写本地文件和目录管理 |
| 网络请求 | HTTP/HTTPS 抓取 |
| UI 定制 | 动态调整主题颜色、圆角、字体、间距 |
| 颜色方案 | 保存 / 加载 / 切换主题预设 |
| UI 文案 | 调整界面文本内容 |
| 文档生成 | 生成 Word (.docx) 和 Excel (.xlsx) 文件 |
| 相机拍照 | 调用设备相机拍照并保存 |
| 图片选取 | 从相册选取图片 |
| 定时器 | 创建和管理倒计时 / 秒表 |
| Agent 管理 | 创建/管理多 Agent 工作区 |
| 任务管理 | 任务 CRUD、状态跟踪、阻塞依赖 |
| 记忆搜索 | 搜索跨会话记忆 |

### 添加自定义 MCP 服务器

1. 在 **设置 → MCP 工具** 标签页点击 **添加**
2. 配置服务器信息：
   - **Node.js**: 指定 `.js` 文件路径
   - **Python**: 指定 `.py` 文件路径
   - **远程 HTTP**: 填入服务器 URL（支持 SSE 2024-11-05 和 Streamable HTTP 2025-03-26）
3. 支持标准 `mcpServers` JSON 格式导入

> ⚠️ Node.js 在每个进程中只能启动一次（nodejs-mobile 限制），多个服务器会合并到一个入口脚本中。

## 🤖 多 Agent 工作区

编排模式的多 Agent 协作系统：

- **TeamManager** — 管理队友和整体工作流
- **AgentTool** — 创建隔离的 SubAgent 执行任务
- **TaskTools** — 任务 CRUD、状态跟踪、阻塞依赖
- **AgentRunner** — Agent 执行循环（含 MCP 工具调用）
- **SendMessageTool** — Agent 间异步消息通信
- **TeammateContext** — 协程上下文隔离

## 📦 Release 构建

推送到 `Release-V*.*` 标签会自动触发 GitHub Actions 构建 Release APK：

```bash
git tag Release-V0.5
git push origin main --tags
```

产物自动发布到 [GitHub Releases](https://github.com/yuchen1204/omnichat/releases)。

## 🤝 贡献指南

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 代码规范

- Room 迁移规则：**只加列 / 加表，绝不删数据**
- 中文 UI 字符串直接硬编码在 Compose 中
- AI 可调整的字符串使用 `uiText("namespace.key", "默认中文")` 模式
- 使用 `CompositionLocal` 传递主题和配置：`LocalUISettings`, `LocalCustomColors`, `LocalUiStrings`

## 📄 许可证

本项目采用 MIT 许可证 — 详见 [LICENSE](LICENSE) 文件

---

<div align="center">

**用 ❤️ 和 Kotlin 构建**

[报告问题](https://github.com/yuchen1204/omnichat/issues) · [功能请求](https://github.com/yuchen1204/omnichat/issues/new)

</div>
