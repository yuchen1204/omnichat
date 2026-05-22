#!/usr/bin/env node
/**
 * 示例 MCP Server - 使用外部依赖
 * 
 * 这个 server 展示了如何在 MCP server 中使用 npm 包
 * 依赖：lodash
 */

'use strict';

const readline = require('readline');
const _ = require('lodash');  // 使用 lodash 依赖

// 捕获初始流
const stdout = process.stdout;
const stdin = process.stdin;

// ── JSON-RPC 工具函数 ─────────────────────────────────────────────────────

function sendResponse(id, result) {
    stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function sendError(id, code, message) {
    stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n');
}

// ── 工具定义 ──────────────────────────────────────────────────────────────

const tools = {
    lodash_sort_by: {
        description: '使用 lodash 对数组进行排序',
        inputSchema: {
            type: 'object',
            properties: {
                array: { 
                    type: 'array', 
                    description: '要排序的数组' 
                },
                iteratee: { 
                    type: 'string', 
                    description: '排序依据的字段名（可选）' 
                }
            },
            required: ['array']
        },
        handler(args) {
            const { array, iteratee } = args;
            const sorted = iteratee ? _.sortBy(array, iteratee) : _.sortBy(array);
            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify(sorted, null, 2) 
                }] 
            };
        }
    },

    lodash_group_by: {
        description: '使用 lodash 对数组进行分组',
        inputSchema: {
            type: 'object',
            properties: {
                array: { 
                    type: 'array', 
                    description: '要分组的数组' 
                },
                key: { 
                    type: 'string', 
                    description: '分组依据的字段名' 
                }
            },
            required: ['array', 'key']
        },
        handler(args) {
            const { array, key } = args;
            const grouped = _.groupBy(array, key);
            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify(grouped, null, 2) 
                }] 
            };
        }
    },

    lodash_uniq: {
        description: '使用 lodash 去重',
        inputSchema: {
            type: 'object',
            properties: {
                array: { 
                    type: 'array', 
                    description: '要去重的数组' 
                },
                key: { 
                    type: 'string', 
                    description: '去重依据的字段名（可选，用于对象数组）' 
                }
            },
            required: ['array']
        },
        handler(args) {
            const { array, key } = args;
            const unique = key ? _.uniqBy(array, key) : _.uniq(array);
            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify(unique, null, 2) 
                }] 
            };
        }
    },

    lodash_chunk: {
        description: '使用 lodash 将数组分成指定大小的块',
        inputSchema: {
            type: 'object',
            properties: {
                array: { 
                    type: 'array', 
                    description: '要分块的数组' 
                },
                size: { 
                    type: 'number', 
                    description: '每个块的大小' 
                }
            },
            required: ['array', 'size']
        },
        handler(args) {
            const { array, size } = args;
            const chunked = _.chunk(array, size);
            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify(chunked, null, 2) 
                }] 
            };
        }
    },

    lodash_pick: {
        description: '使用 lodash 从对象中选取指定的属性',
        inputSchema: {
            type: 'object',
            properties: {
                object: { 
                    type: 'object', 
                    description: '源对象' 
                },
                keys: { 
                    type: 'array', 
                    items: { type: 'string' },
                    description: '要选取的属性名列表' 
                }
            },
            required: ['object', 'keys']
        },
        handler(args) {
            const { object, keys } = args;
            const picked = _.pick(object, keys);
            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify(picked, null, 2) 
                }] 
            };
        }
    },

    lodash_deep_flatten: {
        description: '使用 lodash 深度扁平化数组',
        inputSchema: {
            type: 'object',
            properties: {
                array: { 
                    type: 'array', 
                    description: '要扁平化的数组' 
                }
            },
            required: ['array']
        },
        handler(args) {
            const { array } = args;
            const flattened = _.flattenDeep(array);
            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify(flattened, null, 2) 
                }] 
            };
        }
    },

    lodash_merge: {
        description: '使用 lodash 深度合并对象',
        inputSchema: {
            type: 'object',
            properties: {
                objects: { 
                    type: 'array', 
                    items: { type: 'object' },
                    description: '要合并的对象列表' 
                }
            },
            required: ['objects']
        },
        handler(args) {
            const { objects } = args;
            const merged = _.merge({}, ...objects);
            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify(merged, null, 2) 
                }] 
            };
        }
    },

    lodash_version: {
        description: '获取 lodash 版本',
        inputSchema: {
            type: 'object',
            properties: {}
        },
        handler() {
            return { 
                content: [{ 
                    type: 'text', 
                    text: `lodash version: ${_.VERSION}` 
                }] 
            };
        }
    }
};

// ── MCP 协议处理 ──────────────────────────────────────────────────────────

function handleRequest(req) {
    const { id, method, params } = req;

    if (method === 'exit') {
        process.stderr.write(`[example-server] 收到退出指令\n`);
        rl.close();
        return;
    }

    if (method === 'initialize') {
        sendResponse(id, {
            protocolVersion: '2024-11-05',
            capabilities: { tools: {} },
            serverInfo: { name: 'example-lodash-server', version: '1.0.0' }
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

const rl = readline.createInterface({ input: stdin, crlfDelay: Infinity });

rl.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    try {
        const req = JSON.parse(trimmed);
        handleRequest(req);
    } catch (e) {
        process.stderr.write(`[example-server] 解析错误: ${e.message}\n`);
    }
});

rl.on('close', () => {
    process.stderr.write(`[example-server] readline 接口已关闭\n`);
});

process.stderr.write('[example-server] 示例 server 已启动\n');
process.stderr.write(`[example-server] lodash version: ${_.VERSION}\n`);
