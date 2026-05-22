# MCP 包管理器使用指南

OmniChat 内置了一个轻量级的 npm 包管理器，可以在 Android 设备上直接安装 Node.js 依赖。

## 🚀 功能特性

- ✅ 纯 Node.js 实现，无需额外依赖
- ✅ 支持从 npm registry 下载和安装包
- ✅ 支持版本范围解析（^、~、>= 等）
- ✅ 自动递归安装依赖
- ✅ 支持 scoped packages（@scope/name）
- ✅ 缓存机制，避免重复下载

## 📦 使用方法

### 方法 1：自动安装（推荐）

当 MCP server 启动时，如果检测到脚本目录下有 `package.json` 且 `node_modules` 不存在，会自动安装依赖。

**目录结构示例**：
```
OmniChat/mcp/
  my-server/
    index.js          # MCP server 脚本
    package.json      # 依赖配置
    node_modules/     # 自动生成
```

**package.json 示例**：
```json
{
  "name": "my-mcp-server",
  "version": "1.0.0",
  "dependencies": {
    "@modelcontextprotocol/sdk": "^1.0.0",
    "axios": "^1.6.0",
    "lodash": "^4.17.21"
  }
}
```

### 方法 2：手动安装

使用 MCP 工具手动安装依赖：

```json
{
  "method": "tools/call",
  "params": {
    "name": "npm_install",
    "arguments": {
      "package": "lodash@^4.0.0",
      "directory": "/sdcard/OmniChat/mcp/my-server"
    }
  }
}
```

### 方法 3：批量安装

```json
{
  "method": "tools/call",
  "params": {
    "name": "npm_install_all",
    "arguments": {
      "directory": "/sdcard/OmniChat/mcp/my-server"
    }
  }
}
```

## 🛠️ 可用工具

### 1. npm_install
安装单个包

**参数**：
- `package` (string, 必需): 包名，可带版本（如 `lodash@^4.0.0`）
- `directory` (string, 必需): 项目目录
- `save` (boolean, 可选): 是否保存到 package.json，默认 true

### 2. npm_install_all
安装 package.json 中的所有依赖

**参数**：
- `directory` (string, 必需): 项目目录（包含 package.json）

### 3. npm_list
列出已安装的包

**参数**：
- `directory` (string, 必需): 项目目录

### 4. npm_info
获取包的信息

**参数**：
- `package` (string, 必需): 包名

## 📋 支持的版本范围

| 格式 | 说明 | 示例 |
|------|------|------|
| `1.2.3` | 精确版本 | `lodash@4.17.21` |
| `^1.2.3` | 兼容版本（推荐） | `lodash@^4.17.21` |
| `~1.2.3` | 近似版本 | `lodash@~4.17.21` |
| `>=1.2.3` | 大于等于 | `lodash@>=4.17.21` |
| `1.2.x` | 通配符 | `lodash@4.17.x` |
| `latest` | 最新版本 | `lodash@latest` |
| `*` | 任意版本 | `lodash@*` |

## 🔧 工作原理

1. **解析依赖**：读取 package.json 中的 dependencies 和 devDependencies
2. **查询 registry**：从 npm registry 获取包的元数据
3. **版本选择**：根据版本范围选择最佳版本
4. **下载 tarball**：下载包的压缩包
5. **解压安装**：解压到 node_modules 目录
6. **递归安装**：自动安装包的依赖

## ⚠️ 注意事项

### 网络要求
- 需要网络连接来下载包
- 首次安装可能较慢，取决于网络速度

### 存储空间
- node_modules 会占用存储空间
- 建议定期清理不需要的依赖

### 兼容性
- 只支持纯 JavaScript 包
- 不支持需要编译的 native 模块（如 sqlite3、bcrypt）
- 部分包可能需要 polyfill（如 Buffer、process）

### 缓存
- 包的元数据会缓存 1 小时
- 缓存目录：`.npm-cache`

## 🎯 最佳实践

### 1. 使用预打包（推荐）
对于生产环境，建议使用 bundler 预打包：

```bash
# 使用 esbuild
esbuild server.js --bundle --platform=node --outfile=server.bundle.js \
  --external:fs --external:path --external:net

# 使用 webpack
webpack server.js -o server.bundle.js
```

**优点**：
- 启动更快
- 体积更小
- 无需运行时安装

### 2. 只安装必要依赖
```json
{
  "dependencies": {
    "lodash": "^4.17.21"  // ✅ 只安装需要的
  }
}
```

避免：
```json
{
  "dependencies": {
    "everything": "*"  // ❌ 不要安装不需要的包
  }
}
```

### 3. 使用版本锁定
```json
{
  "dependencies": {
    "lodash": "4.17.21"  // 锁定精确版本
  }
}
```

## 🐛 故障排除

### 问题：安装失败
**可能原因**：
- 网络连接问题
- 包名或版本不存在
- 存储空间不足

**解决方法**：
1. 检查网络连接
2. 验证包名和版本
3. 清理存储空间

### 问题：找不到模块
**可能原因**：
- 依赖未正确安装
- 路径错误

**解决方法**：
1. 检查 node_modules 目录
2. 重新安装依赖
3. 验证 require 路径

### 问题：包不兼容
**可能原因**：
- 包依赖 native 模块
- 使用了 Node.js 特定 API

**解决方法**：
1. 寻找纯 JS 替代方案
2. 使用预打包版本
3. 联系包作者

## 📚 示例

### 示例 1：安装 Express

```json
// 添加依赖
{
  "method": "tools/call",
  "params": {
    "name": "npm_install",
    "arguments": {
      "package": "express@^4.18.0",
      "directory": "/sdcard/OmniChat/mcp/my-api-server"
    }
  }
}
```

### 示例 2：查看已安装的包

```json
{
  "method": "tools/call",
  "params": {
    "name": "npm_list",
    "arguments": {
      "directory": "/sdcard/OmniChat/mcp/my-server"
    }
  }
}
```

### 示例 3：获取包信息

```json
{
  "method": "tools/call",
  "params": {
    "name": "npm_info",
    "arguments": {
      "package": "lodash"
    }
  }
}
```

## 🔗 相关链接

- [npm 官方文档](https://docs.npmjs.com/)
- [Node.js 内置模块](https://nodejs.org/api/)
- [OmniChat GitHub](https://github.com/your-repo/omnichat)

## 📝 更新日志

### v1.0.0 (2026-05-22)
- 初始版本
- 支持基本的包安装功能
- 支持版本范围解析
- 支持缓存机制
