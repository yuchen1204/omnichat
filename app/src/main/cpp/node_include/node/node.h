/**
 * node.h — 占位文件
 *
 * 这是一个占位文件。实际的 node.h 需要从 nodejs-mobile 发布包中获取。
 *
 * 下载地址：https://github.com/nodejs-mobile/nodejs-mobile/releases
 * 下载 nodejs-mobile-v*.zip，解压后将 include/ 目录复制到：
 *   app/src/main/cpp/node_include/
 *
 * 正确的 node.h 应包含 node::Start() 函数声明。
 */

#pragma once

#ifndef NODE_H_PLACEHOLDER
#define NODE_H_PLACEHOLDER

#include <stddef.h>

namespace node {
    /**
     * 启动 Node.js 运行时。
     * 此函数会阻塞直到 Node.js 进程退出。
     * 在整个进程生命周期内只能调用一次。
     */
    int Start(int argc, char* argv[]);
}

#endif // NODE_H_PLACEHOLDER
