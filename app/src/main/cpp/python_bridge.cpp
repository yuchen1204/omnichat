#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <android/log.h>
#include <dlfcn.h>
#include <locale.h>

#define LOG_TAG "PythonBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Python C API 函数指针（动态加载，避免编译时依赖 libpython） ──────────────

typedef void (*Py_SetProgramName_t)(const wchar_t*);
typedef void (*Py_SetPythonHome_t)(const wchar_t*);
typedef void (*Py_SetPath_t)(const wchar_t*);
typedef void (*Py_Initialize_t)();
typedef void (*Py_Finalize_t)();
typedef void (*Py_InitializeEx_t)(int);
typedef int  (*PyRun_SimpleString_t)(const char*);
typedef int  (*Py_IsInitialized_t)();
typedef void (*PySys_SetArgvEx_t)(int, wchar_t**, int);

// GIL 管理相关
typedef enum { PyGILState_LOCKED, PyGILState_UNLOCKED } PyGILState_STATE;
typedef PyGILState_STATE (*PyGILState_Ensure_t)();
typedef void (*PyGILState_Release_t)(PyGILState_STATE);
typedef void* (*PyEval_SaveThread_t)();

static void* libpython_handle = nullptr;
static Py_Initialize_t      fn_Py_Initialize      = nullptr;
static Py_InitializeEx_t    fn_Py_InitializeEx    = nullptr;
static Py_Finalize_t        fn_Py_Finalize        = nullptr;
static PyRun_SimpleString_t fn_PyRun_SimpleString = nullptr;
static Py_IsInitialized_t   fn_Py_IsInitialized   = nullptr;
static Py_SetPythonHome_t   fn_Py_SetPythonHome   = nullptr;
static Py_SetPath_t         fn_Py_SetPath         = nullptr;
static PySys_SetArgvEx_t    fn_PySys_SetArgvEx    = nullptr;

static PyGILState_Ensure_t  fn_PyGILState_Ensure  = nullptr;
static PyGILState_Release_t fn_PyGILState_Release = nullptr;
static PyEval_SaveThread_t  fn_PyEval_SaveThread  = nullptr;

static bool python_initialized = false;

/**
 * 将 Java String 转换为 wchar_t*（调用方负责 free）
 */
static wchar_t* jstring_to_wchar(JNIEnv* env, jstring jstr) {
    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    size_t len = strlen(cstr);
    wchar_t* wstr = (wchar_t*) malloc((len + 1) * sizeof(wchar_t));
    mbstowcs(wstr, cstr, len + 1);
    env->ReleaseStringUTFChars(jstr, cstr);
    return wstr;
}

