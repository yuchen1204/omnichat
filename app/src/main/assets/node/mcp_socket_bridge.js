/**
 * MCP Socket Bridge for nodejs-mobile
 *
 * 这个脚本作为 Node.js MCP server 的包装器运行在 nodejs-mobile 环境中。
 * 由于 nodejs-mobile 的 JNI 模式下 stdin/stdout 不可用于 IPC，
 * 我们通过本地 TCP socket 与 Android 主进程通信。
 *
 * 注意：nodejs-mobile 不支持 child_process.spawn("node", ...)。
 * 本脚本通过重定向 process.stdin/stdout 并 require() 目标脚本来实现运行。
 */

'use strict';

const net = require('net');
const path = require('path');
const { Readable, Writable } = require('stream');

// ── 解析参数 ──────────────────────────────────────────────────────────────

const args = process.argv.slice(2);
let port = 0;
let serverScript = null;
const serverArgs = [];

for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg.startsWith('--mcp-port=')) {
        port = parseInt(arg.split('=')[1], 10);
    } else if (serverScript === null) {
        serverScript = arg;
    } else {
        serverArgs.push(arg);
    }
}

if (!port || !serverScript) {
    console.error('[mcp_bridge] 用法: node mcp_socket_bridge.js --mcp-port=<port> <server-script> [args...]');
    return; // 不要 exit
}

console.log(`[mcp_bridge] 准备加载 MCP server: ${serverScript}`);
console.log(`[mcp_bridge] Socket 端口: ${port}`);

// ── 模拟 stdio ────────────────────────────────────────────────────────────

let activeSocket = null;

const mockStdin = new Readable({
    read() {}
});

const mockStdout = new Writable({
    write(chunk, encoding, callback) {
        if (activeSocket && activeSocket.writable) {
            activeSocket.write(chunk);
        }
        callback();
    }
});

// 重定向全局 stdio
// 警告：这会影响整个 Node.js 实例，但 nodejs-mobile 本身就只能运行一个实例
Object.defineProperty(process, 'stdin', { value: mockStdin });
Object.defineProperty(process, 'stdout', { value: mockStdout });

// 更新 argv 供被加载的脚本使用
process.argv = [process.argv[0], serverScript, ...serverArgs];

// ── 启动 TCP socket 服务器 ────────────────────────────────────────────────

const server = net.createServer((socket) => {
    console.log('[mcp_bridge] Android 主进程已连接');
    activeSocket = socket;

    socket.on('data', (data) => {
        // 将 socket 数据推送到模拟的 stdin
        mockStdin.push(data);
    });

    socket.on('error', (err) => {
        console.error(`[mcp_bridge] Socket 错误: ${err.message}`);
    });

    socket.on('close', () => {
        console.log('[mcp_bridge] Android 连接断开');
        activeSocket = null;
    });
});

server.listen(port, '127.0.0.1', () => {
    console.log(`[mcp_bridge] 监听 127.0.0.1:${port}`);

    // 在监听成功后加载实际脚本
    try {
        const absolutePath = path.isAbsolute(serverScript)
            ? serverScript
            : path.resolve(process.cwd(), serverScript);

        console.log(`[mcp_bridge] 执行脚本: ${absolutePath}`);
        // 使用 require 加载脚本，它会使用我们 mock 的 stdin/stdout
        require(absolutePath);
    } catch (e) {
        console.error(`[mcp_bridge] 脚本执行失败: ${e.stack}`);
    }
});

server.on('error', (err) => {
    console.error(`[mcp_bridge] Bridge Server 错误: ${err.message}`);
});
