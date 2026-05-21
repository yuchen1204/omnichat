#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <android/log.h>
#include <locale.h>
#include "node.h"

#define LOG_TAG "NodeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * 启动 Node.js 运行时。
 *
 * 参数 arguments 是一个 Java String[]，格式与命令行参数相同：
 *   arguments[0] = "node"
 *   arguments[1] = "/path/to/script.js"
 *   arguments[2..n] = 额外参数
 *
 * 注意：Node.js 运行时在整个进程生命周期内只能启动一次。
 * 调用此函数会阻塞当前线程直到 Node.js 进程退出。
 * 应在专用后台线程中调用。
 *
 * JNI 签名：com.example.mcp.NodeJsBridge.startNodeWithArguments([Ljava/lang/String;)I
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mcp_NodeJsBridge_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments) {

    jsize argument_count = env->GetArrayLength(arguments);
    if (argument_count == 0) {
        LOGE("startNodeWithArguments: 参数为空");
        return -1;
    }

    // 计算所有参数所需的连续内存大小
    int c_arguments_size = 0;
    for (int i = 0; i < argument_count; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *cstr = env->GetStringUTFChars(jstr, nullptr);
        c_arguments_size += (int) strlen(cstr) + 1; // +1 for '\0'
        env->ReleaseStringUTFChars(jstr, cstr);
        env->DeleteLocalRef(jstr);
    }

    // 在连续内存中存储所有参数
    char *args_buffer = (char *) calloc(c_arguments_size, sizeof(char));
    // argv 数组长度应为 argument_count + 1，末尾填 NULL，这是 main 函数的标准约定
    char **argv = (char **) malloc((argument_count + 1) * sizeof(char *));
    char *current_pos = args_buffer;

    for (int i = 0; i < argument_count; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *cstr = env->GetStringUTFChars(jstr, nullptr);
        strcpy(current_pos, cstr); // 使用 strcpy 更安全且自动包含 \0
        argv[i] = current_pos;
        current_pos += strlen(cstr) + 1;
        env->ReleaseStringUTFChars(jstr, cstr);
        env->DeleteLocalRef(jstr);
    }
    argv[argument_count] = nullptr; // 末尾置空

    LOGI("启动 Node.js，参数数量: %d，入口: %s", argument_count, argv[1]);

    // 解决 Android 上 std::regex 可能导致的 regex_error 崩溃
    // 强制设置 locale 为 "C"
    setlocale(LC_ALL, "C");
    setenv("LC_ALL", "C", 1);

    int result = node::Start(argument_count, argv);

    free(argv);
    free(args_buffer);

    LOGI("Node.js 退出，返回码: %d", result);
    return (jint) result;
}

/**
 * 检查 libnode.so 是否已成功加载（用于运行时检测）
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mcp_NodeJsBridge_isNodeAvailable(
        JNIEnv *env,
        jobject /* this */) {
    // 如果能调用到这里，说明 libnode.so 已加载
    return JNI_TRUE;
}
