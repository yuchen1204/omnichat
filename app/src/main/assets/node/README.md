# Node.js MCP Server Scripts

将你的 Node.js MCP server 脚本放在这个目录下。

在 MCP 配置界面中，runtime 选择 "node"，command 填写相对于此目录的路径，例如：
- `my_server.js`
- `filesystem/index.js`
- `example-server/index.js`

## 🚀 新功能：内置包管理器

OmniChat 现在内置了轻量级 npm 包管理器，可以自动安装依赖！

### 使用方法

1. **创建项目目录**：在 MCP 工作目录下创建子目录
2. **添加 package.json**：定义依赖
3. **启动 server**：系统会自动检测并安装依赖

**示例目录结构**：
```
OmniChat/mcp/
  example-server/
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
    "lodash": "^4.17.21",
    "axios": "^1.6.0"
  }
}
```

### 支持的依赖类型

- ✅ 纯 JavaScript 包（推荐）
- ⚠️ 部分需要 polyfill 的包
- ❌ 需要编译的 native 模块（如 sqlite3、bcrypt）

### 详细文档

参见 [PKG_MANAGER_README.md](./PKG_MANAGER_README.md)

## 📦 内置示例

### example-server（使用外部依赖）

展示如何使用 npm 包的示例 server。

**功能**：
- `lodash_sort_by` - 数组排序
- `lodash_group_by` - 数组分组
- `lodash_uniq` - 数组去重
- `lodash_chunk` - 数组分块
- `lodash_pick` - 对象属性选取
- `lodash_deep_flatten` - 深度扁平化
- `lodash_merge` - 深度合并对象
- `lodash_version` - 获取 lodash 版本

**使用**：
1. 在 MCP 配置中添加：command = `example-server/index.js`
2. 启动 server，依赖会自动安装
3. 开始使用工具

## 🔧 多 Node.js Server 支持

App 支持同时运行多个 Node.js MCP server，通过 `mcp_multi_bridge.js` 实现多路复用：

- 每个 Node.js server 分配独立的 TCP 端口，互不干扰
- 所有 server 运行在同一个 Node.js 实例中（绕过 nodejs-mobile 的单实例限制）
- 应用启动时会批量收集所有已启用的 Node.js server，一次性启动

**注意**：应用运行期间动态新增的 Node.js server 需要重启应用才能生效。
Python、npx、uvx 类型的 server 支持随时动态添加。

## 📂 内置脚本（自动部署）

- `mcp_filesystem.js` — 文件系统访问（读写 /sdcard 下的文件）
- `mcp_fetch.js` — HTTP/HTTPS 请求
- `mcp_pkg_manager.js` — 包管理器（自动安装依赖）
- `mcp_socket_bridge.js` — 单 server TCP bridge（内部使用）
- `mcp_multi_bridge.js` — 多 server 多路复用 bridge（内部使用）

## 💡 最佳实践

### 方案 1：使用包管理器（简单）

适合开发和测试：

1. 创建项目目录和 package.json
2. 启动 server，自动安装依赖
3. 开始开发

**优点**：无需构建步骤，开箱即用
**缺点**：启动较慢，占用存储空间

### 方案 2：预打包（推荐）

适合生产环境：

```bash
# 使用 esbuild
esbuild server.js --bundle --platform=node --outfile=server.bundle.js \
  --external:fs --external:path --external:net

# 使用 webpack
webpack server.js -o server.bundle.js
```

**优点**：启动快，体积小，无需运行时安装
**缺点**：需要构建步骤

## 🎯 开发流程

### 1. 创建新 server

```bash
# 创建目录
mkdir my-server
cd my-server

# 初始化 package.json
cat > package.json << EOF
{
  "name": "my-mcp-server",
  "version": "1.0.0",
  "dependencies": {
    "lodash": "^4.17.21"
  }
}
EOF

# 创建 server 脚本
cat > index.js << EOF
#!/usr/bin/env node
'use strict';
const _ = require('lodash');
// ... 实现 MCP server
EOF
```

### 2. 测试 server

在 MCP 配置界面中：
- runtime: `node`
- command: `my-server/index.js`
- args: `[]`

### 3. 发布 server

1. 打包成单文件（可选）
2. 分享给其他用户
3. 用户放入 MCP 工作目录即可使用

## ⚠️ 注意事项

### 网络要求
- 首次安装依赖需要网络连接
- 后续启动会使用缓存

### 存储空间
- node_modules 会占用存储空间
- 建议定期清理不需要的依赖

### 兼容性
- 只支持纯 JavaScript 包
- 部分包需要 Node.js polyfill
- 不支持需要编译的 native 模块

### 性能
- 首次启动较慢（安装依赖）
- 后续启动较快（使用缓存）
- 建议使用预打包优化生产环境

## 🔗 相关链接

- [npm 官方文档](https://docs.npmjs.com/)
- [Node.js 内置模块](https://nodejs.org/api/)
- [esbuild 打包工具](https://esbuild.github.io/)
- [MCP 协议规范](https://modelcontextprotocol.io/)

## 📚 示例代码

### 基本 MCP server 模板

```javascript
#!/usr/bin/env node
'use strict';

const readline = require('readline');

// 捕获初始流
const stdout = process.stdout;
const stdin = process.stdin;

// JSON-RPC 工具函数
function sendResponse(id, result) {
    stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function sendError(id, code, message) {
    stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n');
}

// 工具定义
const tools = {
    my_tool: {
        description: '我的工具',
        inputSchema: {
            type: 'object',
            properties: {
                param1: { type: 'string', description: '参数1' }
            },
            required: ['param1']
        },
        handler(args) {
            const { param1 } = args;
            return { content: [{ type: 'text', text: `结果: ${param1}` }] };
        }
    }
};

// MCP 协议处理
function handleRequest(req) {
    const { id, method, params } = req;

    if (method === 'exit') {
        process.exit(0);
    }

    if (method === 'initialize') {
        sendResponse(id, {
            protocolVersion: '2024-11-05',
            capabilities: { tools: {} },
            serverInfo: { name: 'my-server', version: '1.0.0' }
        });
        return;
    }

    if (method === 'notifications/initialized') return;

    if (method === 'tools/list') {
        const toolList = Object.entries(tools).map(([name, t]) => ({
            name,
            description: t.description,
            inputSchema: t.inputSchema
        }));
        sendResponse(id, { tools: toolList });
        return;
    }

    if (method === 'tools/call') {
        const toolName = params && params.name;
        const tool = tools[toolName];
        if (!tool) {
            sendError(id, -32601, `未知工具: ${toolName}`);
            return;
        }
        try {
            const result = tool.handler(params.arguments || {});
            sendResponse(id, result);
        } catch (e) {
            sendResponse(id, {
                content: [{ type: 'text', text: `错误: ${e.message}` }],
                isError: true
            });
        }
        return;
    }

    if (id !== undefined) {
        sendError(id, -32601, `未知方法: ${method}`);
    }
}

// 主循环
const rl = readline.createInterface({ input: stdin, crlfDelay: Infinity });
rl.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    try {
        const req = JSON.parse(trimmed);
        handleRequest(req);
    } catch (e) {
        process.stderr.write(`解析错误: ${e.message}\n`);
    }
});

process.stderr.write('Server 已启动\n');
```

## 🎉 开始使用

1. 创建你的 MCP server
2. 放入这个目录
3. 在 MCP 配置中添加
4. 启动并测试
5. 分享给其他用户

祝你开发愉快！(◕‿◕)★
