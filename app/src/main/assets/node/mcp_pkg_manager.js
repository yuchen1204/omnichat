#!/usr/bin/env node
/**
 * MCP Package Manager - 轻量级 npm 客户端
 * 
 * 只使用 Node.js 内置模块，支持：
 * - 解析 package.json 依赖
 * - 从 npm registry 下载 tarball
 * - 解压到 node_modules
 * - 支持版本范围解析
 */

'use strict';

const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { URL } = require('url');
const readline = require('readline');

// 捕获初始流
const stdout = process.stdout;
const stdin = process.stdin;

// npm registry 配置
const REGISTRY = 'https://registry.npmjs.org';
const CACHE_DIR = '.npm-cache';

// ── HTTP 请求工具 ──────────────────────────────────────────────────────────

function fetch(url, options = {}) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(url);
        const lib = parsed.protocol === 'https:' ? https : http;
        const method = (options.method || 'GET').toUpperCase();
        const headers = {
            'User-Agent': 'OmniChat-PkgManager/1.0.0',
            'Accept': 'application/json',
            ...options.headers
        };

        const req = lib.request({
            hostname: parsed.hostname,
            port: parsed.port || (parsed.protocol === 'https:' ? 443 : 80),
            path: parsed.pathname + parsed.search,
            method,
            headers
        }, (res) => {
            // 处理重定向
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                return fetch(res.headers.location, options).then(resolve).catch(reject);
            }

            const chunks = [];
            res.on('data', chunk => chunks.push(chunk));
            res.on('end', () => {
                const body = Buffer.concat(chunks);
                resolve({
                    status: res.statusCode,
                    headers: res.headers,
                    body
                });
            });
        });

        req.on('error', reject);
        req.setTimeout(60000, () => { req.destroy(); reject(new Error('请求超时')); });

        if (options.body) req.write(options.body);
        req.end();
    });
}

// ── tar 解压实现（最小化） ──────────────────────────────────────────────────

/**
 * 解析 tar header 并提取文件
 * 注意：这是一个简化的 tar 解析器，只支持 ustar 格式
 */
function extractTar(buffer, destDir) {
    let offset = 0;
    const files = [];

    while (offset < buffer.length - 512) {
        // 检查是否是全零块（tar 结束标志）
        const header = buffer.slice(offset, offset + 512);
        if (header.every(b => b === 0)) break;

        // 解析文件名（支持长文件名 extension）
        let name = header.slice(0, 100).toString('utf8').replace(/\0/g, '');
        
        // 检查 ustar 标志
        const ustar = header.slice(257, 263).toString('utf8');
        if (ustar === 'ustar\x00') {
            const prefix = header.slice(345, 500).toString('utf8').replace(/\0/g, '');
            if (prefix) name = prefix + '/' + name;
        }

        // 文件类型
        const typeFlag = header[156];
        
        // 文件大小
        const sizeStr = header.slice(124, 136).toString('utf8').trim().replace(/\0/g, '');
        const size = parseInt(sizeStr, 8) || 0;

        // 跳过 header
        offset += 512;

        // 处理文件内容
        if (typeFlag === 0 || typeFlag === 48 || typeFlag === undefined) { // 普通文件
            if (name && size > 0) {
                const filePath = path.join(destDir, name);
                const dir = path.dirname(filePath);
                
                // 确保目录存在
                fs.mkdirSync(dir, { recursive: true });
                
                // 写入文件
                const fileData = buffer.slice(offset, offset + size);
                fs.writeFileSync(filePath, fileData);
                files.push(name);
            }
        } else if (typeFlag === 53) { // 目录
            if (name) {
                const dirPath = path.join(destDir, name);
                fs.mkdirSync(dirPath, { recursive: true });
            }
        }

        // 移动到下一个 512 字节边界
        offset += Math.ceil(size / 512) * 512;
    }

    return files;
}

// ── 版本解析 ──────────────────────────────────────────────────────────────

/**
 * 简单的 semver 范围匹配
 * 支持：^1.0.0, ~1.0.0, >=1.0.0, 1.0.0, latest
 */
