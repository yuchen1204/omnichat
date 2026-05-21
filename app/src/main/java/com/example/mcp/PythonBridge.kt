package com.example.mcp

import android.util.Log

/**
 * Python JNI 桥接。
 *
 * 对应 C++ 实现在 app/src/main/cpp/python_bridge.cpp。
 *
 * 使用官方 Python Android 包（python.org 提供）：
 *   https://www.python.org/downloads/android/
 *
 * 包结构（解压 tar.gz 后）：
 *   prefix/
 *     lib/
 *       libpython3.14.so        → 放入 app/src/main/jniLibs/<ABI>/
 *       libssl_python.so        → 放入 app/src/main/jniLibs/<ABI>/
 *       libcrypto_python.so     → 放入 app/src/main/jniLibs/<ABI>/
 *       python3.14/             → 放入 app/src/main/assets/python/lib/
 *         site-packages/        → 在此目录预装 mcp 等包
 *
 * 注意：libpython 通过 dlopen() 动态加载，不需要编译时链接。
 * 这样即使没有放置 libpython，APK 也能正常安装。
 */
object PythonBridge {

    private const val TAG = "PythonBridge"

    var isLoaded: Boolean = false
        private set

    @Volatile
    var isInitialized: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("python_bridge")
            isLoaded = true
            Log.i(TAG, "python_bridge JNI 库加载成功")
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
            Log.e(TAG, "python_bridge JNI 库加载失败: ${e.message}")
        }
    }

    /**
     * 动态加载 libpython3.*.so。
     * @param libPath libpython 的绝对路径（通常在 nativeLibraryDir 下）
     * @return true 表示加载成功
     */
    fun loadLibpython(libPath: String): Boolean {
        if (!isLoaded) return false
        val result = nativeLoadLibpython(libPath)
        Log.i(TAG, "loadLibpython($libPath) = $result")
        return result
    }

    /**
     * 初始化 Python 解释器。
     * @param pythonHome Python 标准库根目录（assets 解压后的路径）
     * @return true 表示初始化成功
     */
    fun initialize(pythonHome: String): Boolean {
        if (!isLoaded) return false
        if (isInitialized) return true
        val result = nativeInitializePython(pythonHome)
        if (result) isInitialized = true
        return result
    }

    /**
     * 执行一段 Python 代码。
     * @return 0 表示成功，非 0 表示有异常
     */
    fun runCode(code: String): Int {
        if (!isInitialized) return -1
        return nativeRunPythonString(code)
    }

    // ── JNI 声明 ──────────────────────────────────────────────────────────

    private external fun nativeLoadLibpython(libPath: String): Boolean
    private external fun nativeInitializePython(pythonHome: String): Boolean
    private external fun nativeRunPythonString(code: String): Int
    external fun isPythonInitialized(): Boolean
}
