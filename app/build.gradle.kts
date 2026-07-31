import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

val localSigningProperties = Properties().apply {
  val propertiesFile = rootProject.file("keystore.properties")
  if (propertiesFile.isFile) {
    propertiesFile.inputStream().use(::load)
  }
}
fun signingValue(name: String): String? =
  System.getenv(name)?.takeIf { it.isNotBlank() }
    ?: localSigningProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val releaseKeystorePath = signingValue("KEYSTORE_PATH")
val releaseStorePassword = signingValue("STORE_PASSWORD")
val releaseKeyPassword = signingValue("KEY_PASSWORD")
val releaseKeyAlias = signingValue("KEY_ALIAS") ?: "upload"

android {
  namespace = "com.omnichat"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.omnichat"
    minSdk = 26
    targetSdk = 36

    // CI 通过环境变量 VERSION_NAME 传入版本号（如 "1.2.3"），本地编译使用默认值
    val ciVersion = System.getenv("VERSION_NAME")
    if (ciVersion != null && ciVersion.isNotBlank()) {
        versionName = ciVersion
        // 将语义版本号转为整数 versionCode，如 "1.2.3" → 10203
        versionCode = ciVersion.split(".").fold(0) { acc, part -> acc * 100 + (part.toIntOrNull() ?: 0) }
    } else {
        versionCode = 1
        versionName = "1.0"
    }

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // 避免 BuildConfig 中残留 GEMINI_API_KEY 空值（secrets plugin 已移除）
    buildConfigField("String", "GEMINI_API_KEY", "\"\"")

  }

  signingConfigs {
    create("release") {
      // Do not provide a repository key or a fallback password. The release task
      // validates these values before packaging a distributable artifact.
      releaseKeystorePath?.let { storeFile = file(it) }
      storePassword = releaseStorePassword
      keyAlias = releaseKeyAlias
      keyPassword = releaseKeyPassword
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    isCoreLibraryDesugaringEnabled = true
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// ── 自动生成 ui_text_keys.json ────────────────────────────────────────────
// 扫描所有 Kotlin 源文件中的 uiText("key", R.string.xxx) 和 uiText("key", "默认值") 调用，
// 提取 key 和默认值，写入 assets/ui_text_keys.json。
// 这样每次构建时 JSON 都与代码保持同步，AI 的 list_ui_texts 工具永远不会遗漏 key。
val generateUiTextKeys by tasks.registering {
    description = "Scan Kotlin sources for uiText() calls and generate assets/ui_text_keys.json"
    group = "build"

    val sourceDir = file("src/main/java")
    val stringsFile = file("src/main/res/values/strings.xml")
    val outputFile = file("src/main/assets/ui_text_keys.json")

    inputs.dir(sourceDir)
    inputs.file(stringsFile)
    outputs.file(outputFile)

    doLast {
        // Build resource_name to English value map from strings.xml
        val resMap = mutableMapOf<String, String>()
        val resPattern = Regex("""<string name="([^"]+)">((?:(?!<\/string>).)*)<\/string>""")
        if (stringsFile.exists()) {
            stringsFile.readText(Charsets.UTF_8).lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("<!--")) return@forEach
                resPattern.find(trimmed)?.let { match ->
                    resMap[match.groupValues[1]] = match.groupValues[2]
                        .replace("\\'", "'").replace("&amp;", "&")
                        .replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("\\n", "\n")
                }
            }
        }

        // Match both: uiText("key", R.string.xxx) and uiText("key", "literal")
        val resIdPattern = Regex("""uiText\(\s*"([^"]+)"\s*,\s*R\.string\.(\w+)""")
        val literalPattern = Regex("""uiText\(\s*"([^"]+)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)""")

        val entries = linkedMapOf<String, String>()

        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sorted()
            .forEach { file ->
                val lines = file.readLines(Charsets.UTF_8)
                val filteredLines = lines.filter { line ->
                    val trimmed = line.trimStart()
                    !trimmed.startsWith("//") && !trimmed.startsWith("*")
                }
                val singleLine = filteredLines.joinToString(" ")

                // Match R.string.xxx format first
                resIdPattern.findAll(singleLine).forEach { match ->
                    val key = match.groupValues[1]
                    val resName = match.groupValues[2]
                    entries.putIfAbsent(key, resMap[resName] ?: resName)
                }

                // Then match literal format (backward compat)
                literalPattern.findAll(singleLine).forEach { match ->
                    val key = match.groupValues[1]
                    val default = match.groupValues[2]
                        .replace("\\n", "\n").replace("\\t", "\t")
                        .replace("\\\"", "\"").replace("\\\\", "\\")
                    entries.putIfAbsent(key, default)
                }
            }

        // Generate JSON
        val sb = StringBuilder("{\n")
        entries.entries.forEachIndexed { index, (key, value) ->
            val escapedKey = key.replace("\\", "\\\\").replace("\"", "\\\"")
            val escapedValue = value
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
            sb.append("  \"$escapedKey\": \"$escapedValue\"")
            if (index < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("}")

        outputFile.parentFile.mkdirs()
        outputFile.writeText(sb.toString(), Charsets.UTF_8)
        println("[generateUiTextKeys] Generated ${entries.size} keys -> ${outputFile.absolutePath}")
    }
}

// 在合并 assets 之前先生成 JSON，确保打包进 APK 的是最新版本
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(generateUiTextKeys)
}

// Lint 任务也依赖 generateUiTextKeys（读取了 assets 目录的输出）
tasks.matching { it.name.contains("Lint") || it.name.contains("lint") }.configureEach {
    dependsOn(generateUiTextKeys)
}

ksp {
  arg("room.schemaLocation", file("schemas").absolutePath)
}

tasks.matching { it.name == "validateSigningRelease" }.configureEach {
  doFirst {
    check(
      releaseKeystorePath != null &&
        releaseStorePassword != null &&
        releaseKeyPassword != null
    ) {
      "Release signing is not configured. Set KEYSTORE_PATH, STORE_PASSWORD, and KEY_PASSWORD " +
        "through environment variables or an ignored keystore.properties file."
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material3.window.size)
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
  // Apache POI for document generation
  implementation(libs.poi)
  implementation(libs.poi.ooxml)
  implementation(libs.pdfbox.android)
  implementation(libs.quickjs.android)
  // 图片加载和相机
  implementation(libs.coil.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.camera.core)
  implementation(libs.accompanist.permissions)
  // ZXing for QR code generation
  implementation(libs.zxing.embedded)
  // WorkManager for periodic background tasks
  implementation(libs.work.runtime)
  testImplementation("org.json:json:20231013")
  testImplementation("org.mockito:mockito-core:5.11.0")
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
  coreLibraryDesugaring(libs.desugarJdkLibs)
}