function satisfiesVersion(version, range) {
    // latest 特殊处理
    if (range === 'latest' || range === '*') return true;

    // 精确匹配
    if (version === range) return true;

    // 解析版本号
    const parseVer = (v) => {
        const match = v.match(/^(\d+)\.(\d+)\.(\d+)/);
        if (!match) return null;
        return {
            major: parseInt(match[1]),
            minor: parseInt(match[2]),
            patch: parseInt(match[3])
        };
    };

    const ver = parseVer(version);
    if (!ver) return false;

    // ^1.2.3 -> >=1.2.3 <2.0.0
    if (range.startsWith('^')) {
        const min = parseVer(range.slice(1));
        if (!min) return false;
        if (ver.major !== min.major) return ver.major > min.major;
        if (ver.minor !== min.minor) return ver.minor > min.minor;
        return ver.patch >= min.patch;
    }

    // ~1.2.3 -> >=1.2.3 <1.3.0
    if (range.startsWith('~')) {
        const min = parseVer(range.slice(1));
        if (!min) return false;
        if (ver.major !== min.major) return ver.major > min.major;
        if (ver.minor !== min.minor) return ver.minor > min.minor;
        return ver.patch >= min.patch;
    }

    // >=1.2.3
    if (range.startsWith('>=')) {
        const min = parseVer(range.slice(2));
        if (!min) return false;
        if (ver.major !== min.major) return ver.major > min.major;
        if (ver.minor !== min.minor) return ver.minor > min.minor;
        return ver.patch >= min.patch;
    }

    // >1.2.3
    if (range.startsWith('>')) {
        const min = parseVer(range.slice(1));
        if (!min) return false;
        if (ver.major !== min.major) return ver.major > min.major;
        if (ver.minor !== min.minor) return ver.minor > min.minor;
        return ver.patch > min.patch;
    }

    // <=1.2.3
    if (range.startsWith('<=')) {
        const max = parseVer(range.slice(2));
        if (!max) return false;
        if (ver.major !== max.major) return ver.major < max.major;
        if (ver.minor !== max.minor) return ver.minor < max.minor;
        return ver.patch <= max.patch;
    }

    // <1.2.3
    if (range.startsWith('<')) {
        const max = parseVer(range.slice(1));
        if (!max) return false;
        if (ver.major !== max.major) return ver.major < max.major;
        if (ver.minor !== max.minor) return ver.minor < max.minor;
        return ver.patch < max.patch;
    }

    // 1.2.x 或 1.x.x
    if (range.includes('x') || range.includes('X')) {
        const parts = range.split('.');
        const verParts = version.split('.');
        for (let i = 0; i < parts.length; i++) {
            if (parts[i] === 'x' || parts[i] === 'X') continue;
            if (parts[i] !== verParts[i]) return false;
        }
        return true;
    }

    return false;
}

/**
 * 从版本列表中选择最佳版本
 */
function bestVersion(versions, range) {
    if (!versions || versions.length === 0) return null;
    
    // 过滤满足条件的版本
    const satisfying = versions.filter(v => satisfiesVersion(v, range));
    if (satisfying.length === 0) return null;

    // 按版本号排序，取最新
    satisfying.sort((a, b) => {
        const pa = a.split('.').map(Number);
        const pb = b.split('.').map(Number);
        for (let i = 0; i < 3; i++) {
            if (pa[i] !== pb[i]) return pb[i] - pa[i];
        }
        return 0;
    });

    return satisfying[0];
}

// ── npm registry 操作 ──────────────────────────────────────────────────────

/**
 * 获取包的元数据
 */
async function getPackageMeta(packageName) {
    const cacheFile = path.join(CACHE_DIR, encodeURIComponent(packageName) + '.json');
    
    // 检查缓存（1小时有效）
    if (fs.existsSync(cacheFile)) {
        const stat = fs.statSync(cacheFile);
        if (Date.now() - stat.mtimeMs < 3600000) {
            return JSON.parse(fs.readFileSync(cacheFile, 'utf8'));
        }
    }

    const url = `${REGISTRY}/${encodeURIComponent(packageName).replace('%40', '@')}`;
    const res = await fetch(url);
    
    if (res.status === 404) {
        throw new Error(`包不存在: ${packageName}`);
    }
    if (res.status !== 200) {
        throw new Error(`获取包信息失败: HTTP ${res.status}`);
    }

    const meta = JSON.parse(res.body.toString('utf8'));
    
    // 缓存
    fs.mkdirSync(CACHE_DIR, { recursive: true });
    fs.writeFileSync(cacheFile, JSON.stringify(meta, null, 2));

    return meta;
}

/**
 * 下载并解压 tarball
 */
async function downloadAndExtract(tarballUrl, destDir) {
    process.stderr.write(`[pkg] 下载: ${tarballUrl}\n`);
    
    const res = await fetch(tarballUrl);
    if (res.status !== 200) {
        throw new Error(`下载失败: HTTP ${res.status}`);
    }

    // 解压 gzip
    process.stderr.write(`[pkg] 解压到: ${destDir}\n`);
    const decompressed = zlib.gunzipSync(res.body);
    
    // 解压 tar
    const files = extractTar(decompressed, destDir);
    process.stderr.write(`[pkg] 已安装 ${files.length} 个文件\n`);
    
    return files;
}

