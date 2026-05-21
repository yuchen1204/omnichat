cmake_minimum_required(VERSION 3.22.1)
project("omnichat_bridges")

# ── 引入 libnode.so 预编译库（nodejs-mobile） ─────────────────────────────
# 下载：https://github.com/nodejs-mobile/nodejs-mobile/releases
# 放置：app/src/main/jniLibs/<ABI>/libnode.so
# 头文件：app/src/main/cpp/node_include/node/node.h

add_library(libnode SHARED IMPORTED)
set_target_properties(
    libnode
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so
)

# ── Node.js JNI 桥接库 ────────────────────────────────────────────────────
add_library(node_bridge SHARED node_bridge.cpp)

target_include_directories(node_bridge PRIVATE
    ${CMAKE_SOURCE_DIR}/node_include/node/
)

find_library(log-lib log)
target_link_libraries(node_bridge libnode ${log-lib})

# ── Python JNI 桥接库 ─────────────────────────────────────────────────────
# libpython3.*.so 在运行时通过 dlopen() 动态加载，不需要编译时链接。
# 这样即使 libpython 不存在，APK 也能正常安装和运行（只是 Python 功能不可用）。

add_library(python_bridge SHARED python_bridge.cpp)

target_link_libraries(python_bridge ${log-lib})
# dl 库提供 dlopen/dlsym/dlclose
target_link_libraries(python_bridge dl)
