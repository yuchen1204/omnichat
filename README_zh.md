[English](README.md)

# OmniChat

> 基于 MCP 运行时的 Android AI 助手 -- 让 AI 真正掌控你的设备

<div align="center">

![Min SDK](https://img.shields.io/badge/Min%20SDK-26-green?logo=android)
![Target SDK](https://img.shields.io/badge/Target%20SDK-36-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-purple)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

## 核心特性

- **MCP 运行时** -- 通过 HTTP/HTTPS 连接远程 MCP 服务器，支持 SSE (2024-11-05) 和 Streamable HTTP (2025-03-26) 两种协议；34 个内置工具涵盖文件操作、UI 主题、记忆系统、文档生成、定时器等
- **多智能体工作区** -- 编排器模式，`TeamManager` + `AgentRunner` + `AgentTool` 生成隔离的 SubAgent；支持智能体间消息传递、共享任务看板、Agent 预设、每个 Agent 独立模型配置
- **跨会话记忆系统** -- 双层架构：15 分钟滚动会话摘要 + 长期记忆项（带置信度评分）；支持向量语义搜索、FTS 全文搜索、记忆关联图（BFS 遍历）、标签系统、时间提醒
- **AI 可调整 UI** -- 完整 Material 3 调色板（30 个颜色字段）、布局参数（圆角、间距）、字体设置（缩放、字体族）、颜色方案预设（最多 5 个）、约 130 个 AI 可编辑 UI 文本标签
- **云备份** -- Cloudflare Worker 后端 + TOTP 认证 + R2 存储；支持 `.omniconfig`、`.omnidb`、`.omnifile` 格式，可选分区备份和定时备份
- **多媒体能力** -- 多图视觉理解（相机拍照 + 相册选取）、文档生成（PDF/Excel/Word/PowerPoint）、AlarmManager 定时器（支持重复任务）
- **多模型支持** -- OpenAI 兼容 API，支持 Gemini、OpenAI、DeepSeek、本地模型；每个提供商可配置自定义 HTTP 头、Embedding 模型、Thinking/Reasoning 模式（含 budget_tokens）
- **SSE 流式输出** -- 实时流式响应，打字机效果，分块渲染优化，前台服务防止进程被杀，特殊 chunk 前缀处理工具调用和重试
- **版本检查** -- 基于 GitHub Tag 的更新检查，语义版本号比较

## 应用架构

```
┌──────────────────────────────────────────────────────────────────┐
│                       Compose UI Layer                           │
│  ┌──────────┐  ┌───────────┐  ┌────────────┐  ┌──────────────┐ │
│  │ChatScreen│  │ Workspace │  │  Settings  │  │    Sidebar   │ │
│  │          │  │  Screen   │  │  (5 tabs)  │  │    Drawer    │ │
│  └────┬─────┘  └─────┬─────┘  └─────┬──────┘  └──────┬───────┘ │
├───────┴───────────────┴──────────────┴────────────────┴─────────┤
│                       ViewModel Layer                            │
│  ┌──────────────┐  ┌─────────────────┐                           │
│  │ ChatViewModel│  │SettingsViewModel│                           │
│  └──────┬───────┘  └───────┬─────────┘                           │
├─────────┴──────────────────┴───────────────────────┴────────────┤
│                   Data / Repository Layer                        │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │         AppRepository (Room DB v55, 16 entities)           │ │
│  └────────────────────────────────────────────────────────────┘ │
├────────────────────────────────────────────────────────────────┤
│                    MCP Runtime Layer                             │
│  ┌───────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │  Remote HTTP  │  │  SubAgent    │  │  Cloud Backup        │ │
│  │  (SSE+Stream) │  │  Executor    │  │  (CF Worker + R2)    │ │
│  └───────────────┘  └──────────────┘  └──────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

单 Activity 架构（`MainActivity`），三个顶层视图：聊天、工作区、设置（含 5 个标签页：模型配置、MCP工具、长效记忆、Agent 预置、数据管理）。

## 快速开始

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 21+
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

## 项目结构

```
omnichat/
	├── app/src/main/java/com/omnichat/
	│   ├── MainActivity.kt              # 入口 Activity
	│   ├── MyApplication.kt             # Application 类（备份调度）
	│   ├── StreamingForegroundService.kt # LLM 流式响应前台服务
	│   ├── data/                        # 数据层
	│   │   ├── Entities.kt              # 16 个 Room 实体定义
	│   │   ├── Daos.kt                  # DAO 接口
	│   │   ├── AppDatabase.kt           # 数据库配置 (v55, 94 次迁移)
	│   │   ├── Repository.kt            # 数据仓库 (AppRepository)
	│   │   └── OmnifileFormat.kt        # 二进制导出格式 (.omnifile)
	│   ├── network/
	│   │   └── ApiClient.kt            # OpenAI 兼容 API 客户端 (SSE、视觉、Embedding)
	│   ├── mcp/                         # MCP 运行时
	│   │   ├── McpRuntimeManager.kt     # Remote MCP transport and catalog aggregation
	│   │   ├── McpPermissionManager.kt  # MCP 文件权限管理
	│   │   ├── AskUserManager.kt        # ask_user 工具挂起/恢复
	│   │   ├── TimerManager.kt          # 双轨定时器（AlarmManager + Handler）
	│   │   ├── TimerStorage.kt          # 定时器磁盘持久化
	│   │   ├── ToolSchemaDsl.kt         # JSON Schema DSL 工具定义
	│   │   ├── UiFieldRegistry.kt       # AI 可调整 UI 字段元数据
	│   │   └── McpViewModel.kt          # MCP 配置 ViewModel
	│   ├── memory/                      # 记忆引擎
	│   │   ├── MemoryEngine.kt          # 跨会话记忆（关联图、向量搜索、FTS）
	│   │   └── MemoryTokenizer.kt       # CJK bigram + 英文分词器
	│   ├── agent/                       # 多智能体系统
	│   │   ├── SubAgent.kt              # SubAgent 生命周期
	│   │   ├── SubAgentApproval.kt      # 审批系统（文件操作、任务委派）
	│   │   ├── SubAgentApprovalManager.kt
	│   │   ├── SubAgentEventBus.kt      # 智能体通信事件总线
	│   │   ├── WorkflowEngine.kt        # 工作流执行引擎
	│   │   ├── WorkflowEventBus.kt      # 工作流事件总线
	│   │   ├── WorkflowTemplates.kt     # 工作流定义
	│   │   ├── AgentMessage.kt          # 智能体消息模型
	│   │   └── AgentPrompts.kt          # 系统提示模板
	│   ├── cloud/                       # 云备份
	│   │   ├── CloudBackupApi.kt        # Retrofit API 接口
	│   │   ├── CloudBackupRepository.kt # 认证 + API 客户端管理
	│   │   ├── CloudBackupManager.kt    # 备份/恢复操作
	│   │   ├── CloudBackupViewModel.kt  # 云备份 ViewModel
	│   │   └── CloudBackupDiagnosticViewModel.kt
	│   ├── update/
	│   │   └── UpdateChecker.kt         # 基于 GitHub Tag 的版本检查
	│   ├── worker/
	│   │   └── CloudBackupWorker.kt     # WorkManager 定时备份
	│   ├── util/
	│   │   ├── DocumentParser.kt        # 文档解析 (PDF, DOCX 等)
	│   │   └── SessionLogExporter.kt    # 聊天日志导出
	│   └── ui/
	│       ├── screens/                 # Compose 界面
	│       ├── viewmodel/               # ViewModel 层
	│       ├── components/              # 可复用组件
	│       ├── theme/                   # Material 3 主题系统
	│       └── performance/             # 刷新率 & 动画优化
	├── cloudflare-worker/               # 云备份后端（CF Workers + R2 + KV）
	└── docs/                            # 架构与设计文档
```

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.2.10 |
| UI | Jetpack Compose (Material 3) |
| 数据库 | Room v2.7.0 (v55, 16 实体) |
| 网络 | OkHttp + SSE + Retrofit 2.12.0 |
| 序列化 | Moshi 1.15.2 |
| Firebase | Firebase BOM 34.12.0 |
| 构建 | AGP 9.1.1, KSP 2.2.10-2.0.2 |
| 文档生成 | Apache POI 5.5.1 |
| 权限管理 | Accompanist Permissions 0.37.3 |
| 图片加载 | Coil 2.7.0 |
| 相机 | CameraX 1.5.0 |
| Markdown | compose-markdown 0.7.2 |
| 后台任务 | WorkManager |
| 二维码 | ZXing |
| 测试 | JUnit, Robolectric, Roborazzi 1.59.0 |
| CI/CD | GitHub Actions (自动 Release 构建) |

## MCP 工具扩展

### 内置工具（34 个）

| 分组 | 工具 | 说明 |
|------|------|------|
| core | `get_current_time` | 获取当前日期/时间和时区 |
| core | `ask_user` | 向用户提问，支持单选/多选 |
| core | `list_mcp_tool_groups` | 列出可用工具分组及状态 |
| core | `configure_mcp_tool_groups` | 启用/禁用工具分组 |
| core | `delegate_task` | 将任务委派给 SubAgent |
| core | `check_task_status` | 查询 SubAgent 任务状态 |
| core | `list_agent_tasks` | 列出当前会话所有 SubAgent 任务 |
| core | `send_message` | 智能体间消息传递 |
| core | `read_inbox` | 读取智能体收件箱 |
| core | `manage_task_board` | 共享任务看板（创建/认领/完成/列表） |
| core | `approve_agent_request` | 批准/拒绝 SubAgent 文件操作 |
| memory | `search_memory` | 搜索长期记忆（BFS 关联遍历） |
| memory | `mark_reminded` | 标记时间提醒已送达 |
| ui_appearance | `get_ui_capabilities` | 查询 UI 主题清单和当前值 |
| ui_appearance | `adjust_ui` | 调整完整 Material 3 主题（30 色 + 布局 + 字体） |
| ui_appearance | `color_scheme` | 保存/列表/应用/删除颜色方案预设 |
| ui_text | `list_ui_texts` | 列出所有可调整 UI 文本 |
| ui_text | `set_ui_texts` | 覆盖 UI 文本标签 |
| files | `file_write` | 写入文件（UTF-8 或 base64） |
| files | `file_read` | 读取文件（支持字节/行范围） |
| files | `file_append` | 追加到文件 |
| files | `file_delete` | 删除文件/目录（支持递归） |
| files | `file_list` | 列出目录（递归，深度控制） |
| files | `file_search` | 按名称模式或内容搜索（支持正则） |
| files | `file_info` | 获取文件元数据 |
| files | `file_move` | 移动/重命名文件 |
| files | `file_copy` | 复制文件/目录 |
| files | `file_mkdir` | 创建目录 |
| documents | `create_document` | 生成 PDF/Excel/Word/PowerPoint |
| efficiency | `create_timer` | 创建一次性或重复定时器（带通知） |
| efficiency | `cancel_timer` | 取消待执行定时器 |
| efficiency | `list_timers` | 列出所有待执行定时器 |
| efficiency | `set_tool_display_mode` | 控制工具调用在聊天界面的显示方式 |

### 多智能体工作区

OmniChat 支持基于编排器模式的多智能体工作区：

- **TeamManager** -- 通过 `TeammateContext` 协程元素管理智能体生命周期的门面
- **AgentRunner** -- 每个 Agent 独立的 LLM 循环，通过 `AgentToolFilter` 过滤工具
- **AgentTool** -- 生成隔离的 SubAgent，支持可配置的 `AgentDefinition`
- **智能体间通信** -- `SendMessageTool` 实现点对点消息传递，收件箱系统
- **共享任务看板** -- `TaskTools` 提供任务 CRUD，支持自动认领和阻塞机制
- **Agent 预设** -- 保存的 Agent 配置，存储在 `agent_presets` 数据库表
- **每 Agent 模型覆盖** -- 每个 Agent 实例可使用不同模型

**内置 Agent 类型：** 通用型、探索型、计划型、验证型；支持从预设创建自定义类型。

### 添加自定义 MCP 服务器

1. 在 **设置 → MCP 工具** 标签页点击 **添加**
2. 配置服务器信息：
   - **远程 HTTP**: 填入服务器 URL（支持 SSE 2024-11-05 和 Streamable HTTP 2025-03-26）
3. 支持标准 `mcpServers` JSON 格式导入

## 云备份

OmniChat 支持通过 Cloudflare Worker 后端进行云备份：

- **认证方式**：基于 TOTP（无需密码），二维码绑定，账户恢复
- **存储**：Cloudflare R2 对象存储 + KV 元数据
- **备份格式**：
  - `.omniconfig` -- JSON 配置（提供商、MCP 服务器、记忆、模板、UI 设置、预设）
  - `.omnidb` -- 完整 SQLite 数据库
  - `.omnifile` -- 二进制格式，支持选择性分区
- **定时备份**：WorkManager 支持可配置频率（3小时/6小时/12小时/24小时/手动）
- **后端源码**：`cloudflare-worker/` 目录

## Release 构建

推送到 `Release-V*.*` 标签会自动触发 GitHub Actions 构建 Release APK：

```bash
git tag Release-V0.5
git push origin main --tags
```

产物自动发布到 [GitHub Releases](https://github.com/yuchen1204/omnichat/releases)。

## 贡献指南

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 代码规范

- Room 迁移规则：**只加列/加表，绝不删数据**
- 中文 UI 字符串使用 Android `strings.xml` 进行国际化（英文默认，中文在 `values-zh-rCN`），AI 可调整的装饰性字符串使用 `uiText("namespace.key", R.string.xxx)` 模式，自动生成 `ui_text_keys.json`
- AI 可调整的装饰性字符串使用 `uiText("namespace.key", "默认中文")` 模式
- 使用 `CompositionLocal` 传递主题和配置：`LocalUISettings`, `LocalCustomColors`, `LocalSidebarColors`, `LocalUiStrings`, `LocalChatFontScale`

## 许可证

本项目采用 MIT 许可证 -- 详见 [LICENSE](LICENSE) 文件

---

<div align="center">

**用 Kotlin 构建**

[报告问题](https://github.com/yuchen1204/omnichat/issues) · [功能请求](https://github.com/yuchen1204/omnichat/issues/new)

</div>