// ── 核心安装逻辑 ──────────────────────────────────────────────────────────

/**
 * 安装单个包
 */
async function installPackage(packageName, versionRange, installDir, installed = new Set()) {
    const key = `${packageName}@${versionRange}`;
    if (installed.has(key)) return;
    installed.add(key);

    process.stderr.write(`[pkg] 安装 ${packageName}@${versionRange}\n`);

    // 获取包元数据
    const meta = await getPackageMeta(packageName);
    
    // 选择版本
    const version = bestVersion(Object.keys(meta.versions), versionRange);
    if (!version) {
        throw new Error(`找不到满足条件的版本: ${packageName}@${versionRange}`);
    }

    const versionMeta = meta.versions[version];
    process.stderr.write(`[pkg] 选择版本: ${version}\n`);

    // 下载并解压
    const destDir = path.join(installDir, packageName);
    await downloadAndExtract(versionMeta.dist.tarball, destDir);

    // 写入 package.json（用于后续引用）
    const pkgJson = {
        name: packageName,
        version: version,
        main: versionMeta.main || 'index.js'
    };
    fs.writeFileSync(path.join(destDir, 'package.json'), JSON.stringify(pkgJson, null, 2));

    // 递归安装依赖
    const deps = versionMeta.dependencies || {};
    const depEntries = Object.entries(deps);
    
    if (depEntries.length > 0) {
        process.stderr.write(`[pkg] 安装 ${depEntries.length} 个依赖...\n`);
        for (const [depName, depRange] of depEntries) {
            await installPackage(depName, depRange, installDir, installed);
        }
    }

    return { name: packageName, version, dependencies: depEntries.length };
}

/**
 * 从 package.json 安装所有依赖
 */
async function installFromPackageJson(projectDir) {
    const pkgJsonPath = path.join(projectDir, 'package.json');
    
    if (!fs.existsSync(pkgJsonPath)) {
        throw new Error('找不到 package.json');
    }

    const pkgJson = JSON.parse(fs.readFileSync(pkgJsonPath, 'utf8'));
    const deps = pkgJson.dependencies || {};
    const devDeps = pkgJson.devDependencies || {};
    
    const allDeps = { ...deps, ...devDeps };
    const entries = Object.entries(allDeps);

    if (entries.length === 0) {
        return { message: '没有需要安装的依赖', installed: [] };
    }

    const installDir = path.join(projectDir, 'node_modules');
    fs.mkdirSync(installDir, { recursive: true });

    const installed = new Set();
    const results = [];

    for (const [name, range] of entries) {
        try {
            const result = await installPackage(name, range, installDir, installed);
            if (result) results.push(result);
        } catch (e) {
            process.stderr.write(`[pkg] 安装 ${name} 失败: ${e.message}\n`);
            results.push({ name, error: e.message });
        }
    }

    return {
        message: `安装完成，共 ${results.length} 个包`,
        installed: results
    };
}

/**
 * 安装指定的包（npm install <pkg>）
 */
async function installSpecific(packageSpec, projectDir) {
    // 解析包名和版本
    let name, range;
    
    if (packageSpec.includes('@') && !packageSpec.startsWith('@')) {
        const parts = packageSpec.split('@');
        name = parts[0];
        range = parts[1] || 'latest';
    } else if (packageSpec.startsWith('@')) {
        // scoped package: @scope/name@version
        const lastAt = packageSpec.lastIndexOf('@');
        if (lastAt > 0) {
            name = packageSpec.slice(0, lastAt);
            range = packageSpec.slice(lastAt + 1);
        } else {
            name = packageSpec;
            range = 'latest';
        }
    } else {
        name = packageSpec;
        range = 'latest';
    }

    const installDir = path.join(projectDir, 'node_modules');
    fs.mkdirSync(installDir, { recursive: true });

    const result = await installPackage(name, range, installDir);

    // 更新 package.json
    const pkgJsonPath = path.join(projectDir, 'package.json');
    let pkgJson = {};
    if (fs.existsSync(pkgJsonPath)) {
        pkgJson = JSON.parse(fs.readFileSync(pkgJsonPath, 'utf8'));
    }
    if (!pkgJson.dependencies) pkgJson.dependencies = {};
    pkgJson.dependencies[name] = range;
    fs.writeFileSync(pkgJsonPath, JSON.stringify(pkgJson, null, 2));

    return {
        message: `已安装 ${name}@${result.version}`,
        ...result
    };
}

/**
 * 列出已安装的包
 */
