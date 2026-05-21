# Node.js MCP Server Scripts

将你的 Node.js MCP server 脚本放在这个目录下。

在 MCP 配置界面中，runtime 选择 "node"，command 填写相对于此目录的路径，例如：
- `my_server.js`
- `filesystem/index.js`

## 注意事项

nodejs-mobile 的限制：整个 App 进程生命周期内只能启动一次 Node.js 运行时。
如果需要运行多个 Node.js MCP server，请将它们合并到同一个入口脚本中。
