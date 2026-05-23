# OmniChat 🤖💬

> 嵌入式MCP Agent运行时的Android AI助手 — 让AI真正掌控你的设备

<div align="center">

![Android](https://img.shields.io/badge/Android-24%2B-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-purple)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

## ✨ 核心特性

- **🧠 嵌入式MCP运行时** — 在Android设备上本地运行Node.js/Python MCP服务器，AI可直接调用本地工具
- **💾 跨会话记忆系统** — 15分钟滚动摘要 + 长期记忆项，AI真正"记住"你的偏好
- **🎨 AI可调整UI** — AI可通过MCP工具实时修改应用主题、颜色、布局
- **🔄 多模型支持** — OpenAI兼容API，支持Gemini、OpenAI、本地模型等
- **📡 SSE流式输出** — 实时流式响应，打字机效果

## 📱 应用架构

```
┌─────────────────────────────────────────────────────────┐
│                    Compose UI Layer                      │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────────┐  │
│  │ ChatScreen│  │ Settings │  │    MCP Config         │  │
│  └─────┬────┘  └─────┬────┘  └───────────┬───────────┘  │
│        │             │                    │              │
├────────┴─────────────┴────────────────────┴──────────────┤
│                    ViewModel Layer                        │
│  ┌──────────────┐  ┌─────────────────┐  ┌─────────────┐ │
│  │ ChatViewModel│  │SettingsViewModel│  │ McpViewModel│ │
│  └──────┬───────┘  └────────┬────────┘  └──────┬──────┘ │
├─────────┴───────────────────┴──────────────────┴────────┤
│                    Data/Repository Layer                  │
│  ┌─────────────────────────────────────────────────────┐ │
│  │              AppRepository (Room DB)                 │ │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────┐  │ │
│  │  │Sessions │ │Messages │ │ Memory  │ │MCP Servers│  │ │
│  │  └─────────┘ └─────────┘ └─────────┘ └──────────┘  │ │
│  └─────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────┤
│                    MCP Runtime Layer                      │
│  ┌───────────────┐  ┌───────────────┐  ┌──────────────┐ │
│  │  NodeJsBridge │  │ PythonBridge  │  │ Remote HTTP  │ │
│  │   (JNI/TCP)   │  │   (JNI/dlopen)│  │   (Direct)   │ │
│  └───────────────┘  └───────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────┘
```

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 11+
- Android SDK 36

### 安装步骤

```bash
# 1. 克隆仓库
git clone https://github.com/yuchen1204/omnichat.git
cd omnichat

# 2. 构建Debug版本
./gradlew assembleDebug

# 3. 安装到设备
./gradlew installDebug
```

### 运行测试

```bash
# 单元测试
./gradlew testDebugUnitTest

# Android仪器测试
./gradlew connectedDebugAndroidTest

# 截图测试 (Roborazzi)
./gradlew verifyRoborazziDebug
```

## 📂 项目结构

```
omnichat/
├── app/src/main/java/com/example/
│   ├── MainActivity.kt              # 入口Activity
│   ├── data/                        # 数据层
│   │   ├── Entities.kt              # Room实体定义 (10个表)
│   │   ├── Daos.kt                  # DAO接口
│   │   ├── AppDatabase.kt           # 数据库配置 (v18)
│   │   └── Repository.kt            # 数据仓库
│   ├── mcp/                         # MCP运行时
│   │   ├── McpRuntimeManager.kt     # 运行时管理器
│   │   ├── NodeJsBridge.kt          # Node.js JNI桥接
│   │   ├── PythonBridge.kt          # Python桥接
│   │   └── BuiltinToolHandler.kt    # 内置工具处理
│   ├── network/ApiClient.kt         # API客户端 (SSE)
│   ├── ui/screens/                  # Compose界面
│   └── ui/viewmodel/                # ViewModel层
├── app/src/main/cpp/                # C++ JNI代码
├── app/src/main/assets/             # 资源文件
│   └── node/                        # Node.js MCP脚本
└── scripts/                         # 工具脚本
```

## 🛠️ 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.2.10, C++17 |
| UI | Jetpack Compose, Material 3 |
| 数据库 | Room (17次迁移) |
| 网络 | OkHttp + SSE |
| 构建 | AGP 9.1.1, KSP, CMake 3.22.1 |
| 测试 | JUnit, Robolectric, Roborazzi |
| 原生 | nodejs-mobile (libnode.so), Python 3.14 |

## 📊 数据库表结构

| 实体 | 表名 | 用途 |
|------|------|------|
| `ModelConfig` | `model_configs` | API配置管理 |
| `Session` | `sessions` | 聊天会话 |
| `Message` | `messages` | 消息记录 |
| `MemoryItem` | `memory_items` | 跨会话记忆 |
| `PromptTemplate` | `prompt_templates` | 提示词模板 |
| `FetchedModel` | `fetched_models` | 模型列表缓存 |
| `SessionSummary` | `session_summaries` | 会话摘要 |
| `McpServer` | `mcp_servers` | MCP服务器配置 |
| `UISettings` | `ui_settings` | UI主题设置 |
| `ColorSchemePreset` | `color_scheme_presets` | 颜色方案预设 |

## 🔧 MCP工具扩展

### 内置工具

- **文件系统** — 读写本地文件
- **网络请求** — HTTP抓取
- **UI定制** — 动态调整主题参数
- **颜色方案** — 保存/加载主题预设

### 添加自定义MCP服务器

1. 在"设置 → MCP工具"标签页点击"添加"
2. 配置服务器信息：
   - **Node.js**: 指定`.js`文件路径
   - **Python**: 指定`.py`文件路径
   - **远程HTTP**: 填入服务器URL
3. 支持标准`mcpServers` JSON格式导入

## 🤝 贡献指南

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

## 🙏 致谢

- [Google AI Studio](https://ai.studio/) — 项目初始模板
- [nodejs-mobile](https://github.com/nickoala/nickoala.github.io) — Android端Node.js运行时
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — 现代Android UI框架

---

<div align="center">

**用 ❤️ 和 Kotlin 构建**

[报告问题](https://github.com/yuchen1204/omnichat/issues) · [功能请求](https://github.com/yuchen1204/omnichat/issues/new) · [文档](https://github.com/yuchen1204/omnichat/wiki)

</div>