function listInstalled(projectDir) {
    const nodeModulesDir = path.join(projectDir, 'node_modules');
    
    if (!fs.existsSync(nodeModulesDir)) {
        return { packages: [] };
    }

    const packages = [];
    const entries = fs.readdirSync(nodeModulesDir);

    for (const entry of entries) {
        if (entry.startsWith('.')) continue;

        const pkgDir = path.join(nodeModulesDir, entry);
        const pkgJsonPath = path.join(pkgDir, 'package.json');

        // 处理 scoped packages
        if (entry.startsWith('@')) {
            const scopedEntries = fs.readdirSync(pkgDir);
            for (const scopedEntry of scopedEntries) {
                const scopedPkgJsonPath = path.join(pkgDir, scopedEntry, 'package.json');
                if (fs.existsSync(scopedPkgJsonPath)) {
                    const pkgJson = JSON.parse(fs.readFileSync(scopedPkgJsonPath, 'utf8'));
                    packages.push({
                        name: `${entry}/${scopedEntry}`,
                        version: pkgJson.version
                    });
                }
            }
        } else if (fs.existsSync(pkgJsonPath)) {
            const pkgJson = JSON.parse(fs.readFileSync(pkgJsonPath, 'utf8'));
            packages.push({
                name: pkgJson.name || entry,
                version: pkgJson.version
            });
        }
    }

    return { packages };
}

// ── JSON-RPC 工具函数 ─────────────────────────────────────────────────────

function sendResponse(id, result) {
    stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function sendError(id, code, message) {
    stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n');
}

// ── MCP 工具定义 ──────────────────────────────────────────────────────────

const tools = {
    npm_install: {
        description: '安装 npm 包到指定目录',
        inputSchema: {
            type: 'object',
            properties: {
                package: { 
                    type: 'string', 
                    description: '包名（可带版本，如 lodash@^4.0.0）' 
                },
                directory: { 
                    type: 'string', 
                    description: '项目目录（包含 package.json）' 
                },
                save: { 
                    type: 'boolean', 
                    description: '是否保存到 package.json', 
                    default: true 
                }
            },
            required: ['package', 'directory']
        },
        async handler(args) {
            const result = await installSpecific(args.package, args.directory);
            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify(result, null, 2) 
                }] 
            };
        }
    },

    npm_install_all: {
        description: '安装 package.json 中的所有依赖',
        inputSchema: {
            type: 'object',
            properties: {
                directory: { 
                    type: 'string', 
                    description: '项目目录（包含 package.json）' 
                }
            },
            required: ['directory']
        },
        async handler(args) {
            const result = await installFromPackageJson(args.directory);
            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify(result, null, 2) 
                }] 
            };
        }
    },

    npm_list: {
        description: '列出已安装的包',
        inputSchema: {
            type: 'object',
            properties: {
                directory: { 
                    type: 'string', 
                    description: '项目目录' 
                }
            },
            required: ['directory']
        },
        handler(args) {
            const result = listInstalled(args.directory);
            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify(result, null, 2) 
                }] 
            };
        }
    },

    npm_info: {
        description: '获取包的信息（版本、依赖等）',
        inputSchema: {
            type: 'object',
            properties: {
                package: { 
                    type: 'string', 
                    description: '包名' 
                }
            },
            required: ['package']
        },
        async handler(args) {
            const meta = await getPackageMeta(args.package);
            const latest = meta['dist-tags']?.latest;
            const latestMeta = latest ? meta.versions[latest] : null;

            return { 
                content: [{ 
                    type: 'text', 
                    text: JSON.stringify({
                        name: meta.name,
                        description: meta.description,
                        latest: latest,
                        versions: Object.keys(meta.versions).slice(-10),
                        dependencies: latestMeta?.dependencies || {},
                        homepage: meta.homepage
                    }, null, 2) 
                }] 
            };
        }
    }
};

// ── MCP 协议处理 ──────────────────────────────────────────────────────────

async function handleRequest(req) {
    const { id, method, params } = req;

    if (method === 'exit') {
        process.stderr.write(`[mcp_pkg] 收到退出指令\n`);
        rl.close();
        return;
    }

    if (method === 'initialize') {
        sendResponse(id, {
            protocolVersion: '2024-11-05',
            capabilities: { tools: {} },
            serverInfo: { name: 'mcp-pkg-manager', version: '1.0.0' }
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
            process.stderr.write(`[mcp_pkg] 处理错误: ${e.message}\n`);
        });
    } catch (e) {
        process.stderr.write(`[mcp_pkg] 解析错误: ${e.message}\n`);
    }
});

rl.on('close', () => {
    process.stderr.write(`[mcp_pkg] readline 接口已关闭\n`);
});

process.stderr.write('[mcp_pkg] 包管理器已启动\n');
