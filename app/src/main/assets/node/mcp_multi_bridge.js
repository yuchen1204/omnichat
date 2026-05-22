/**
 * MCP Multi-Server Bridge for nodejs-mobile
 *
 * 在单个 Node.js 实例中同时运行多个 MCP server，每个 server 监听独立的 TCP 端口。
 * 解决 nodejs-mobile 只能启动一次 Node.js 运行时的限制。
 *
 * 改进：支持动态添加 (Hot Registration) 新的 MCP server。
 */

'use strict';

const net = require('net');
const path = require('path');
const { Readable, Writable } = require('stream');

// ── 异步上下文跟踪 ──────────────────────────────────────────────────────────

let AsyncLocalStorage;
try {
    AsyncLocalStorage = require('async_hooks').AsyncLocalStorage;
} catch (e) {
    AsyncLocalStorage = class {
        constructor() { this.store = null; }
        run(store, callback) {
            const prev = this.store;
            this.store = store;
            try { return callback(); } finally { this.store = prev; }
        }
        getStore() { return this.store; }
    };
}
const als = new AsyncLocalStorage();

// ── 解析启动参数 ──────────────────────────────────────────────────────────

const args = process.argv.slice(2);
let configJson = null;

for (const arg of args) {
    if (arg.startsWith('--config=')) {
        const encoded = arg.slice('--config='.length);
        try {
            configJson = JSON.parse(Buffer.from(encoded, 'base64').toString('utf8'));
        } catch (e) {
            process.stderr.write(`[mcp_multi_bridge] 配置解析失败: ${e.message}\n`);
        }
    }
}

if (!configJson) {
    process.stderr.write('[mcp_multi_bridge] 错误：未提供有效的 --config 参数\n');
    return;
}

// ── 全局 stdio 劫持 ────────────────────────────────────────────────────────

const originalStdin = process.stdin;
const originalStdout = process.stdout;

Object.defineProperty(process, 'stdout', {
    get() {
        const store = als.getStore();
        return (store && store.stdout) || originalStdout;
    },
    configurable: true
});

Object.defineProperty(process, 'stdin', {
    get() {
        const store = als.getStore();
        return (store && store.stdin) || originalStdin;
    },
    configurable: true
});

// ── 为每个 server 创建独立的虚拟 stdio 环境 ──────────────────────────────

const servers = new Map();

function startServer(serverConfig, index) {
    const { port, script, args: scriptArgs = [] } = serverConfig;
    const label = `[server#${index}:${path.basename(script)}:${port}]`;

    if (servers.has(port)) {
        process.stderr.write(`${label} 端口冲突，该 server 已在运行\n`);
        return;
    }

    process.stderr.write(`${label} 准备启动，端口 ${port}\n`);

    const mockStdin = new Readable({ read() {} });
    const mockStdout = new Writable({
        write(chunk, encoding, callback) {
            if (activeSocket && !activeSocket.destroyed) {
                activeSocket.write(chunk);
            }
            callback();
        }
    });

    let activeSocket = null;
    const context = { stdin: mockStdin, stdout: mockStdout };

    const tcpServer = net.createServer((socket) => {
        process.stderr.write(`${label} Android 已连接\n`);
        activeSocket = socket;

        socket.on('data', (data) => {
            const raw = data.toString().trim();
            if (raw === '{"jsonrpc":"2.0","method":"exit"}') {
                process.stderr.write(`${label} 收到退出指令，关闭 server\n`);
                stopServer(port);
                return;
            }

            als.run(context, () => {
                mockStdin.push(data);
            });
        });

        socket.on('error', (err) => {
            process.stderr.write(`${label} Socket 错误: ${err.message}\n`);
        });

        socket.on('close', () => {
            process.stderr.write(`${label} Android 断开连接\n`);
            activeSocket = null;
        });
    });

    function stopServer(p) {
        const s = servers.get(p);
        if (s) {
            process.stderr.write(`${label} 正在停止...\n`);
            s.tcpServer.close();
            s.mockStdin.push(null);
            servers.delete(p);
        }
    }

    tcpServer.on('error', (err) => {
        process.stderr.write(`${label} TCP server 错误: ${err.message}\n`);
    });

    tcpServer.listen(port, '127.0.0.1', () => {
        process.stderr.write(`${label} 监听 127.0.0.1:${port}\n`);

        const originalArgv = process.argv;
        process.argv = [process.argv[0], script, ...scriptArgs];

        const absoluteScript = path.isAbsolute(script)
            ? script
            : path.resolve(process.cwd(), script);

        process.stderr.write(`${label} 加载脚本: ${absoluteScript}\n`);

        als.run(context, () => {
            try {
                delete require.cache[require.resolve(absoluteScript)];
                require(absoluteScript);
            } catch (e) {
                process.stderr.write(`${label} 脚本加载失败: ${e.stack}\n`);
            }
        });

        process.argv = originalArgv;
    });

    servers.set(port, { tcpServer, mockStdin, stopServer });
}

// ── 控制通道 (用于动态添加 Server) ────────────────────────────────────────

function startControlServer(controlPort) {
    if (!controlPort) return;

    const controlServer = net.createServer((socket) => {
        socket.on('data', (data) => {
            try {
                const cmd = JSON.parse(data.toString());
                if (cmd.command === 'add_server') {
                    process.stderr.write(`[control] 收到动态添加请求: ${cmd.serverConfig.script}\n`);
                    startServer(cmd.serverConfig, servers.size);
                    socket.write(JSON.stringify({ status: 'ok' }) + '\n');
                    socket.end();
                } else {
                    socket.write(JSON.stringify({ status: 'error', message: '未知命令' }) + '\n');
                    socket.end();
                }
            } catch (e) {
                socket.write(JSON.stringify({ status: 'error', message: e.message }));
            }
        });
    });

    controlServer.listen(controlPort, '127.0.0.1', () => {
        process.stderr.write(`[control] 控制通道已就绪，监听端口: ${controlPort}\n`);
    });
}

// ── 依次启动初始 server 并开启控制通道 ──────────────────────────────────────

if (Array.isArray(configJson.servers)) {
    configJson.servers.forEach((serverConfig, index) => {
        setTimeout(() => {
            try {
                startServer(serverConfig, index);
            } catch (e) {
                process.stderr.write(`[mcp_multi_bridge] server#${index} 启动异常: ${e.stack}\n`);
            }
        }, index * 50);
    });
}

startControlServer(configJson.controlPort);

process.stderr.write('[mcp_multi_bridge] 多路复用 bridge 已初始化\n');
