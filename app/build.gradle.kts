plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.aichatmemory.qwzkvp"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // 只打包主流 ABI，减小 APK 体积（libnode.so 和 python 二进制都按 ABI 分包）
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }

    externalNativeBuild {
      cmake {
        cppFlags("-std=c++17")
        // 使用 c++_shared STL，与 libnode.so 的构建方式一致
        arguments("-DANDROID_STL=c++_shared")
      }
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // signingConfig = signingConfigs.getByName("release")
    }
    debug {
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  // CMake 构建配置（用于 JNI 桥接 libnode.so）
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  // 将 libnode/bin/ 目录下的预编译 .so 文件打包进 APK
  sourceSets {
    getByName("main") {
      jniLibs.srcDirs("src/main/jniLibs")
    }
  }

  // 不压缩 .so 文件，让系统可以直接 mmap（加快加载速度）
  packaging {
    jniLibs {
      useLegacyPackaging = false
    }
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// ── 自动生成 ui_text_keys.json ────────────────────────────────────────────
// 扫描所有 Kotlin 源文件中的 uiText("key", "默认值") 调用，
// 提取 key 和默认中文，写入 assets/ui_text_keys.json。
// 这样每次构建时 JSON 都与代码保持同步，AI 的 list_ui_texts 工具永远不会遗漏 key。
val generateUiTextKeys by tasks.registering {
    description = "Scan Kotlin sources for uiText() calls and generate assets/ui_text_keys.json"
    group = "build"

    val sourceDir = file("src/main/java")
    val outputFile = file("src/main/assets/ui_text_keys.json")

    inputs.dir(sourceDir)
    outputs.file(outputFile)

    doLast {
        // 匹配 uiText("key", "默认值") 或 uiText("key", "默认值（含转义）")
        // 支持单行和多行调用（key 和 default 可以分行写）
        val pattern = Regex("""uiText\(\s*"([^"]+)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)""")

        val entries = linkedMapOf<String, String>()

        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sorted()
            .forEach { file ->
                // 将文件内容按行处理：先过滤掉纯注释行，再合并成单行文本进行匹配
                // 这样既能处理多行 uiText() 调用，又能避免注释里的示例被误匹配
                val lines = file.readLines(Charsets.UTF_8)
                val filteredLines = lines.filter { line ->
                    val trimmed = line.trimStart()
                    // 跳过单行注释（// ...）和 KDoc 行（* ...）
                    !trimmed.startsWith("//") && !trimmed.startsWith("*")
                }
                // 合并为单行（用空格连接），使多行 uiText() 调用也能被正则匹配
                val singleLine = filteredLines.joinToString(" ")

                pattern.findAll(singleLine).forEach { match ->
                    val key = match.groupValues[1]
                    val default = match.groupValues[2]
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\u201c", "\u201c")
                        .replace("\\u201d", "\u201d")
                    // 同一个 key 可能在多处调用（如 contentDescription 和 Text 共用），
                    // 以第一次出现的默认值为准（通常是最完整的那个）
                    entries.putIfAbsent(key, default)
                }
            }

        // 生成 JSON（手动拼接保证 key 顺序稳定，避免不必要的 diff）
        val sb = StringBuilder("{\n")
        entries.entries.forEachIndexed { index, (key, value) ->
            val escapedKey = key.replace("\\", "\\\\").replace("\"", "\\\"")
            val escapedValue = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
            sb.append("  \"$escapedKey\": \"$escapedValue\"")
            if (index < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("}")

        outputFile.parentFile.mkdirs()
        outputFile.writeText(sb.toString(), Charsets.UTF_8)
        println("[generateUiTextKeys] 已生成 ${entries.size} 个 key → ${outputFile.absolutePath}")
    }
}

// 在合并 assets 之前先生成 JSON，确保打包进 APK 的是最新版本
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(generateUiTextKeys)
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  implementation(libs.compose.markdown)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