/**
 * 动态加载 libpython3.*.so
 * 返回 true 表示加载成功
 *
 * JNI 签名：com.example.mcp.PythonBridge.loadLibpython(String)Z
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mcp_PythonBridge_nativeLoadLibpython(
        JNIEnv* env,
        jobject /* this */,
        jstring libPath) {

    if (libpython_handle != nullptr) {
        LOGI("libpython 已加载");
        return JNI_TRUE;
    }

    const char* path = env->GetStringUTFChars(libPath, nullptr);
    std::string libDir;
    std::string fullPath(path);
    size_t lastSlash = fullPath.find_last_of('/');
    if (lastSlash != std::string::npos) {
        libDir = fullPath.substr(0, lastSlash + 1);
    }

    // 尝试预加载依赖库，这些库通常在同一目录下
    const char* dependencies[] = {
        "libcrypto_python.so",
        "libssl_python.so",
        "libsqlite3_python.so"
    };

    for (const char* dep : dependencies) {
        std::string depPath = libDir + dep;
        // 恢复为 RTLD_GLOBAL，因为 Python 扩展通常需要全局符号
        void* depHandle = dlopen(depPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (depHandle) {
            LOGI("预加载依赖库成功: %s", depPath.c_str());
        }
    }

    LOGI("加载 libpython: %s", path);
    libpython_handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    env->ReleaseStringUTFChars(libPath, path);

    if (libpython_handle == nullptr) {
        LOGE("dlopen 失败: %s", dlerror());
        return JNI_FALSE;
    }

    // 解析函数指针
    fn_Py_Initialize      = (Py_Initialize_t)      dlsym(libpython_handle, "Py_Initialize");
    fn_Py_InitializeEx    = (Py_InitializeEx_t)    dlsym(libpython_handle, "Py_InitializeEx");
    fn_Py_Finalize        = (Py_Finalize_t)        dlsym(libpython_handle, "Py_Finalize");
    fn_PyRun_SimpleString = (PyRun_SimpleString_t) dlsym(libpython_handle, "PyRun_SimpleString");
    fn_Py_IsInitialized   = (Py_IsInitialized_t)   dlsym(libpython_handle, "Py_IsInitialized");
    fn_Py_SetPythonHome   = (Py_SetPythonHome_t)   dlsym(libpython_handle, "Py_SetPythonHome");
    fn_Py_SetPath         = (Py_SetPath_t)         dlsym(libpython_handle, "Py_SetPath");
    fn_PySys_SetArgvEx    = (PySys_SetArgvEx_t)    dlsym(libpython_handle, "PySys_SetArgvEx");

    fn_PyGILState_Ensure  = (PyGILState_Ensure_t)  dlsym(libpython_handle, "PyGILState_Ensure");
    fn_PyGILState_Release = (PyGILState_Release_t) dlsym(libpython_handle, "PyGILState_Release");
    fn_PyEval_SaveThread  = (PyEval_SaveThread_t)  dlsym(libpython_handle, "PyEval_SaveThread");

    if (!fn_Py_Initialize || !fn_PyRun_SimpleString) {
        LOGE("无法解析 Python 函数符号");
        dlclose(libpython_handle);
        libpython_handle = nullptr;
        return JNI_FALSE;
    }

    LOGI("libpython 加载成功");
    return JNI_TRUE;
}

/**
 * 初始化 Python 解释器
 *
 * @param pythonHome  Python 标准库根目录（assets 解压后的路径）
 * JNI 签名：com.example.mcp.PythonBridge.initializePython(String)Z
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mcp_PythonBridge_nativeInitializePython(
        JNIEnv* env,
        jobject /* this */,
        jstring pythonHome) {

    if (libpython_handle == nullptr) {
        LOGE("libpython 未加载，请先调用 loadLibpython()");
        return JNI_FALSE;
    }

    if (python_initialized) {
        LOGI("Python 已初始化");
        return JNI_TRUE;
    }

    // 解决 Android 上某些 native 库可能导致的 std::regex_error 崩溃
    setlocale(LC_ALL, "C");
    setenv("LC_ALL", "C", 1);

    wchar_t* home = jstring_to_wchar(env, pythonHome);
    LOGI("设置 PYTHONHOME: %ls", home);

    if (fn_Py_SetPythonHome) {
        fn_Py_SetPythonHome(home);
    }

    // 显式设置 PYTHONPATH 以确保能找到标准库和 site-packages
    // 路径格式：HOME/lib/python3.14:HOME/lib/python3.14/site-packages
    std::wstring wHome(home);
    std::wstring wPath = wHome + L"/lib/python3.14:" + wHome + L"/lib/python3.14/site-packages";
    LOGI("设置 PYTHONPATH: %ls", wPath.c_str());
    if (fn_Py_SetPath) {
        fn_Py_SetPath(wPath.c_str());
    }

    LOGI("初始化 Python 解释器...");
    if (fn_Py_InitializeEx) {
        fn_Py_InitializeEx(0); // 0 表示不安装信号句柄，对 Android 更友好
    } else {
        fn_Py_Initialize();
    }
    python_initialized = true;

    // 初始化完成后，立即释放 GIL，以便其他线程（如 MCP 工作线程）可以获取它
    if (fn_PyEval_SaveThread) {
        fn_PyEval_SaveThread();
        LOGI("Python 初始化完成，已释放 GIL");
    }

    free(home);
    LOGI("Python 解释器初始化完成");
    return JNI_TRUE;
}

/**
 * 执行一段 Python 代码字符串
 * 返回 0 表示成功，非 0 表示有异常
 *
 * JNI 签名：com.example.mcp.PythonBridge.runPythonString(String)I
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mcp_PythonBridge_nativeRunPythonString(
        JNIEnv* env,
        jobject /* this */,
        jstring code) {

    if (!python_initialized || !fn_PyRun_SimpleString) {
        LOGE("Python 未初始化");
        return -1;
    }

    // 确保当前线程持有 GIL
    PyGILState_STATE gstate;
    bool has_gil_api = (fn_PyGILState_Ensure && fn_PyGILState_Release);
    if (has_gil_api) {
        gstate = fn_PyGILState_Ensure();
    }

    const char* ccode = env->GetStringUTFChars(code, nullptr);
    LOGI("执行 Python 代码 (前50字符): %.50s", ccode);
    int result = fn_PyRun_SimpleString(ccode);
    env->ReleaseStringUTFChars(code, ccode);

    if (has_gil_api) {
        fn_PyGILState_Release(gstate);
    }

    return (jint) result;
}

/**
 * 检查 Python 是否已初始化
 *
 * JNI 签名：com.example.mcp.PythonBridge.isPythonInitialized()Z
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mcp_PythonBridge_isPythonInitialized(
        JNIEnv* env,
        jobject /* this */) {
    return python_initialized ? JNI_TRUE : JNI_FALSE;
}
