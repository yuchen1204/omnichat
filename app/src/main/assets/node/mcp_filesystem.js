#!/usr/bin/env node
/**
 * MCP Filesystem Server (stdio transport)
 *
 * 提供对指定根目录的文件系统访问能力。
 * 支持工具：
 *   list_directory, read_file, write_file, append_file, copy_file,
 *   move_file, delete_file, create_directory, get_file_info,
 *   search_files, search_content, get_working_directory
 */

'use strict';

const fs = require('fs');
const path = require('path');
const readline = require('readline');

// 关键：捕获初始流，确保在多路复用环境下的异步稳定性
const stdout = process.stdout;
const stdin = process.stdin;

const rootDir = path.resolve(process.argv[2] || '/sdcard');

// ── JSON-RPC 工具函数 ─────────────────────────────────────────────────────

function sendResponse(id, result) {
    const msg = JSON.stringify({ jsonrpc: '2.0', id, result });
    stdout.write(msg + '\n');
}

function sendError(id, code, message) {
    const msg = JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } });
    stdout.write(msg + '\n');
}

// ── 路径安全检查 ──────────────────────────────────────────────────────────

/**
 * 将用户输入路径解析为安全的绝对路径。
 * - 绝对路径：直接规范化，不限制在 rootDir 内（允许访问任意绝对路径，
 *   权限控制由 Kotlin 侧 McpPermissionManager 负责）
 * - 相对路径：resolve 到 rootDir 下，并检查不越界
 */
function safePath(inputPath) {
    if (!inputPath || typeof inputPath !== 'string') {
        throw new Error('路径不能为空');
    }
    if (path.isAbsolute(inputPath)) {
        return path.normalize(inputPath);
    }
    const resolved = path.resolve(rootDir, inputPath);
    const rootResolved = path.resolve(rootDir);
    if (!resolved.startsWith(rootResolved + path.sep) && resolved !== rootResolved) {
        throw new Error('路径越界：不允许访问根目录之外的相对路径');
    }
    return resolved;
}

/**
 * 将 glob 模式转换为正则表达式（支持 * 和 ? 通配符）
 */
function globToRegex(pattern) {
    const escaped = pattern.replace(/[.+^${}()|[\]\\]/g, '\\$&');
    const regexStr = escaped.replace(/\*/g, '.*').replace(/\?/g, '.');
    return new RegExp('^' + regexStr + '$', 'i');
}

/**
 * 格式化文件大小为人类可读字符串
 */
