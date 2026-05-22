#!/usr/bin/env node
/**
 * MCP Fetch Server (stdio transport)
 *
 * 提供 HTTP/HTTPS 请求能力，让 AI 可以访问网络资源。
 *
 * 改进：在启动时捕获 process.stdout/stdin，确保在多路复用环境下的异步稳定性。
 */

'use strict';

const https = require('https');
const http = require('http');
const readline = require('readline');
const { URL } = require('url');

// 关键：捕获初始流
const stdout = process.stdout;
const stdin = process.stdin;

// ── JSON-RPC 工具函数 ─────────────────────────────────────────────────────

function sendResponse(id, result) {
    stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function sendError(id, code, message) {
    stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n');
}

// ── HTTP 请求实现 ─────────────────────────────────────────────────────────

function doFetch(url, options = {}) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(url);
        const lib = parsed.protocol === 'https:' ? https : http;
        const method = (options.method || 'GET').toUpperCase();
        const headers = options.headers || {};
        const body = options.body;

        if (body && !headers['Content-Length']) {
            headers['Content-Length'] = Buffer.byteLength(body);
        }

        const req = lib.request({
            hostname: parsed.hostname,
            port: parsed.port || (parsed.protocol === 'https:' ? 443 : 80),
            path: parsed.pathname + parsed.search,
            method,
            headers
        }, (res) => {
            const chunks = [];
            res.on('data', chunk => chunks.push(chunk));
            res.on('end', () => {
                const rawBody = Buffer.concat(chunks);
                const contentType = res.headers['content-type'] || '';
                const isText = contentType.includes('text') ||
                               contentType.includes('json') ||
                               contentType.includes('xml') ||
                               contentType.includes('javascript');
                resolve({
                    status: res.statusCode,
                    statusText: res.statusMessage,
                    headers: res.headers,
                    body: isText ? rawBody.toString('utf8') : `[二进制内容，${rawBody.length} 字节]`
                });
            });
        });

        req.on('error', reject);
        req.setTimeout(30000, () => { req.destroy(); reject(new Error('请求超时')); });

        if (body) req.write(body);
        req.end();
    });
}

// ── 工具定义 ──────────────────────────────────────────────────────────────

const tools = {
    fetch: {
        description: '发起 HTTP/HTTPS 请求，返回响应内容',
        inputSchema: {
            type: 'object',
            properties: {
                url: { type: 'string', description: '请求 URL' },
                method: { type: 'string', description: 'HTTP 方法，默认 GET', default: 'GET' },
                headers: { type: 'object', description: '请求头（键值对）' },
                body: { type: 'string', description: '请求体（POST/PUT 时使用）' },
                maxLength: { type: 'number', description: '响应体最大字符数，默认 10000' }
            },
            required: ['url']
        },
        async handler(args) {
            const result = await doFetch(args.url, {
                method: args.method || 'GET',
                headers: args.headers || {},
                body: args.body
            });
            const maxLen = args.maxLength || 10000;
            const body = result.body.length > maxLen
                ? result.body.slice(0, maxLen) + `\n...[已截断，共 ${result.body.length} 字符]`
                : result.body;
            const text = `状态: ${result.status} ${result.statusText}\n内容类型: ${result.headers['content-type'] || '未知'}\n\n${body}`;
            return { content: [{ type: 'text', text }] };
        }
    }
};

// ── MCP 协议处理 ──────────────────────────────────────────────────────────

async function handleRequest(req) {
    const { id, method, params } = req;

    if (method === 'exit') {
        process.stderr.write(`[mcp_fetch] 收到退出指令，正在关闭...\n`);
        rl.close();
        return;
    }

    if (method === 'initialize') {
        sendResponse(id, {
            protocolVersion: '2024-11-05',
            capabilities: { tools: {} },
            serverInfo: { name: 'mcp-fetch', version: '1.0.0' }
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
            const result = await tool.handler(params.arguments || {});
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

const rl = readline.createInterface({ input: stdin, crlfDelay: Infinity });

rl.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    try {
        const req = JSON.parse(trimmed);
        handleRequest(req).catch(e => {
            process.stderr.write(`[mcp_fetch] 处理错误: ${e.message}\n`);
        });
    } catch (e) {
        process.stderr.write(`[mcp_fetch] 解析错误: ${e.message}\n`);
    }
});

rl.on('close', () => {
    process.stderr.write(`[mcp_fetch] readline 接口已关闭\n`);
});

process.stderr.write('[mcp_fetch] 已启动\n');
