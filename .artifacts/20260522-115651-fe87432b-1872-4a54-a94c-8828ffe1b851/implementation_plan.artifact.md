# Fix MCP Handshake Timeout for Node.js Servers

This plan addresses the `TimeoutCancellationException` during MCP handshake for Node.js servers (specifically the "文件系统" server). The root cause is premature restoration of global `process.stdout` in the `mcp_multi_bridge.js` multiplexer, which causes asynchronous responses from the server scripts to be misdirected.

## Proposed Changes

### Node.js Infrastructure

#### [mcp_multi_bridge.js](file:///E:/omnichat/app/src/main/assets/node/mcp_multi_bridge.js)

- Introduce `AsyncLocalStorage` (with fallback) to track execution context for multiple MCP servers.
- Override `process.stdout` and `process.stdin` using getters that return the correct virtual stream based on the current context.
- Wrap `require()` and socket event handlers in `als.run()` to preserve context across asynchronous boundaries.
- Remove or refine the `finally` block that restores global stdio, as the Proxy/Getter approach is more robust.

```javascript
// Example of context tracking in mcp_multi_bridge.js
const { AsyncLocalStorage } = require('async_hooks');
const als = new AsyncLocalStorage();

Object.defineProperty(process, 'stdout', {
    get() {
        const store = als.getStore();
        return (store && store.stdout) || originalStdout;
    },
    configurable: true
});

// In startServer
socket.on('data', (data) => {
    als.run({ stdout: mockStdout, stdin: mockStdin }, () => {
        mockStdin.push(data);
    });
});
```

### Built-in MCP Scripts

#### [mcp_filesystem.js](file:///E:/omnichat/app/src/main/assets/node/mcp_filesystem.js)
#### [mcp_fetch.js](file:///E:/omnichat/app/src/main/assets/node/mcp_fetch.js)

- Capture `process.stdout` and `process.stdin` into local variables at the very beginning of the script.
- Use these captured variables instead of global `process.stdout`/`stdin` throughout the script. This ensures the script always uses the correct virtual streams provided during its initialization, even if the bridge later changes the global `process` object.

```javascript
// At the top of mcp_filesystem.js and mcp_fetch.js
const stdout = process.stdout;
const stdin = process.stdin;

function sendResponse(id, result) {
    stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}
```

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to ensure the project builds correctly with the modified assets.
- I will perform a static analysis (manual review) of the Javascript changes to ensure they are syntactically correct and follow Node.js best practices for the expected environment.

### Manual Verification
1.  **Deploy and Run**: Since I cannot run the Android app in a real device with the Node.js runtime here, I will rely on the logical proof that capturing streams at startup is the standard way to handle such "bridge" environments.
2.  **Code Review**: Verify that all asynchronous calls in the modified JS files use the captured streams.
3.  **Logcat Analysis**: After deployment, the user can verify that the "MCP 握手失败" error no longer appears and that the "文件系统" server status changes to "RUNNING".
