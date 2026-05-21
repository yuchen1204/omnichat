# 预编译 Native 库放置说明

## 1. Node.js 运行时（libnode.so）

**来源：** https://github.com/nodejs-mobile/nodejs-mobile/releases

下载最新的 `nodejs-mobile-v*.zip`，解压后：

```
app/src/main/jniLibs/
  arm64-v8a/
    libnode.so          ← 约 30MB
  x86_64/
    libnode.so          ← 约 35MB
```

同时将 zip 包中的 `include/` 目录复制到（替换占位文件）：
```
app/src/main/cpp/node_include/
```

---

## 2. Python 运行时（libpython3.14.so）

**来源：** https://www.python.org/downloads/android/

下载（以 3.14.5 为例）：
- arm64: `python-3.14.5-aarch64-linux-android.tar.gz`
- x86_64: `python-3.14.5-x86_64-linux-android.tar.gz`

解压后将 `prefix/lib/` 中的 `.so` 文件放入对应目录：

```
app/src/main/jniLibs/
  arm64-v8a/
    libpython3.14.so    ← 必须
    libssl_python.so    ← 可选（HTTPS 支持）
    libcrypto_python.so ← 可选（HTTPS 支持）
  x86_64/
    libpython3.14.so
    libssl_python.so
    libcrypto_python.so
```

### 打包标准库

在 `prefix/lib/` 目录下执行：

```bash
# 可选：预装 mcp 包
pip install --target=python3.14/site-packages mcp httpx anyio

# 打包标准库
zip -r stdlib.zip python3.14/
```

将生成的 `stdlib.zip` 放入：
```
app/src/main/assets/python/stdlib.zip
```

App 首次启动时会自动解压（约 5-10 秒），后续启动直接使用缓存。
