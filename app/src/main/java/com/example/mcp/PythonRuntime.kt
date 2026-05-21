package com.example.mcp

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "PythonRuntime"

/**
 * Python 运行时管理器（官方 Android 包方案）。
 *
 * 使用 Python 官方提供的 Android embeddable package：
 *   https://www.python.org/downloads/android/
 *
 * ── 集成步骤 ──────────────────────────────────────────────────────────────
 *
 * 1. 下载官方 Android 包（以 3.14.5 为例）：
 *    arm64: https://www.python.org/ftp/python/3.14.5/python-3.14.5-aarch64-linux-android.tar.gz
 *    x86_64: https://www.python.org/ftp/python/3.14.5/python-3.14.5-x86_64-linux-android.tar.gz
 *
 * 2. 解压 tar.gz，得到 prefix/ 目录，内部结构：
 *    prefix/lib/
 *      libpython3.14.so         ← 主解释器库
 *      libssl_python.so         ← OpenSSL（可选）
 *      libcrypto_python.so      ← OpenSSL（可选）
 *      python3.14/              ← 标准库
 *        site-packages/         ← 第三方包安装位置
 *
 * 3. 将 .so 文件放入 jniLibs（APK 打包时自动提取到 nativeLibraryDir）：
 *    app/src/main/jniLibs/arm64-v8a/libpython3.14.so
 *    app/src/main/jniLibs/arm64-v8a/libssl_python.so
 *    app/src/main/jniLibs/arm64-v8a/libcrypto_python.so
 *    app/src/main/jniLibs/x86_64/libpython3.14.so
 *    ...（x86_64 同理）
 *
 * 4. 将标准库打包为 zip 放入 assets：
 *    app/src/main/assets/python/stdlib.zip
 *      内容：python3.14/ 目录（即 prefix/lib/python3.14/）
 *
 *    打包命令（在 prefix/lib/ 目录下执行）：
 *      zip -r stdlib.zip python3.14/
 *
 * 5. 预装 mcp 等 Python 包到 site-packages，一起打包进 stdlib.zip：
 *    pip install --target=python3.14/site-packages mcp httpx anyio
 *    zip -r stdlib.zip python3.14/
 *
 * ── 运行时工作流程 ────────────────────────────────────────────────────────
 *
 * 首次启动：
 *   1. 从 assets/python/stdlib.zip 解压标准库到 filesDir/python/
 *   2. 通过 dlopen() 加载 nativeLibraryDir/libpython3.14.so
 *   3. 设置 PYTHONHOME = filesDir/python
 *   4. 调用 Py_Initialize() 初始化解释器
 *
 * 后续启动：
 *   1. 检查版本文件，如果版本匹配则跳过解压
 *   2. 直接加载 libpython 并初始化
 */
object PythonRuntime {

    // 解压目标目录
    private const val PYTHON_DIR = "python"
    private const val VERSION_FILE = "python/.stdlib_version"
    // 更新 assets 时同步修改此值
    private const val CURRENT_VERSION = "3.14.5-2"
    // libpython 的文件名（与下载的版本对应）
    private const val LIBPYTHON_NAME = "libpython3.14.so"

    @Volatile
    private var _isReady = false
    val isReady: Boolean get() = _isReady

    /**
     * 确保 Python 运行时已就绪。
     * 首次调用会从 assets 解压标准库并初始化解释器。
     *
     * @return true 表示就绪，false 表示不可用（assets 中没有 stdlib.zip 或 libpython 未找到）
     */
    suspend fun ensureReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (_isReady) return@withContext true

        // ── Step 1: 解压标准库 ────────────────────────────────────────────
        val stdlibReady = ensureStdlibExtracted(context)
        if (!stdlibReady) {
            Log.w(TAG, "Python 标准库未就绪，Python 功能不可用")
            return@withContext false
        }

        // ── Step 2: 加载 libpython ────────────────────────────────────────
        if (!PythonBridge.isLoaded) {
            Log.e(TAG, "python_bridge JNI 库未加载")
            return@withContext false
        }

        val libpythonPath = findLibpython(context)
        if (libpythonPath == null) {
            Log.w(TAG, "找不到 $LIBPYTHON_NAME，Python 功能不可用")
            Log.w(TAG, "请将 libpython3.14.so 放入 app/src/main/jniLibs/<ABI>/")
            return@withContext false
        }

        val loaded = PythonBridge.loadLibpython(libpythonPath)
        if (!loaded) {
            Log.e(TAG, "dlopen($libpythonPath) 失败")
            return@withContext false
        }

