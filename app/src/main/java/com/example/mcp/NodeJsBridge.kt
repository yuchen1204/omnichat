package com.example.mcp

import android.util.Log

/**
 * Node.js JNI 桥接。
 *
 * 对应 C++ 实现在 app/src/main/cpp/node_bridge.cpp。
 * libnode.so 来自 nodejs-mobile 项目：
 *   https://github.com/nodejs-mobile/nodejs-mobile/releases
 *
 * 重要限制（来自 nodejs-mobile 官方文档）：
 *   - 整个进程生命周期内只能启动一次 Node.js 运行时
 *   - 启动后无法重启
 *   - startNodeWithArguments() 会阻塞调用线程直到 Node.js 退出
 *   - 必须在专用后台线程中调用
 */
object NodeJsBridge {

    private const val TAG = "NodeJsBridge"

    // 是否已成功加载 native 库
    var isLoaded: Boolean = false
        private set

    // Node.js 是否已经启动过（只能启动一次）
    @Volatile
    var hasStarted: Boolean = false
        private set

    init {
        try {
            // 先加载 libnode.so（被 node_bridge 依赖）
            System.loadLibrary("node")
            // 再加载我们的 JNI 桥接库
            System.loadLibrary("node_bridge")
            isLoaded = true
            Log.i(TAG, "Node.js native 库加载成功")
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
            Log.e(TAG, "Node.js native 库加载失败: ${e.message}")
            Log.e(TAG, "请确认已将 libnode.so 放入 app/src/main/jniLibs/<ABI>/ 目录")
        }
    }

    /**
     * 启动 Node.js 运行时并执行指定脚本。
     * 此方法会阻塞直到 Node.js 进程退出，必须在后台线程调用。
     *
     * @param scriptPath 要执行的 JS 脚本绝对路径
     * @param extraArgs  额外的命令行参数
     * @return Node.js 退出码，-1 表示未能启动
     */
    fun startScript(scriptPath: String, vararg extraArgs: String): Int {
        if (!isLoaded) {
            Log.e(TAG, "Node.js 库未加载，无法启动")
            return -1
        }
        if (hasStarted) {
            Log.e(TAG, "Node.js 已经启动过，不能重复启动（nodejs-mobile 限制）")
            return -1
        }
        hasStarted = true
        val args = arrayOf("node", scriptPath) + extraArgs
        return startNodeWithArguments(args)
    }

    /**
     * 执行一段内联 JS 代码（通过 -e 参数）。
     * 同样只能调用一次，且会阻塞。
     */
    fun evalScript(jsCode: String): Int {
        if (!isLoaded) return -1
        if (hasStarted) return -1
        hasStarted = true
        return startNodeWithArguments(arrayOf("node", "-e", jsCode))
    }

    // ── JNI 声明 ──────────────────────────────────────────────────────────

    /**
     * 启动 Node.js，arguments[0] 必须是 "node"。
     * 阻塞直到 Node.js 退出。
     */
    private external fun startNodeWithArguments(arguments: Array<String>): Int

    /**
     * 检测 libnode.so 是否可用（用于 UI 状态显示）
     */
    external fun isNodeAvailable(): Boolean
}
