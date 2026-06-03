[English](README.md)

# OmniChat 🤖💬

> 基于 MCP 运行时的 Android AI 助手 -- 让 AI 真正掌控你的设备

<div align="center">

![Min SDK](https://img.shields.io/badge/Min%20SDK-26-green?logo=android)
![Target SDK](https://img.shields.io/badge/Target%20SDK-36-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-purple)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

## ✨ 核心特性

- **🔌 MCP 运行时** — 通过 HTTP/HTTPS 连接远程 MCP 服务器，AI 可直接调用外部工具
- **🤖 subAgent 系统** — 将任务委派给专门的 AI 代理（研究员、编码员、审查员、测试员）异步执行
- **💾 跨会话记忆系统** — 15 分钟滚动摘要 + 长期记忆项（带置信度评分），AI 真正"记住"你的偏好
- **🎨 AI 可调整 UI** — Apple 风格色彩方案，AI 可通过 MCP 工具实时修改应用主题、颜色、字体、布局
- **📷 多媒体能力** — 相机拍照、图片选取、文档生成（docx/xlsx）、AlarmManager 定时器（支持重复任务）
- **🔄 多模型支持** — OpenAI 兼容 API，支持 Gemini、OpenAI、DeepSeek、本地模型等
- **📡 SSE 流式输出** — 实时流式响应，打字机效果，支持 Thinking/Reasoning 模式
- **🪝 Hook 系统** — 可扩展的 Hook 机制，支持日志记录、文件权限控制等
- **🔐 自定义 Headers** — 每个模型提供商可配置自定义 HTTP 头

## 📱 应用架构

```
┌─────────────────────────────────────────────────────────────┐
│                     Compose UI Layer                         │
│  ┌──────────┐  ┌────────────┐  ┌─────────┐                 │
│  │ChatScreen│  │  Settings  │  │ Sidebar │                 │
│  │          │  │   (4 tabs) │  │  Drawer │                 │
│  └────┬─────┘  └─────┬──────┘  └────┬────┘                 │
├───────┴───────────────┴──────────────┴──────────────────────┤
│                     ViewModel Layer                          │
│  ┌──────────────┐  ┌───────────────┐                       │
│  │ ChatViewModel│  │SettingsVM     │                       │
│  └──────┬───────┘  └───────┬───────┘                       │
├─────────┴─────────────────┴────────────────────────────────┤
│                 Data / Repository Layer                      │
│  ┌───────────────────────────────────────────────────────┐  │
│  │           AppRepository (Room DB v39)                  │  │
│  └───────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│                   MCP Runtime Layer                          │
│  ┌───────────────┐  ┌──────────────────┐                   │
│  │  Remote HTTP  │  │  AgentExecutor   │                   │
│  │  (SSE+Stream) │  │  (subAgent)      │                   │
│  └───────────────┘  └──────────────────┘                   │
└─────────────────────────────────────────────────────────────┘
```

单 Activity 架构（`MainActivity`），两个顶层视图：聊天、设置（含 4 个子标签页）。

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17+
- Android SDK 36

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
./gradlew testDebugUnitTest --tests "com.omnichat.YourTestClass"

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
├── app/src/main/java/com/omnichat/
│   ├── MainActivity.kt              # 入口 Activity
│   ├── data/                        # 数据层
│   │   ├── Entities.kt              # Room 实体定义
│   │   ├── Daos.kt                  # DAO 接口
│   │   ├── AppDatabase.kt           # 数据库配置 (v39)
│   │   └── Repository.kt            # 数据仓库 (AppRepository)
│   ├── agent/                       # subAgent 系统
│   │   ├── AgentExecutor.kt         # 任务执行引擎
│   │   └── AgentPrompts.kt          # 系统提示模板
│   ├── mcp/                         # MCP 运行时
│   │   ├── McpRuntimeManager.kt     # 运行时管理器
│   │   ├── McpPermissionManager.kt  # MCP 权限管理
│   │   └── BuiltinToolHandler.kt    # 内置工具处理
│   ├── hooks/                       # Hook 系统
│   │   ├── HookManager.kt           # Hook 管理器
│   │   ├── HookInterfaces.kt        # Hook 接口定义
│   │   ├── LoggingHooks.kt          # 日志 Hook
│   │   └── McpFilePermissionHook.kt # 文件权限 Hook
│   ├── network/
│   │   └── ApiClient.kt            # OpenAI 兼容 API 客户端 (SSE)
│   ├── memory/                      # 记忆引擎
│   │   ├── MemoryEngine.kt          # 跨会话记忆引擎
│   │   └── MemoryTokenizer.kt       # 记忆搜索分词器
│   ├── ui/
│   │   ├── screens/                 # Compose 界面
│   │   ├── viewmodel/               # ViewModel 层
│   │   ├── components/              # 可复用组件
│   │   └── theme/                   # Material 3 主题系统
│   └── TimerManager.kt             # 定时器管理 (AlarmManager)
├── app/src/main/assets/
│   └── node/                        # Node.js MCP 脚本
└── scripts/                         # 工具脚本
```

## 🛠️ 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.2.10 |
| UI | Jetpack Compose (Material 3) |
| 数据库 | Room v2.7.0 (v39) |
| 网络 | OkHttp + SSE + Retrofit 2.12.0 |
| 序列化 | Moshi 1.15.2 |
| Firebase | Firebase BOM 34.12.0 |
| 构建 | AGP 9.1.1, KSP 2.2.10-2.0.2 |
| 文档生成 | Apache POI 5.5.1 |
| 权限管理 | Accompanist Permissions 0.37.3 |
| 图片加载 | Coil 2.7.0 |
| 相机 | CameraX 1.5.0 |
| Markdown | compose-markdown 0.7.2 |
| 测试 | JUnit, Robolectric, Roborazzi 1.59.0 |
| CI/CD | GitHub Actions (自动 Release 构建) |

## 🔧 MCP 工具扩展

### 内置工具

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
| 记忆搜索 | 搜索跨会话记忆 |
| **subAgent** | **将任务委派给专门的 AI 代理（通用、研究员、编码员、审查员、测试员）** |

### subAgent 系统

OmniChat 支持将任务委派给专门的 AI 代理异步执行：

- **delegate_task** — 将任务分配给指定类型的代理
- **check_task_status** — 查询任务执行状态
- **list_agent_tasks** — 列出当前会话所有任务

**定时器等待机制：** 主 AI 代理委派任务后，使用 `create_timer` 的 `task_id` 参数设置提醒（如 60 秒后），而非立即检查状态。定时器触发时再查询任务进度；若子代理在此之前完成，定时器自动取消。这避免了主代理重复执行子代理的工作。

**支持的代理类型：**
| 类型 | 用途 |
|------|------|
| general | 通用任务 |
| researcher | 信息搜索与分析 |
| coder | 代码编写与修改 |
| reviewer | 代码审查与质量检查 |
| tester | 测试用例生成 |

### 添加自定义 MCP 服务器

1. 在 **设置 → MCP 工具** 标签页点击 **添加**
2. 配置服务器信息：
   - **远程 HTTP**: 填入服务器 URL（支持 SSE 2024-11-05 和 Streamable HTTP 2025-03-26）
3. 支持标准 `mcpServers` JSON 格式导入

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
- UI 字符串使用 Android `strings.xml` 进行国际化（英文默认，中文 `values-zh-rCN`）
- AI 可调整的装饰性字符串使用 `uiText("key", "English default")` 模式
- 使用 `CompositionLocal` 传递主题和配置：`LocalUISettings`, `LocalCustomColors`, `LocalUiStrings`

## 📄 许可证

本项目采用 MIT 许可证 — 详见 [LICENSE](LICENSE) 文件

---

<div align="center">

**用 ❤️ 和 Kotlin 构建**

[报告问题](https://github.com/yuchen1204/omnichat/issues) · [功能请求](https://github.com/yuchen1204/omnichat/issues/new)

</div>