        // ── Step 3: 初始化解释器 ──────────────────────────────────────────
        val pythonHome = File(context.filesDir, PYTHON_DIR).absolutePath
        val initialized = PythonBridge.initialize(pythonHome)
        if (!initialized) {
            Log.e(TAG, "Py_Initialize() 失败")
            return@withContext false
        }

        _isReady = true
        Log.i(TAG, "Python 运行时就绪，PYTHONHOME=$pythonHome")
        true
    }

    /**
     * 从 assets/python/stdlib.zip 解压标准库到私有目录。
     */
    private fun ensureStdlibExtracted(context: Context): Boolean {
        val filesDir = context.filesDir
        val pythonDir = File(filesDir, PYTHON_DIR)
        val versionFile = File(filesDir, VERSION_FILE)

        // 检查是否需要重新解压
        val needsExtract = !pythonDir.exists() ||
                !versionFile.exists() ||
                versionFile.readText().trim() != CURRENT_VERSION

        if (!needsExtract) {
            Log.i(TAG, "Python 标准库已解压（版本 $CURRENT_VERSION）")
            return true
        }

        // 检查 assets 中是否有 stdlib.zip
        val hasAsset = try {
            context.assets.open("python/stdlib.zip").use { true }
        } catch (e: Exception) {
            false
        }

        if (!hasAsset) {
            Log.w(TAG, "assets/python/stdlib.zip 不存在")
            Log.w(TAG, "请按以下步骤准备：")
            Log.w(TAG, "1. 下载 https://www.python.org/ftp/python/3.14.5/python-3.14.5-aarch64-linux-android.tar.gz")
            Log.w(TAG, "2. 解压后在 prefix/lib/ 目录执行：zip -r stdlib.zip python3.14/")
            Log.w(TAG, "3. 将 stdlib.zip 放入 app/src/main/assets/python/stdlib.zip")
            return false
        }

        // 清理旧版本
        if (pythonDir.exists()) {
            pythonDir.deleteRecursively()
        }
        pythonDir.mkdirs()

        // Python 默认在 PYTHONHOME/lib/python3.x 下查找标准库
        // 我们将所有内容解压到 python/lib 目录下
        val libDir = File(pythonDir, "lib")
        libDir.mkdirs()

        Log.i(TAG, "开始解压 Python 标准库...")
        return try {
            context.assets.open("python/stdlib.zip").use { assetStream ->
                java.util.zip.ZipInputStream(assetStream).use { zip ->
                    var entry = zip.nextEntry
                    var count = 0
                    while (entry != null) {
                        // 确保解压到 lib 目录下
                        val outFile = File(libDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                            count++
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    Log.i(TAG, "解压完成，共 $count 个文件")
                }
            }
            versionFile.writeText(CURRENT_VERSION)
            true
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            false
        }
    }

    /**
     * 在 nativeLibraryDir 中查找 libpython3.*.so。
     * APK 安装时系统会自动将 jniLibs 中的 .so 提取到 nativeLibraryDir。
     */
    private fun findLibpython(context: Context): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        // 先找精确版本名，再找通配
        val candidates = listOf(
            "$nativeDir/$LIBPYTHON_NAME",
            "$nativeDir/libpython3.13.so",
            "$nativeDir/libpython3.12.so",
            "$nativeDir/libpython3.11.so"
        )
        val foundPath = candidates.firstOrNull { File(it).exists() }
            ?: File(nativeDir).listFiles()
                ?.firstOrNull { it.name.startsWith("libpython") && it.name.endsWith(".so") }
                ?.absolutePath

        if (foundPath != null) return foundPath

        // 备选方案：如果开启了 extractNativeLibs=false，文件可能不直接存在于 filesystem
        // 直接返回文件名，让 dlopen 在库搜索路径中寻找
        Log.i(TAG, "未在 $nativeDir 找到库文件，尝试通过库名直接加载: $LIBPYTHON_NAME")
        return LIBPYTHON_NAME
    }

    /**
     * 获取 Python 标准库路径（PYTHONHOME）
     */
    fun getPythonHome(context: Context): String =
        File(context.filesDir, PYTHON_DIR).absolutePath

    /**
     * 获取 site-packages 路径
     */
    fun getSitePackagesPath(context: Context): String =
        File(context.filesDir, "$PYTHON_DIR/lib/python3.14/site-packages").absolutePath

    /**
     * 获取当前设备支持的主 ABI
     */
    fun getSupportedAbi(): String {
        val supported = Build.SUPPORTED_ABIS.toList()
        return when {
            "arm64-v8a" in supported -> "arm64-v8a"
            "x86_64" in supported -> "x86_64"
            else -> supported.firstOrNull() ?: "arm64-v8a"
        }
    }
}