function formatSize(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

// ── 工具实现 ──────────────────────────────────────────────────────────────

const tools = {

    get_working_directory: {
        description: '获取当前工作根目录路径',
        inputSchema: {
            type: 'object',
            properties: {}
        },
        handler(_args) {
            return {
                content: [{
                    type: 'text',
                    text: JSON.stringify({ root_directory: rootDir }, null, 2)
                }]
            };
        }
    },

    list_directory: {
        description: '列出指定目录的内容，包含文件大小和修改时间',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '目录路径（相对于根目录或绝对路径）' },
                recursive: { type: 'boolean', description: '是否递归列出子目录，默认 false' },
                max_depth: { type: 'number', description: '递归最大深度，默认 3（recursive=true 时有效）' },
                show_hidden: { type: 'boolean', description: '是否显示隐藏文件（以 . 开头），默认 false' }
            },
            required: ['path']
        },
        handler(args) {
            const dirPath = safePath(args.path);
            if (!fs.existsSync(dirPath)) {
                throw new Error(`目录不存在: ${args.path}`);
            }
            const stat = fs.statSync(dirPath);
            if (!stat.isDirectory()) {
                throw new Error(`不是目录: ${args.path}`);
            }

            const showHidden = args.show_hidden || false;
            const recursive = args.recursive || false;
            const maxDepth = args.max_depth != null ? args.max_depth : 3;

            function listDir(dir, depth) {
                const entries = fs.readdirSync(dir, { withFileTypes: true });
                const items = [];
                for (const e of entries) {
                    if (!showHidden && e.name.startsWith('.')) continue;
                    const fullPath = path.join(dir, e.name);
                    let itemStat;
                    try { itemStat = fs.statSync(fullPath); } catch { continue; }
                    const item = {
                        name: e.name,
                        type: e.isDirectory() ? 'directory' : 'file',
                        path: fullPath,
                        size: e.isDirectory() ? null : itemStat.size,
                        size_human: e.isDirectory() ? null : formatSize(itemStat.size),
                        modified: itemStat.mtime.toISOString()
                    };
                    if (recursive && e.isDirectory() && depth < maxDepth) {
                        item.children = listDir(fullPath, depth + 1);
                    }
                    items.push(item);
                }
                return items;
            }

            const items = listDir(dirPath, 1);
            return { content: [{ type: 'text', text: JSON.stringify(items, null, 2) }] };
        }
    },

    read_file: {
        description: '读取文件内容，支持按行范围读取',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '文件路径' },
                encoding: { type: 'string', description: '编码，默认 utf8，二进制文件可用 base64' },
                start_line: { type: 'number', description: '起始行号（从 1 开始，含），不填则从头读' },
                end_line: { type: 'number', description: '结束行号（含），不填则读到末尾' }
            },
            required: ['path']
        },
        handler(args) {
            const filePath = safePath(args.path);
            if (!fs.existsSync(filePath)) {
                throw new Error(`文件不存在: ${args.path}`);
            }
            const stat = fs.statSync(filePath);
            if (stat.isDirectory()) {
                throw new Error(`路径是目录，请使用 list_directory: ${args.path}`);
            }

            const encoding = args.encoding || 'utf8';

            // base64 模式：直接读取整个文件
            if (encoding === 'base64') {
                const buf = fs.readFileSync(filePath);
                return {
                    content: [{
                        type: 'text',
                        text: `[base64]\n${buf.toString('base64')}`
                    }]
                };
            }

            const raw = fs.readFileSync(filePath, 'utf8');

            // 无行范围：直接返回
            if (args.start_line == null && args.end_line == null) {
                const lineCount = raw.split('\n').length;
                return {
                    content: [{
                        type: 'text',
                        text: `[文件: ${args.path} | ${lineCount} 行 | ${formatSize(stat.size)}]\n${raw}`
                    }]
                };
            }

            // 按行范围读取
            const lines = raw.split('\n');
            const total = lines.length;
            const start = Math.max(1, args.start_line || 1);
            const end = Math.min(total, args.end_line || total);
            const slice = lines.slice(start - 1, end).join('\n');
            return {
                content: [{
                    type: 'text',
                    text: `[文件: ${args.path} | 第 ${start}–${end} 行 / 共 ${total} 行]\n${slice}`
                }]
            };
        }
    },

    write_file: {
        description: '写入文件内容（覆盖）。目标目录不存在时自动创建',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '文件路径' },
                content: { type: 'string', description: '文件内容' },
                encoding: { type: 'string', description: '编码，默认 utf8，可用 base64' }
            },
            required: ['path', 'content']
        },
        handler(args) {
            const filePath = safePath(args.path);
            fs.mkdirSync(path.dirname(filePath), { recursive: true });
            const encoding = args.encoding || 'utf8';
            if (encoding === 'base64') {
                fs.writeFileSync(filePath, Buffer.from(args.content, 'base64'));
            } else {
                fs.writeFileSync(filePath, args.content, 'utf8');
            }
            const stat = fs.statSync(filePath);
            return {
                content: [{
                    type: 'text',
                    text: `已写入: ${args.path} (${formatSize(stat.size)})`
                }]
            };
        }
    },

    append_file: {
        description: '向文件末尾追加内容。文件不存在时自动创建',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '文件路径' },
                content: { type: 'string', description: '要追加的内容' },
                newline: { type: 'boolean', description: '追加前是否先插入换行符，默认 true' }
            },
            required: ['path', 'content']
        },
        handler(args) {
            const filePath = safePath(args.path);
            fs.mkdirSync(path.dirname(filePath), { recursive: true });
            const addNewline = args.newline !== false;
            const toWrite = addNewline && fs.existsSync(filePath) && fs.statSync(filePath).size > 0
                ? '\n' + args.content
                : args.content;
            fs.appendFileSync(filePath, toWrite, 'utf8');
            const stat = fs.statSync(filePath);
            return {
                content: [{
                    type: 'text',
                    text: `已追加到: ${args.path} (当前大小: ${formatSize(stat.size)})`
                }]
            };
        }
    },

    copy_file: {
        description: '复制文件或目录',
        inputSchema: {
            type: 'object',
            properties: {
                source: { type: 'string', description: '源路径' },
                destination: { type: 'string', description: '目标路径' },
                overwrite: { type: 'boolean', description: '目标已存在时是否覆盖，默认 false' }
            },
            required: ['source', 'destination']
        },
        handler(args) {
            const srcPath = safePath(args.source);
            const dstPath = safePath(args.destination);
            if (!fs.existsSync(srcPath)) {
                throw new Error(`源路径不存在: ${args.source}`);
            }
            if (fs.existsSync(dstPath) && !args.overwrite) {
                throw new Error(`目标已存在: ${args.destination}。如需覆盖请设置 overwrite: true`);
            }
            fs.mkdirSync(path.dirname(dstPath), { recursive: true });

            function copyRecursive(src, dst) {
                const stat = fs.statSync(src);
                if (stat.isDirectory()) {
                    fs.mkdirSync(dst, { recursive: true });
                    for (const entry of fs.readdirSync(src)) {
                        copyRecursive(path.join(src, entry), path.join(dst, entry));
                    }
                } else {
                    fs.copyFileSync(src, dst);
                }
            }

            copyRecursive(srcPath, dstPath);
            return {
                content: [{
                    type: 'text',
                    text: `已复制: ${args.source} → ${args.destination}`
                }]
            };
        }
    },

    move_file: {
        description: '移动或重命名文件/目录',
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
            if (!fs.existsSync(srcPath)) {
                throw new Error(`源路径不存在: ${args.source}`);
            }
            fs.mkdirSync(path.dirname(dstPath), { recursive: true });
            fs.renameSync(srcPath, dstPath);
            return {
                content: [{
                    type: 'text',
                    text: `已移动: ${args.source} → ${args.destination}`
                }]
            };
        }
    },

    delete_file: {
        description: '删除文件或目录。删除目录时必须显式设置 recursive: true',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '文件或目录路径' },
                recursive: { type: 'boolean', description: '是否递归删除目录（必须显式设为 true 才能删除非空目录）' }
            },
            required: ['path']
        },
        handler(args) {
            const filePath = safePath(args.path);
            if (!fs.existsSync(filePath)) {
                throw new Error(`路径不存在: ${args.path}`);
            }
            const stat = fs.statSync(filePath);
            if (stat.isDirectory()) {
                if (!args.recursive) {
                    throw new Error(`${args.path} 是目录，删除目录需要设置 recursive: true`);
                }
                fs.rmSync(filePath, { recursive: true, force: true });
                return { content: [{ type: 'text', text: `已递归删除目录: ${args.path}` }] };
            } else {
                fs.unlinkSync(filePath);
                return { content: [{ type: 'text', text: `已删除文件: ${args.path}` }] };
            }
        }
    },

    create_directory: {
        description: '创建目录（递归，已存在时不报错）',
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

    get_file_info: {
        description: '获取文件或目录的详细元信息',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '文件或目录路径' }
            },
            required: ['path']
        },
        handler(args) {
            const filePath = safePath(args.path);
            if (!fs.existsSync(filePath)) {
                throw new Error(`路径不存在: ${args.path}`);
            }
            const stat = fs.statSync(filePath);
            const isDir = stat.isDirectory();
            const info = {
                path: args.path,
                absolute_path: filePath,
                type: isDir ? 'directory' : 'file',
                size: stat.size,
                size_human: formatSize(stat.size),
                created: stat.birthtime.toISOString(),
                modified: stat.mtime.toISOString(),
                accessed: stat.atime.toISOString(),
                permissions: stat.mode.toString(8),
                is_readable: true,
                extension: isDir ? null : path.extname(args.path).toLowerCase() || null
            };
            if (isDir) {
                try {
                    info.entry_count = fs.readdirSync(filePath).length;
                } catch {
                    info.entry_count = null;
                }
            }
            return { content: [{ type: 'text', text: JSON.stringify(info, null, 2) }] };
        }
    },

    search_files: {
        description: '在目录中递归搜索匹配文件名的文件（支持 * 和 ? 通配符）',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '搜索起始目录' },
                pattern: { type: 'string', description: '文件名匹配模式，支持 * 和 ? 通配符，如 *.txt、report_?.csv' },
                max_results: { type: 'number', description: '最大结果数，默认 100' },
                include_dirs: { type: 'boolean', description: '是否包含目录，默认 false' }
            },
            required: ['path', 'pattern']
        },
        handler(args) {
            const dirPath = safePath(args.path);
            if (!fs.existsSync(dirPath)) {
                throw new Error(`目录不存在: ${args.path}`);
            }
            const maxResults = args.max_results || 100;
            const includeDirs = args.include_dirs || false;
            const regex = globToRegex(args.pattern);
            const results = [];

            function walk(dir) {
                if (results.length >= maxResults) return;
                let entries;
                try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
                for (const entry of entries) {
                    if (results.length >= maxResults) break;
                    const fullPath = path.join(dir, entry.name);
                    const isDir = entry.isDirectory();
                    if (regex.test(entry.name) && (!isDir || includeDirs)) {
                        let size = null;
                        try {
                            const s = fs.statSync(fullPath);
                            size = isDir ? null : s.size;
                        } catch { /* ignore */ }
                        results.push({
                            path: fullPath,
                            name: entry.name,
                            type: isDir ? 'directory' : 'file',
                            size,
                            size_human: size != null ? formatSize(size) : null
                        });
                    }
                    if (isDir) walk(fullPath);
                }
            }

            walk(dirPath);
            const summary = results.length >= maxResults
                ? `（已达上限 ${maxResults} 条，可能有更多结果）`
                : `（共 ${results.length} 条）`;
            return {
                content: [{
                    type: 'text',
                    text: `搜索 "${args.pattern}" 结果 ${summary}:\n${JSON.stringify(results, null, 2)}`
                }]
            };
        }
    },

    search_content: {
        description: '在文件中搜索包含指定文本或正则表达式的行（类似 grep）',
        inputSchema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: '搜索起始目录或单个文件路径' },
                pattern: { type: 'string', description: '搜索的文本或正则表达式' },
                file_pattern: { type: 'string', description: '限定搜索的文件名模式，如 *.txt（仅在 path 为目录时有效）' },
                is_regex: { type: 'boolean', description: '是否将 pattern 视为正则表达式，默认 false（普通文本搜索）' },
                case_sensitive: { type: 'boolean', description: '是否区分大小写，默认 false' },
                context_lines: { type: 'number', description: '匹配行前后各显示的上下文行数，默认 2' },
                max_results: { type: 'number', description: '最大匹配条数，默认 50' }
            },
            required: ['path', 'pattern']
        },
        handler(args) {
            const targetPath = safePath(args.path);
            if (!fs.existsSync(targetPath)) {
                throw new Error(`路径不存在: ${args.path}`);
            }

            const maxResults = args.max_results || 50;
            const contextLines = args.context_lines != null ? args.context_lines : 2;
            const flags = args.case_sensitive ? '' : 'i';
            let searchRegex;
            try {
                searchRegex = new RegExp(
                    args.is_regex ? args.pattern : args.pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
                    flags
                );
            } catch (e) {
                throw new Error(`无效的正则表达式: ${e.message}`);
            }

            const filePattern = args.file_pattern ? globToRegex(args.file_pattern) : null;
            const matches = [];

            function searchInFile(filePath) {
                if (matches.length >= maxResults) return;
                let content;
                try { content = fs.readFileSync(filePath, 'utf8'); } catch { return; }
                const lines = content.split('\n');
                for (let i = 0; i < lines.length; i++) {
                    if (matches.length >= maxResults) break;
                    if (searchRegex.test(lines[i])) {
                        const start = Math.max(0, i - contextLines);
                        const end = Math.min(lines.length - 1, i + contextLines);
                        const contextBlock = [];
                        for (let j = start; j <= end; j++) {
                            contextBlock.push({
                                line: j + 1,
                                text: lines[j],
                                is_match: j === i
                            });
                        }
                        matches.push({
                            file: filePath,
                            line: i + 1,
                            match_text: lines[i],
                            context: contextBlock
                        });
                    }
                }
            }

            function walkForContent(dir) {
                if (matches.length >= maxResults) return;
                let entries;
                try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
                for (const entry of entries) {
                    if (matches.length >= maxResults) break;
                    const fullPath = path.join(dir, entry.name);
                    if (entry.isDirectory()) {
                        walkForContent(fullPath);
                    } else {
                        if (!filePattern || filePattern.test(entry.name)) {
                            searchInFile(fullPath);
                        }
                    }
                }
            }

            const stat = fs.statSync(targetPath);
            if (stat.isDirectory()) {
                walkForContent(targetPath);
            } else {
                searchInFile(targetPath);
            }

            const summary = matches.length >= maxResults
                ? `（已达上限 ${maxResults} 条）`
                : `（共 ${matches.length} 条）`;
            return {
                content: [{
                    type: 'text',
                    text: `搜索 "${args.pattern}" 结果 ${summary}:\n${JSON.stringify(matches, null, 2)}`
                }]
            };
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
            serverInfo: { name: 'mcp-filesystem', version: '2.0.0' }
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
            // 在执行前将 args 中的路径字段 resolve 为绝对路径，
            // 确保 Kotlin 侧 McpFilePermissionHook 拿到的是绝对路径，能正确触发权限弹窗。
            const rawArgs = params.arguments || {};
            const normalizedArgs = Object.assign({}, rawArgs);
            for (const key of ['path', 'source', 'destination']) {
                if (typeof normalizedArgs[key] === 'string' && !path.isAbsolute(normalizedArgs[key])) {
                    normalizedArgs[key] = path.resolve(rootDir, normalizedArgs[key]);
                }
            }
            const result = tool.handler(normalizedArgs);
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
        process.stderr.write(`[mcp_filesystem] 解析错误: ${e.message}\n`);
    }
});

rl.on('close', () => {
    process.stderr.write(`[mcp_filesystem] readline 接口已关闭\n`);
});

process.stderr.write(`[mcp_filesystem] 已启动，根目录: ${rootDir}\n`);
