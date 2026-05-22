#!/usr/bin/env node
/**
 * MCP Filesystem Server (stdio transport)
 *
 * 提供对指定根目录的文件系统访问能力。
 *
 * 改进：在启动时捕获 process.stdout/stdin，确保在多路复用环境下的异步稳定性。
 */

'use strict';

const fs = require('fs');
const path = require('path');
const readline = require('readline');

// 关键：捕获初始流，后续始终使用这两个局部变量
const stdout = process.stdout;
const stdin = process.stdin;

const rootDir = process.argv[2] || '/sdcard';

// ── JSON-RPC 工具函数 ─────────────────────────────────────────────────────

function sendResponse(id, result) {
    const msg = JSON.stringify({ jsonrpc: '2.0', id, result });
    stdout.write(msg + '\n');
}

function sendError(id, code, message) {
    const msg = JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } });
    stdout.write(msg + '\n');
}

function sendNotification(method, params) {
    const msg = JSON.stringify({ jsonrpc: '2.0', method, params });
    stdout.write(msg + '\n');
}

// ── 路径安全检查 ──────────────────────────────────────────────────────────

function safePath(inputPath) {
    const resolved = path.resolve(rootDir, inputPath.replace(/^\//, ''));
    if (!resolved.startsWith(path.resolve(rootDir))) {
        throw new Error('路径越界：不允许访问根目录之外的路径');
    }
    return resolved;
}

// ── 工具实现 ──────────────────────────────────────────────────────────────

const tools = {
    list_directory: {
        description: '列出指定目录的内容',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '目录路径（相对于根目录或绝对路径）' }
            },
            required: ['path']
        },
        handler(args) {
            const dirPath = safePath(args.path);
            const entries = fs.readdirSync(dirPath, { withFileTypes: true });
            const items = entries.map(e => ({
                name: e.name,
                type: e.isDirectory() ? 'directory' : 'file',
                path: path.join(args.path, e.name)
            }));
            return { content: [{ type: 'text', text: JSON.stringify(items, null, 2) }] };
        }
    },

    read_file: {
        description: '读取文件内容',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '文件路径' },
                encoding: { type: 'string', description: '编码，默认 utf8', default: 'utf8' }
            },
            required: ['path']
        },
        handler(args) {
            const filePath = safePath(args.path);
            const content = fs.readFileSync(filePath, args.encoding || 'utf8');
            return { content: [{ type: 'text', text: content }] };
        }
    },

    write_file: {
        description: '写入文件内容（覆盖）',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '文件路径' },
                content: { type: 'string', description: '文件内容' }
            },
            required: ['path', 'content']
        },
        handler(args) {
            const filePath = safePath(args.path);
            fs.mkdirSync(path.dirname(filePath), { recursive: true });
            fs.writeFileSync(filePath, args.content, 'utf8');
            return { content: [{ type: 'text', text: `已写入: ${args.path}` }] };
        }
    },

    create_directory: {
        description: '创建目录（递归）',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '目录路径' }
            },
            required: ['path']
        },
        handler(args) {
            const dirPath = safePath(args.path);
            fs.mkdirSync(dirPath, { recursive: true });
            return { content: [{ type: 'text', text: `已创建目录: ${args.path}` }] };
        }
    },

    delete_file: {
        description: '删除文件或目录',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '文件或目录路径' },
                recursive: { type: 'boolean', description: '是否递归删除目录，默认 false' }
            },
            required: ['path']
        },
        handler(args) {
            const filePath = safePath(args.path);
            const stat = fs.statSync(filePath);
            if (stat.isDirectory()) {
                fs.rmSync(filePath, { recursive: args.recursive || false });
            } else {
                fs.unlinkSync(filePath);
            }
            return { content: [{ type: 'text', text: `已删除: ${args.path}` }] };
        }
    },

    move_file: {
        description: '移动或重命名文件',
        inputSchema: {
            type: 'object',
            properties: {
                source: { type: 'string', description: '源路径' },
                destination: { type: 'string', description: '目标路径' }
            },
            required: ['source', 'destination']
        },
        handler(args) {
            const srcPath = safePath(args.source);
            const dstPath = safePath(args.destination);
            fs.mkdirSync(path.dirname(dstPath), { recursive: true });
            fs.renameSync(srcPath, dstPath);
            return { content: [{ type: 'text', text: `已移动: ${args.source} → ${args.destination}` }] };
        }
    },

    get_file_info: {
        description: '获取文件或目录的元信息',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '文件或目录路径' }
            },
            required: ['path']
        },
        handler(args) {
            const filePath = safePath(args.path);
            const stat = fs.statSync(filePath);
            const info = {
                path: args.path,
                type: stat.isDirectory() ? 'directory' : 'file',
                size: stat.size,
                created: stat.birthtime.toISOString(),
                modified: stat.mtime.toISOString(),
                permissions: stat.mode.toString(8)
            };
            return { content: [{ type: 'text', text: JSON.stringify(info, null, 2) }] };
        }
    },

    search_files: {
        description: '在目录中递归搜索匹配文件名的文件',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '搜索起始目录' },
                pattern: { type: 'string', description: '文件名匹配模式（支持 * 通配符）' },
                maxResults: { type: 'number', description: '最大结果数，默认 50' }
            },
            required: ['path', 'pattern']
        },
        handler(args) {
            const dirPath = safePath(args.path);
            const maxResults = args.maxResults || 50;
            const results = [];
            const regex = new RegExp('^' + args.pattern.replace(/\*/g, '.*') + '$', 'i');

            function walk(dir) {
                if (results.length >= maxResults) return;
                let entries;
                try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
                for (const entry of entries) {
                    if (results.length >= maxResults) break;
                    if (regex.test(entry.name)) {
                        results.push(path.join(dir, entry.name).replace(path.resolve(rootDir), ''));
                    }
                    if (entry.isDirectory()) {
                        walk(path.join(dir, entry.name));
                    }
                }
            }

            walk(dirPath);
            return { content: [{ type: 'text', text: JSON.stringify(results, null, 2) }] };
        }
    }
};

// ── MCP 协议处理 ──────────────────────────────────────────────────────────

function handleRequest(req) {
    const { id, method, params } = req;

    if (method === 'exit') {
        process.stderr.write(`[mcp_filesystem] 收到退出指令，正在关闭...\n`);
        rl.close();
        return;
    }

    if (method === 'initialize') {
        sendResponse(id, {
            protocolVersion: '2024-11-05',
            capabilities: { tools: {} },
            serverInfo: { name: 'mcp-filesystem', version: '1.0.0' }
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

// ── 主循环 ────────────────────────────────────────────────────────────────

// 关键：使用捕获的 stdin
const rl = readline.createInterface({ input: stdin, crlfDelay: Infinity });

rl.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    try {
        const req = JSON.parse(trimmed);
        handleRequest(req);
    } catch (e) {
        process.stderr.write(`[mcp_filesystem] 解析错误: ${e.message}\n`);
    }
});

rl.on('close', () => {
    process.stderr.write(`[mcp_filesystem] readline 接口已关闭\n`);
});

process.stderr.write(`[mcp_filesystem] 已启动，根目录: ${rootDir}\n`);
