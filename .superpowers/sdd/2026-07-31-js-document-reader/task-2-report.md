# Task 2 Report: Host Protocol and QuickJS Adapter

Date: 2026-07-31
Branch: `feat/js-document-reader`
Task brief: `E:\omnichat\.superpowers\sdd\2026-07-31-js-document-reader\task-2-brief.md`
Implementation commit: the final commit is recorded in the repository history and in the completion response.

## Status

**DONE_WITH_CONCERNS**

Task 2 is implemented and committed. The synchronous protocol, Kotlin result validation, bundled-asset-only facade, QuickJS adapter, cooperative input/output/deadline guards, cleanup behavior, and focused tests are present. The adapter does not implement Promise support, a Promise-job pump, Worker, DOM, Canvas, Node APIs, or any parser. The existing `DocumentParser.kt` and `ChatScreen.kt` were not modified.

The selected runtime remains `wang.harlon.quickjs:wrapper-android:3.2.3`, as established by Task 1. The wrapper's public Java API exposes synchronous `evaluate()` plus `setMemoryLimit(int)` and `setMaxStackSize(int)`, but no verified public pending-job or interrupt API.

## Changed files

- `E:\omnichat\app\src\main\java\com\omnichat\util\DocumentParseResult.kt`
  - Added `DocumentParseResult`.
  - Added the stable `DocumentParseErrorCategory` taxonomy.
  - Added `DocumentParseException`.
  - Added `JsDocumentInput`.
- `E:\omnichat\app\src\main\java\com\omnichat\util\JsDocumentRuntime.kt`
  - Added synchronous `JsDocumentRuntime` protocol.
  - Added `BundledQuickJsDocumentRuntime` facade.
  - Added internal `JsDocumentAssetSource` test seam.
  - Added Android `AssetManager` loading constructor.
  - Added asset path policy requiring `document_plugins/*.js` and rejecting traversal/backslash/external paths.
  - Added bounded input and output checks.
  - Added one fresh runtime and one dedicated single-thread executor per parse.
  - Added `finally` cleanup of the runtime context on the worker thread.
  - Added cooperative future cancellation after the configured deadline.
  - Added QuickJS memory and stack configuration in the Android adapter.
  - Added synchronous one-call evaluation that loads runtime and plugin source, injects only document name, MIME type, and copied bytes, invokes `parseDocument`, rejects Promise-like results, and returns the JSON string from the same `evaluate()` call.
  - Added Kotlin JSON validation for `format`, `text`, and `warnings`.
  - Added stable mapping for malformed output, no text, runtime creation, plugin loading, parse failure, resource-limit-looking errors, input/output limits, and timeout/interruption.
- `E:\omnichat\app\src\test\java\com\omnichat\util\JsDocumentRuntimeTest.kt`
  - Added JVM tests using a fake runtime; no native QuickJS dependency is needed by the test seam.
  - Covers valid JSON, missing `format`, non-string `text`, non-array `warnings`, non-string warning members, blank text, oversized output, oversized input before runtime creation, plugin exception, preserved `UnsupportedFormat`, runtime creation failure, asset loading failure, invalid/external plugin path, cleanup exactly once, cooperative timeout cancellation, and absence of a raw script entry point on the production facade.

No other tracked source files were changed. In particular, the old `E:\omnichat\app\src\main\java\com\omnichat\util\DocumentParser.kt`, `ChatScreen.kt`, parser assets, and build dependency files were not changed by Task 2.

## Interface signatures

```kotlin
data class DocumentParseResult(
    val text: String,
    val warnings: List<String> = emptyList()
)

enum class DocumentParseErrorCategory {
    UnsupportedFormat, FileTooLarge, UnreadableInput, RuntimeUnavailable,
    PluginLoadFailed, PluginTimeout, PluginMemoryLimit,
    MalformedPluginResult, ParseFailed, NoExtractableText
}

class DocumentParseException(
    val category: DocumentParseErrorCategory,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

interface JsDocumentRuntime {
    fun parse(pluginSource: String, runtimeSource: String, input: JsDocumentInput): String
    fun close()
}

data class JsDocumentInput(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray
)
```

Production facade signature:

```kotlin
class BundledQuickJsDocumentRuntime : AutoCloseable {
    constructor(assetManager: AssetManager)
    fun parse(pluginAsset: String, input: JsDocumentInput): DocumentParseResult
    override fun close()
}
```

The internal JVM-only constructor accepts `JsDocumentAssetSource`, a runtime factory, and configured limits. It is intentionally not a raw-script production API. Production callers select a bundled asset name and provide a `JsDocumentInput`; source text is loaded by the adapter from `AssetManager`.

## Runtime and security behavior

1. `parse()` rejects input over the configured maximum before creating a runtime. Default input cap is 20 MiB.
2. Only paths beginning with `document_plugins/`, ending in `.js`, and containing neither `..` nor `\\` are accepted. Arbitrary production script source is not accepted by the public Android constructor.
3. Runtime and plugin assets are read as UTF-8 from `AssetManager`.
4. A new worker thread and runtime are created for each parse. QuickJS context creation, evaluation, and close remain on that worker thread.
5. The input bytes are copied before being passed to the runtime seam. The Android adapter encodes bounded bytes as base64 and decodes them into a JavaScript `Uint8Array`; no filesystem path, URI, `Context`, `ContentResolver`, reflection, network, Node, DOM, Canvas, Worker, or Java/Android object is exposed.
6. One synchronous evaluation invokes the loaded runtime/plugin and calls `parseDocument({ name, mimeType, bytes })`. The result is JSON-stringified in that evaluation and returned as a string.
7. Promise-like plugin results are rejected. No Promise job is scheduled or pumped. The implementation does not use `async`/`await`, `Worker`, DOM, Canvas, or Node APIs.
8. The Android adapter calls `QuickJSContext.setMemoryLimit(32 * 1024 * 1024)` and `setMaxStackSize(512 * 1024)`, both confirmed APIs for wrapper 3.2.3. Kotlin also enforces output bytes with a default 4 MiB cap.
9. The default deadline is 5 seconds. On deadline or caller interruption, `Future.cancel(true)` is used and the caller receives `PluginTimeout`.
10. The timeout is cooperative/best effort. The inspected wrapper exposes no verified hard interrupt API for a native `evaluate()` that ignores Java interruption. The worker's `finally` closes the context when evaluation unwinds; the implementation does not claim an immediate hard-kill guarantee for non-cooperative native evaluation.
11. Runtime close is performed exactly once per created runtime through the worker's `finally` block. A runtime that fails to be created does not need closing.
12. Error messages and tests do not log or include document contents.

## TDD evidence

The required failing test was written before the protocol/adapter implementation. Initial command:

```bash
./gradlew :app:testDebugUnitTest --tests "com.omnichat.util.JsDocumentRuntimeTest" --no-configuration-cache
```

Initial complete failure class:

```text
e: file:///E:/omnichat/app/src/test/java/com/omnichat/util/JsDocumentRuntimeTest.kt:16:30 Unresolved reference 'parse'.
e: file:///E:/omnichat/app/src/test/java/com/omnichat/util/JsDocumentRuntimeTest.kt:18:22 Unresolved reference 'DocumentParseResult'.
e: ... Unresolved reference 'DocumentParseErrorCategory'.
e: ... Unresolved reference 'DocumentParseException'.
e: ... Unresolved reference 'BundledQuickJsDocumentRuntime'.
e: ... Unresolved reference 'JsDocumentInput'.
e: ... Unresolved reference 'JsDocumentRuntime'.

> Task :app:compileDebugUnitTestKotlin FAILED

BUILD FAILED
```

This was the expected RED state because the new protocol and adapter types did not yet exist.

## Verification commands and complete outputs

### Focused Task 2 test

Command:

```bash
./gradlew :app:testDebugUnitTest --tests "com.omnichat.util.JsDocumentRuntimeTest" --no-configuration-cache
```

Complete output:

```text
> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.

> Task :app:testDebugUnitTest
> Task :app:finalizeTestRoborazziDebug SKIPPED

BUILD SUCCESSFUL in 12s
33 actionable tasks: 3 executed, 30 up-to-date
```

The final focused class contains 14 tests and passed with zero failures.

### Utility JVM suite and debug build

Command:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests "com.omnichat.util.*" --no-configuration-cache
```

Complete output:

```text
> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.
The current default is 'true'.

> Task :app:assembleDebug
> Task :app:testDebugUnitTest
> Task :app:finalizeTestRoborazziDebug SKIPPED

BUILD SUCCESSFUL in 4s
50 actionable tasks: 4 executed, 46 up-to-date
```

The run emitted only existing project warnings (experimental Kotlin source-set setting and existing Kotlin/Java deprecation/type warnings); it added no Task 2 compilation error.

### Ordinary focused Android instrumentation command

Command:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.omnichat.util.QuickJsSmokeInstrumentedTest \
  --no-configuration-cache
```

Result: **failed before the instrumentation runner** because the pre-existing Android test source below does not compile. Task 2 did not modify it.

Complete relevant output:

```text
> Task :app:compileDebugAndroidTestKotlin FAILED
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:32:63 No type arguments expected for object Spring : Any.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:39:9 Unresolved reference 'assertNotNull'.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:47:59 No parameter with name 'durationMs' found.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:51:9 Unresolved reference 'assertNotNull'.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:62:17 No parameter with name 'durationMs' found.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:80:17 No parameter with name 'durationMs' found.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:99:9 Unresolved reference 'assertNotNull'.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:122:33 Unresolved reference 'intValue'.

> Task :app:compileDebugAndroidTestKotlin FAILED

BUILD FAILED in 9s
65 actionable tasks: 5 executed, 60 up-to-date
```

### Isolated QuickJS Android smoke verification

The three pre-existing Android test sources were temporarily moved out of the source set and restored by a shell `EXIT` trap. No existing test was edited. The command was:

```bash
set -eu
isolated_dir="$(mktemp -d)"
isolated_sources=(
  "app/src/androidTest/java/com/omnichat/ExampleInstrumentedTest.kt"
  "app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt"
  "app/src/androidTest/java/com/omnichat/ui/performance/RefreshRateManagerTest.kt"
)
restore_sources() {
  for source in "${isolated_sources[@]}"; do
    name="${source##*/}"
    if [ -f "$isolated_dir/$name" ]; then
      mkdir -p "$(dirname "$source")"
      mv "$isolated_dir/$name" "$source"
    fi
  done
  rmdir "$isolated_dir" 2>/dev/null || true
}
trap restore_sources EXIT
for source in "${isolated_sources[@]}"; do
  mv "$source" "$isolated_dir/${source##*/}"
done
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.omnichat.util.QuickJsSmokeInstrumentedTest \
  --no-configuration-cache
```

Complete result:

```text
> Task :app:compileDebugAndroidTestKotlin
> Task :app:packageDebugAndroidTest
Starting 2 tests on CPH2695 - 16
> Task :app:connectedDebugAndroidTest
Finished 2 tests on CPH2695 - 16

BUILD SUCCESSFUL in 37s
73 actionable tasks: 3 executed, 70 up-to-date
```

This is isolated verification of the existing real-device QuickJS smoke tests, not a claim that the ordinary connected suite is green. The source-set restoration completed and the working tree contains all four original Android test files.

### Full utility JVM suite rerun

Command:

```bash
./gradlew :app:testDebugUnitTest --tests "com.omnichat.util.*" --no-configuration-cache
```

Complete output:

```text
> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.

> Task :app:testDebugUnitTest
> Task :app:finalizeTestRoborazziDebug SKIPPED

BUILD SUCCESSFUL in 3s
33 actionable tasks: 1 executed, 32 up-to-date
```

### Diff/whitespace checks

Commands:

```bash
git diff --check
git diff --cached --check
```

Result:

```text
passed with no whitespace errors
```

Git emitted only normal Windows LF-to-CRLF normalization warnings when the new files were staged.

## Limits and unresolved risks

1. **Timeout/cancellation is not a hard native interruption.** The Java wrapper has no verified public interrupt API. `Future.cancel(true)` interrupts cooperative code and the runtime closes in `finally` once evaluation unwinds. A native `evaluate()` that ignores interruption can outlive the caller's timeout and retain its daemon worker until it returns. This is the selected wrapper's real limitation and is not represented as a hard-kill guarantee.
2. **No Promise support by design.** The wrapper's real-device smoke proves Promise callbacks remain pending after `evaluate()`. No Promise/job pump is used or promised. Async parser bundles remain unsupported.
3. **QuickJS memory API is configured, but memory classification is defensive.** `setMemoryLimit` and `setMaxStackSize` are available and applied. Runtime exceptions are classified as `PluginMemoryLimit` only when their message indicates a memory/stack/resource-limit condition; exact native exception wording should be validated on representative devices.
4. **The production facade does not yet select a specific parser format or validate `format` against an expected requested format.** That belongs to the later document reader/parser task. Task 2 validates that `format` exists and is a non-empty string.
5. **The adapter uses base64 as a primitive bridge representation.** Input is bounded before encoding, but base64 temporarily increases the JS source size. The 20 MiB default input cap plus 32 MiB QuickJS memory cap should be tuned against actual parser bundles and device memory before shipping.
6. **Asset source trust is enforced by path policy and Android AssetManager.** The internal test seam can supply arbitrary strings for testing, but the public production constructor accepts only `AssetManager`; later production wiring must retain this constructor and must not expose the internal source/factory seam.
7. **No PDF/DOCX parser, shared JS bundle, ChatScreen integration, or legacy parser deletion was attempted.** These are explicitly outside Task 2.
8. **The complete connected Android suite remains blocked by the unrelated existing `AnimationOptimizerTest.kt` compile errors listed above.** The isolated QuickJS smoke passed on device `CPH2695 - 16`.
9. **No document content is logged.** Runtime error messages intentionally describe categories and limits without including names, text, bytes, or plugin output.

## Commit

The Task 2 implementation is committed separately with:

```text
feat: add sandboxed document JavaScript runtime
```


## Full captured verification output

The following blocks are the complete outputs captured from the final verification commands. The ordinary Android command is intentionally a failed baseline because of the pre-existing `AnimationOptimizerTest.kt` compiler errors.

### Fresh final utility build and JVM output

```text

> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.
The current default is 'true'.

> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:mergeDebugNativeDebugMetadata NO-SOURCE
> Task :app:generateDebugBuildConfig UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:processDebugNavigationResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:generateDebugRFile UP-TO-DATE
> Task :app:kspDebugKotlin UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:generateUiTextKeys UP-TO-DATE
> Task :app:mergeDebugAssets UP-TO-DATE
> Task :app:compressDebugAssets UP-TO-DATE
> Task :app:l8DexDesugarLibDebug UP-TO-DATE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:mergeDebugJavaResource UP-TO-DATE
> Task :app:checkDebugDuplicateClasses UP-TO-DATE
> Task :app:desugarDebugFileDependencies UP-TO-DATE
> Task :app:mergeExtDexDebug UP-TO-DATE
> Task :app:mergeLibDexDebug UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:compileDebugNavigationResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:dexBuilderDebug UP-TO-DATE
> Task :app:mergeProjectDexDebug UP-TO-DATE
> Task :app:mergeDebugJniLibFolders UP-TO-DATE
> Task :app:mergeDebugNativeLibs UP-TO-DATE
> Task :app:stripDebugDebugSymbols UP-TO-DATE
> Task :app:validateSigningDebug UP-TO-DATE
> Task :app:writeDebugAppMetadata UP-TO-DATE
> Task :app:writeDebugSigningConfigVersions UP-TO-DATE
> Task :app:packageDebug UP-TO-DATE
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug UP-TO-DATE
> Task :app:bundleDebugClassesToRuntimeJar UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar UP-TO-DATE
> Task :app:kspDebugUnitTestKotlin UP-TO-DATE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:packageDebugUnitTestForUnitTest UP-TO-DATE
> Task :app:processDebugUnitTestManifest UP-TO-DATE
> Task :app:generateDebugUnitTestConfig UP-TO-DATE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest UP-TO-DATE
> Task :app:finalizeTestRoborazziDebug SKIPPED

BUILD SUCCESSFUL in 1s
50 actionable tasks: 50 up-to-date
exit=0

```

### Fresh final ordinary connected Android output

```text

> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.
The current default is 'true'.

> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:processDebugNavigationResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:generateDebugRFile UP-TO-DATE
> Task :app:kspDebugKotlin UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:compileDebugNavigationResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar UP-TO-DATE
> Task :app:preDebugAndroidTestBuild SKIPPED
> Task :app:processDebugAndroidTestManifest UP-TO-DATE
> Task :app:generateDebugAndroidTestBuildConfig UP-TO-DATE
> Task :app:generateDebugAndroidTestResources UP-TO-DATE
> Task :app:packageDebugAndroidTestResources UP-TO-DATE
> Task :app:processDebugAndroidTestNavigationResources UP-TO-DATE
> Task :app:parseDebugAndroidTestLocalResources UP-TO-DATE
> Task :app:generateDebugAndroidTestRFile UP-TO-DATE
> Task :app:kspDebugAndroidTestKotlin FROM-CACHE
> Task :app:javaPreCompileDebugAndroidTest UP-TO-DATE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:generateUiTextKeys UP-TO-DATE
> Task :app:mergeDebugAssets UP-TO-DATE
> Task :app:compressDebugAssets UP-TO-DATE
> Task :app:l8DexDesugarLibDebug UP-TO-DATE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:mergeDebugJavaResource UP-TO-DATE
> Task :app:checkDebugDuplicateClasses UP-TO-DATE
> Task :app:desugarDebugFileDependencies UP-TO-DATE
> Task :app:mergeExtDexDebug UP-TO-DATE
> Task :app:mergeLibDexDebug UP-TO-DATE
> Task :app:dexBuilderDebug UP-TO-DATE
> Task :app:mergeProjectDexDebug UP-TO-DATE
> Task :app:mergeDebugJniLibFolders UP-TO-DATE
> Task :app:mergeDebugNativeLibs UP-TO-DATE
> Task :app:stripDebugDebugSymbols UP-TO-DATE
> Task :app:validateSigningDebug UP-TO-DATE
> Task :app:writeDebugAppMetadata UP-TO-DATE
> Task :app:writeDebugSigningConfigVersions UP-TO-DATE
> Task :app:packageDebug UP-TO-DATE
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:generateDebugAndroidTestAssets UP-TO-DATE
> Task :app:mergeDebugAndroidTestAssets UP-TO-DATE
> Task :app:compressDebugAndroidTestAssets UP-TO-DATE
> Task :app:checkDebugAndroidTestDuplicateClasses UP-TO-DATE
> Task :app:desugarDebugAndroidTestFileDependencies UP-TO-DATE
> Task :app:mergeExtDexDebugAndroidTest UP-TO-DATE
> Task :app:mergeLibDexDebugAndroidTest UP-TO-DATE
> Task :app:checkDebugAndroidTestAarMetadata UP-TO-DATE
> Task :app:mapDebugAndroidTestSourceSetPaths UP-TO-DATE
> Task :app:compileDebugAndroidTestNavigationResources UP-TO-DATE
> Task :app:mergeDebugAndroidTestResources UP-TO-DATE
> Task :app:processDebugAndroidTestResources UP-TO-DATE
> Task :app:mergeDebugAndroidTestJniLibFolders UP-TO-DATE
> Task :app:mergeDebugAndroidTestNativeLibs NO-SOURCE
> Task :app:stripDebugAndroidTestDebugSymbols NO-SOURCE
> Task :app:validateSigningDebugAndroidTest UP-TO-DATE
> Task :app:writeDebugAndroidTestSigningConfigVersions UP-TO-DATE

> Task :app:compileDebugAndroidTestKotlin FAILED
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:32:63 No type arguments expected for object Spring : Any.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:39:9 Unresolved reference 'assertNotNull'.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:47:59 No parameter with name 'durationMs' found.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:51:9 Unresolved reference 'assertNotNull'.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:62:17 No parameter with name 'durationMs' found.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:80:17 No parameter with name 'durationMs' found.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:99:9 Unresolved reference 'assertNotNull'.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:122:33 Unresolved reference 'intValue'.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compileDebugAndroidTestKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 6s
65 actionable tasks: 1 executed, 1 from cache, 63 up-to-date
exit=1

```

### Fresh final isolated connected QuickJS output

```text

> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.
The current default is 'true'.

> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:processDebugNavigationResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:generateDebugRFile UP-TO-DATE
> Task :app:kspDebugKotlin UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:compileDebugNavigationResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar UP-TO-DATE
> Task :app:preDebugAndroidTestBuild SKIPPED
> Task :app:processDebugAndroidTestManifest UP-TO-DATE
> Task :app:generateDebugAndroidTestBuildConfig UP-TO-DATE
> Task :app:generateDebugAndroidTestResources UP-TO-DATE
> Task :app:packageDebugAndroidTestResources UP-TO-DATE
> Task :app:processDebugAndroidTestNavigationResources UP-TO-DATE
> Task :app:parseDebugAndroidTestLocalResources UP-TO-DATE
> Task :app:generateDebugAndroidTestRFile UP-TO-DATE
> Task :app:kspDebugAndroidTestKotlin FROM-CACHE
> Task :app:compileDebugAndroidTestKotlin FROM-CACHE
> Task :app:javaPreCompileDebugAndroidTest UP-TO-DATE
> Task :app:compileDebugAndroidTestJavaWithJavac UP-TO-DATE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:generateUiTextKeys UP-TO-DATE
> Task :app:mergeDebugAssets UP-TO-DATE
> Task :app:compressDebugAssets UP-TO-DATE
> Task :app:l8DexDesugarLibDebug UP-TO-DATE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:mergeDebugJavaResource UP-TO-DATE
> Task :app:checkDebugDuplicateClasses UP-TO-DATE
> Task :app:desugarDebugFileDependencies UP-TO-DATE
> Task :app:mergeExtDexDebug UP-TO-DATE
> Task :app:mergeLibDexDebug UP-TO-DATE
> Task :app:dexBuilderDebug UP-TO-DATE
> Task :app:mergeProjectDexDebug UP-TO-DATE
> Task :app:mergeDebugJniLibFolders UP-TO-DATE
> Task :app:mergeDebugNativeLibs UP-TO-DATE
> Task :app:stripDebugDebugSymbols UP-TO-DATE
> Task :app:validateSigningDebug UP-TO-DATE
> Task :app:writeDebugAppMetadata UP-TO-DATE
> Task :app:writeDebugSigningConfigVersions UP-TO-DATE
> Task :app:packageDebug UP-TO-DATE
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:generateDebugAndroidTestAssets UP-TO-DATE
> Task :app:mergeDebugAndroidTestAssets UP-TO-DATE
> Task :app:compressDebugAndroidTestAssets UP-TO-DATE
> Task :app:processDebugAndroidTestJavaRes UP-TO-DATE
> Task :app:mergeDebugAndroidTestJavaResource UP-TO-DATE
> Task :app:checkDebugAndroidTestDuplicateClasses UP-TO-DATE
> Task :app:desugarDebugAndroidTestFileDependencies UP-TO-DATE
> Task :app:mergeExtDexDebugAndroidTest UP-TO-DATE
> Task :app:mergeLibDexDebugAndroidTest UP-TO-DATE
> Task :app:checkDebugAndroidTestAarMetadata UP-TO-DATE
> Task :app:mapDebugAndroidTestSourceSetPaths UP-TO-DATE
> Task :app:compileDebugAndroidTestNavigationResources UP-TO-DATE
> Task :app:mergeDebugAndroidTestResources UP-TO-DATE
> Task :app:processDebugAndroidTestResources UP-TO-DATE
> Task :app:dexBuilderDebugAndroidTest UP-TO-DATE
> Task :app:mergeProjectDexDebugAndroidTest UP-TO-DATE
> Task :app:mergeDebugAndroidTestJniLibFolders UP-TO-DATE
> Task :app:mergeDebugAndroidTestNativeLibs NO-SOURCE
> Task :app:stripDebugAndroidTestDebugSymbols NO-SOURCE
> Task :app:validateSigningDebugAndroidTest UP-TO-DATE
> Task :app:writeDebugAndroidTestSigningConfigVersions UP-TO-DATE
> Task :app:packageDebugAndroidTest UP-TO-DATE
> Task :app:createDebugAndroidTestApkListingFileRedirect UP-TO-DATE
Starting 2 tests on CPH2695 - 16
> Task :app:connectedDebugAndroidTest

Finished 2 tests on CPH2695 - 16

BUILD SUCCESSFUL in 32s
73 actionable tasks: 1 executed, 2 from cache, 70 up-to-date
exit=0

```

### Final whitespace check

Command:

```bash
git diff --check && git diff --cached --check
```

Complete output:

```text
(no output; exit code 0)
```

## Fix Round 1: Review Findings

Date: 2026-07-31
Branch: `feat/js-document-reader`
Base implementation: `9eac9af feat: add sandboxed document JavaScript runtime`
Status: **DONE_WITH_CONCERNS**

This fix round addresses the review package `review-160f765..9eac9af.diff`. The timeout and close semantics are now explicit and bounded: this implementation still cannot hard-kill a native `evaluate()` that ignores interruption, but such work is retained in an active registry, consumes a bounded orphan budget, and prevents an unbounded stream of new parses. It is not represented as a hard native termination guarantee.

### Fix-round changed files

- `E:\omnichat\app\src\main\java\com\omnichat\util\JsDocumentRuntime.kt`
  - Replaced one-executor-per-parse lifecycle with one shared, bounded `ThreadPoolExecutor` per facade.
  - Added `SynchronousQueue`, active task registry, `maxConcurrentTasks`, and `maxOrphanTasks` controls.
  - Kept timed-out non-cooperative tasks registered until worker `finally` closes the runtime and removes the task.
  - Made `parse()` registration, closed check, input snapshot, and `close()` task snapshot share one lock.
  - Moved injectable asset/runtime factories to the internal coordinator; the production facade constructor accepts only `AssetManager`.
  - Changed default input cap from 20 MiB to 4 MiB because base64, evaluated script/source copies, decoded `Uint8Array`, output, and the 32 MiB QuickJS cap otherwise leave insufficient headroom.
  - Used primitive global bridge values for name, MIME type, and base64 bytes, with bounded typed-array decoding in JS.
  - Closed a created QuickJS context if memory/stack configuration fails.
  - Replaced broad resource-message substring classification with exact normalized QuickJS resource-limit messages.
  - Fixed actual-device script construction: JavaScript source separators are real newlines and the IIFE explicitly returns `JSON.stringify(...)`.
- `E:\omnichat\app\src\test\java\com\omnichat\util\JsDocumentRuntimeTest.kt`
  - Added regression coverage for non-string warning members, non-cooperative timeout/orphan retention and cap rejection, close/parse lifecycle, input snapshot semantics, 4 MiB boundary, exact resource classification, and production-constructor surface.
  - Added a real warning-member assertion and tests that verify runtime close remains exactly once.
- `E:\omnichat\app\src\androidTest\assets\document_plugins\runtime.js`
  - Added a minimal synchronous runtime fixture for the real adapter test.
- `E:\omnichat\app\src\androidTest\assets\document_plugins\test.js`
  - Added a synchronous bundled fixture that returns the input name, MIME type, byte length, and byte value.
- `E:\omnichat\app\src\androidTest\java\com\omnichat\util\BundledQuickJsDocumentRuntimeInstrumentedTest.kt`
  - Added a real-device test through the production `AssetManager` facade, QuickJS context, base64/`Uint8Array` bridge, JSON validation, and `close()` lifecycle.
- `E:\omnichat\docs\superpowers\plans\2026-07-31-js-document-reader.md`
  - Updated the Task 6 example to use `BundledQuickJsDocumentRuntime(context.assets)` instead of the nonexistent `QuickJsDocumentRuntime(context)`. This is a plan/API clarification only; Task 6 implementation remains out of scope.
- `E:\omnichat\.superpowers\sdd\2026-07-31-js-document-reader\task-2-report.md`
  - Appended this fix-round report.

The following were not modified: `DocumentParser.kt`, `ChatScreen.kt`, PDF/DOCX parser assets, shared bundle, and build dependency declarations.

### Fix-round interface and lifecycle contract

Production facade:

```kotlin
class BundledQuickJsDocumentRuntime(assetManager: AssetManager) : AutoCloseable {
    fun parse(pluginAsset: String, input: JsDocumentInput): DocumentParseResult
    override fun close()
}
```

Test seam/internal coordinator:

```kotlin
internal class JsDocumentRuntimeCoordinator(
    assetSource: JsDocumentAssetSource,
    runtimeFactory: () -> JsDocumentRuntime,
    maxInputBytes: Int = 4 * 1024 * 1024,
    maxOutputBytes: Int = 4 * 1024 * 1024,
    timeoutMillis: Long = 5_000L,
    maxConcurrentTasks: Int = 1,
    maxOrphanTasks: Int = 1
) : AutoCloseable
```

The production class has no constructor accepting arbitrary source text, `JsDocumentAssetSource`, or runtime factory. The internal coordinator is the JVM-test seam; the production construction path is `AssetManager` only. This is not relying on Kotlin `internal` as a security boundary: production callers simply have no injection parameter on the production facade.

`parse()` copies the input bytes while holding the same lifecycle lock used for closed-state checking and task registration. The caller can mutate its original `ByteArray` after `parse()` begins without changing the registered task's input.

The coordinator uses one shared fixed-size executor with a synchronous handoff. The default active-task limit is one. On timeout or caller interruption, `Future.cancel(true)` is used only as a cooperative interruption request. A task that has started remains in `activeTasks`; it is marked orphan and consumes one orphan slot until the worker's `finally` closes its runtime and removes it. If the orphan cap is exhausted, later `parse()` calls fail with `RuntimeUnavailable` without creating another runtime or executor. `close()` takes the same lock, marks the coordinator closed, snapshots active tasks, requests cancellation, and calls `shutdownNow()`. It does not wait for native code that ignores interruption. After `close()` returns, registration cannot succeed and no new parse can start. A non-cooperative worker may finish later on its daemon thread; this is documented as cooperative cancellation, not hard termination.

### Finding-by-finding disposition

1. **Important: timeout leaked non-cooperative native work and removed it from active tracking — fixed.**
   - Removed per-parse executor creation and unconditional active-executor removal in the caller's `finally`.
   - Added shared controlled executor, active task registry, task state, orphan count, and cap.
   - Added `nonCooperativeTimeoutConsumesOrphanBudgetUntilWorkerFinallyCloses`.
   - The test proves the first timeout returns `PluginTimeout`, the still-running task prevents a second parse, no replacement runtime is created, and parsing resumes only after the orphan worker releases and closes.
   - Remaining semantic limitation is explicit: the wrapper exposes no verified hard interrupt for native `evaluate()`, so a native call that ignores interruption can outlive the caller's timeout/close until it returns. It cannot create an unlimited number of orphan workers.

2. **Important: `close()`/`parse()` race — fixed.**
   - Closed check, validation, defensive byte copy, active-task registration, and executor submission are under `stateLock`.
   - `close()` uses the same lock before taking its task snapshot and shutting down.
   - Added `closeReturnsAsCancellationRequestAndPreventsParseAfterClose`.
   - `close()` is intentionally non-blocking for non-cooperative native work; a parse already in progress can finish later, but parse registration after `close()` returns is rejected.

3. **Important: production facade injection seam — fixed.**
   - `BundledQuickJsDocumentRuntime` now has only `BundledQuickJsDocumentRuntime(AssetManager)`.
   - Asset loading remains restricted to the production `AssetManager` path and the existing bundled path policy.
   - JVM tests inject only the internal coordinator, not the production facade constructor.
   - Reflection coverage verifies no production constructor accepts `JsDocumentAssetSource` or a function/runtime factory.

4. **Important: Task 6 API mismatch — fixed in the plan, without expanding Task 2.**
   - The plan no longer points at nonexistent `QuickJsDocumentRuntime(context)`.
   - The plan now names `BundledQuickJsDocumentRuntime(context.assets)` as the high-level production adapter. Task 6 must adapt its reader/runtime seam to that facade; this fix round does not implement `JsDocumentReader`.

5. **Important: 20 MiB input versus 32 MiB QuickJS/base64/script copies — fixed conservatively.**
   - Default input is now 4 MiB and output is 4 MiB.
   - The bridge copies the input once, encodes it as base64 (approximately 5.33 MiB at the 4 MiB boundary), stores primitive bridge values, decodes directly into a bounded `Uint8Array`, and avoids embedding base64 in the generated source string. The 32 MiB QuickJS memory cap and 512 KiB stack cap remain configured.
   - Added an exact-4-MiB acceptance and 4 MiB-plus-one rejection test.
   - The 4 MiB value is a conservative adapter default, not a claim that every future PDF/DOCX bundle can use the entire cap; plugin source/output and device-specific native allocations must remain part of later Task 3-5 validation.

6. **Minor: copy bytes before parse — fixed.**
   - Snapshot happens before task registration under the lifecycle lock, and the runtime bridge makes a second local copy before encoding.
   - `parseSnapshotsInputBytesBeforeCallerCanMutateThem` verifies the runtime sees the original bytes.

7. **Minor: context configuration failure cleanup — fixed.**
   - `createConfiguredContext()` closes the newly created `QuickJSContext` if memory or stack configuration throws, attaching a close failure as suppressed.

8. **Minor: arbitrary plugin message substring resource classification — fixed.**
   - Only exact normalized messages such as `out of memory`, `internalerror: out of memory`, `stack overflow`, and `maximum call stack size exceeded` map to `PluginMemoryLimit`.
   - An arbitrary `IOException("memory pressure in document")` remains `ParseFailed`.

9. **Minor: warning member test — fixed.**
   - Added a test for `[7]` in `warnings`, which maps to `MalformedPluginResult` and still closes the runtime once.

10. **Real adapter verification — added and passed in isolation.**
    - Added `BundledQuickJsDocumentRuntimeInstrumentedTest` using real `QuickJSContext` and test APK assets.
    - The first device run exposed a real bridge bug (`unexpected token in expression: '\\'`) caused by literal backslash separators in the Kotlin triple-quoted script; that was fixed before the passing rerun.
    - The second run exposed that the wrapper did not reliably return the final IIFE expression; the script now uses explicit `return JSON.stringify(...)`, and the next isolated run passed.

### TDD/red-green evidence

The fix-round test changes were compiled/run before the final green verification. The first test command caught a test seam return-type mismatch:

```text
./gradlew :app:testDebugUnitTest --tests "com.omnichat.util.JsDocumentRuntimeTest" --no-configuration-cache

> Task :app:compileDebugUnitTestKotlin FAILED
e: file:///E:/omnichat/app/src/test/java/com/omnichat/util/JsDocumentRuntimeTest.kt:242:40 Return type mismatch: expected 'BundledQuickJsDocumentRuntime', actual 'JsDocumentRuntimeCoordinator'.

BUILD FAILED
```

After correcting the helper to return the internal coordinator, the next run exposed the test's accidental `submit(Runnable)` overload and failed as expected at the assertion (`expected DocumentParseResult ... but was null`). The test was corrected to submit an explicit `Callable<DocumentParseResult>`. The final focused test then passed with 21 tests at that intermediate point; the final focused command passed with the complete current `JsDocumentRuntimeTest` class (the Gradle output reports the task result, while the prior test XML recorded 21 completed tests).

The real adapter test also supplied a red-green device loop: the first implementation failed on the device with the QuickJS parser error described above; after fixing the bridge script and adding explicit return, the isolated real adapter run passed.

### Verification commands and complete outputs

#### Final focused JVM runtime test

Command:

```bash
./gradlew :app:testDebugUnitTest --tests "com.omnichat.util.JsDocumentRuntimeTest" --no-configuration-cache
```

Complete output:

```text
> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.
The current default is 'true'.

> Task :app:testDebugUnitTest
> Task :app:finalizeTestRoborazziDebug SKIPPED

BUILD SUCCESSFUL in 3s
33 actionable tasks: 1 executed, 32 up-to-date
```

#### Final utility JVM suite and debug build

Command:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests "com.omnichat.util.*" --no-configuration-cache
```

Complete output:

```text
> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.
The current default is 'true'.

> Task :app:assembleDebug UP-TO-DATE
> Task :app:testDebugUnitTest
> Task :app:finalizeTestRoborazziDebug SKIPPED

BUILD SUCCESSFUL in 12s
50 actionable tasks: 3 executed, 47 up-to-date
```

#### First isolated real adapter run (red, before bridge fix)

Command:

```bash
./gradlew :app:connectedDebugAndroidTest \\
  -Pandroid.testInstrumentationRunnerArguments.class=com.omnichat.util.BundledQuickJsDocumentRuntimeInstrumentedTest \\
  --no-configuration-cache
```

Relevant complete failure chain:

```text
Starting 1 tests on CPH2695 - 16

com.omnichat.util.BundledQuickJsDocumentRuntimeInstrumentedTest > realQuickJsAdapterEvaluatesBundledSynchronousPluginAndCloses[CPH2695 - 16] FAILED
com.whl.quickjs.wrapper.QuickJSException: unexpected token in expression: '\\'
at <input>:4
at Function (native)
at <anonymous> (unknown.js:34)
at <eval> (unknown.js:45)

> Task :app:connectedDebugAndroidTest FAILED
BUILD FAILED in 36s
73 actionable tasks: 11 executed, 62 up-to-date
```

#### Final isolated real adapter run

The three pre-existing sources `ExampleInstrumentedTest.kt`, `AnimationOptimizerTest.kt`, and `RefreshRateManagerTest.kt` were temporarily moved out of the Android test source set and restored by an `EXIT` trap. No existing source was edited.

Command:

```bash
./gradlew :app:connectedDebugAndroidTest \\
  -Pandroid.testInstrumentationRunnerArguments.class=com.omnichat.util.BundledQuickJsDocumentRuntimeInstrumentedTest \\
  --no-configuration-cache
```

Complete output:

```text
> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.
The current default is 'true'.

> Task :app:compileDebugAndroidTestKotlin UP-TO-DATE
> Task :app:packageDebugAndroidTest UP-TO-DATE
Starting 1 tests on CPH2695 - 16
Finished 1 tests on CPH2695 - 16
> Task :app:connectedDebugAndroidTest

BUILD SUCCESSFUL in 1m 39s
73 actionable tasks: 8 executed, 65 up-to-date
```

#### Ordinary connected Android baseline

Command:

```bash
./gradlew :app:connectedDebugAndroidTest \\
  -Pandroid.testInstrumentationRunnerArguments.class=com.omnichat.util.BundledQuickJsDocumentRuntimeInstrumentedTest \\
  --no-configuration-cache
```

Result: **failed before the runner because of the pre-existing `AnimationOptimizerTest.kt` compile errors**. The new adapter test itself was not the blocker.

Complete compiler output:

```text
> Task :app:compileDebugAndroidTestKotlin FAILED
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:32:63 No type arguments expected for object Spring : Any.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:39:9 Unresolved reference 'assertNotNull'.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:47:59 No parameter with name 'durationMs' found.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:51:9 Unresolved reference 'assertNotNull'.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:62:17 No parameter with name 'durationMs' found.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:80:17 No parameter with name 'durationMs' found.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:99:9 Unresolved reference 'assertNotNull'.
e: file:///E:/omnichat/app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt:122:33 Unresolved reference 'intValue'.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compileDebugAndroidTestKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details.

BUILD FAILED in 6s
65 actionable tasks: 2 executed, 63 up-to-date
```

#### Diff and API checks

Commands:

```bash
git diff --check
grep -RIn --exclude-dir=build -E 'QuickJsDocumentRuntime\\(context\\)|runtimeFactory:.*JsDocumentRuntime|BundledQuickJsDocumentRuntime' docs/superpowers/plans/2026-07-31-js-document-reader.md app/src/main/java app/src/test/java app/src/androidTest/java
```

Output:

```text
(no output from git diff --check)
docs/superpowers/plans/2026-07-31-js-document-reader.md:326:    private val runtimeFactory: () -> BundledQuickJsDocumentRuntime = {
docs/superpowers/plans/2026-07-31-js-document-reader.md:327:        BundledQuickJsDocumentRuntime(context.assets)
app/src/main/java\\com\\omnichat\\util\\JsDocumentRuntime.kt:40:class BundledQuickJsDocumentRuntime(
app/src/main/java\\com\\omnichat\\util\\JsDocumentRuntime.kt:70:internal class JsDocumentRuntimeCoordinator(
app/src/androidTest/java\\com\\omnichat\\util\\BundledQuickJsDocumentRuntimeInstrumentedTest.kt:11:class BundledQuickJsDocumentRuntimeInstrumentedTest {
```

### Remaining risks and explicit concerns

1. The selected QuickJS wrapper still has no verified public hard-interrupt API. A native `evaluate()` that ignores interruption can run beyond the caller deadline and beyond non-blocking `close()`, but it remains bounded by one active task and one orphan slot by default. The worker is daemonized, and its runtime is closed in `finally` after it unwinds. This is the reason the status remains `DONE_WITH_CONCERNS`, not a claim of hard termination.
2. The default 4 MiB input cap is an adapter memory budget chosen to avoid the prior 20 MiB/32 MiB conflict. Final PDF/DOCX plugin source sizes and representative device memory behavior must still be validated in Tasks 3-5 before raising the cap.
3. `close()` is a cancellation request and lifecycle barrier, not a join. Task 6 should treat a closed facade as unusable and should not expect `close()` to synchronously terminate an uncooperative native call.
4. Task 6 still needs a reader-level seam around the high-level `BundledQuickJsDocumentRuntime(context.assets)` facade. It must not reintroduce `QuickJsDocumentRuntime(context)` or expose the internal source/runtime factory through the production facade.
5. The ordinary connected Android suite remains blocked by the unrelated existing `AnimationOptimizerTest.kt` errors above. The isolated real adapter test passed on `CPH2695 - 16`.
6. No parser bundle, PDF/DOCX parser, ChatScreen integration, legacy parser removal, or dependency cleanup was attempted, per scope.

### Fix-round commit

Fix-round commit: `6a37ac1 fix: harden document runtime lifecycle`

## Fix Round 2: Shared Executor Handoff Rejection

Date: 2026-07-31
Branch: `feat/js-document-reader`
Review package: `E:\omnichat\.superpowers\sdd\2026-07-31-js-document-reader\review-9eac9af..ad8cf9a.diff`
Status: **DONE_WITH_CONCERNS**

This round addresses the new Important finding: with the prior shared `ThreadPoolExecutor` and `SynchronousQueue`, a normal task could finish `runTask()` and remove its active-registry entry before its worker returned to the executor loop. A back-to-back parse could then observe capacity as available but still receive `AbortPolicy` rejection during that short handoff window, which was incorrectly exposed as `RuntimeUnavailable`.

### Queue strategy

- Replaced `SynchronousQueue` with `ArrayBlockingQueue(maxConcurrentTasks)` in `JsDocumentRuntimeCoordinator`.
- Kept `corePoolSize == maximumPoolSize == maxConcurrentTasks`, the daemon dedicated worker factory, and `AbortPolicy` as a final shutdown/rejection guard. No unbounded queue or unlimited thread creation was restored.
- The queue capacity equals the configured active-task budget. The registry check remains the authoritative admission control: no more than `maxConcurrentTasks` tasks can be registered, and the orphan budget still rejects new work while timed-out non-cooperative tasks remain active.
- The bounded queue absorbs the finite worker handoff window after a successful task has completed but before that worker calls `getTask()` again. It cannot accumulate an unbounded backlog because registry admission is capped and the queue itself is bounded.
- Cancellation of a task that has not started now removes its `FutureTask` from the queue before removing it from the active registry. This prevents a canceled queued task from consuming queue capacity or delaying later work. Running canceled tasks remain registered as orphans until worker `finally` closes the runtime and removes them, preserving the existing orphan cap and cooperative-cancellation semantics.
- Shutdown and rejection mappings remain unchanged: `close()` still marks the lifecycle closed, cancels the active snapshot, and calls `shutdownNow()`; post-close parse still fails with the existing closed-state `IllegalStateException`; executor rejection is still mapped to `DocumentParseErrorCategory.RuntimeUnavailable`.

### Regression coverage

Added to `E:\omnichat\app\src\test\java\com\omnichat\util\JsDocumentRuntimeTest.kt`:

1. `repeatedBackToBackParsesDoNotHitTransientRuntimeUnavailable` performs 500 sequential successful parses against one coordinator and asserts every result succeeds and all 500 runtimes are created. This failed before the queue change with `RejectedExecutionException` mapped to `RuntimeUnavailable` at the 49th parse in the first run.
2. `concurrentSubmissionsWithinConfiguredLimitCompleteWithoutRejection` submits two parses concurrently with `maxConcurrentTasks = 2`, uses latches to prove both workers started, releases them deterministically, and asserts both complete successfully. It uses no sleeps.
3. Existing `nonCooperativeTimeoutConsumesOrphanBudgetUntilWorkerFinallyCloses` continues to cover timeout, orphan-budget rejection, deterministic release, and a successful parse after the orphan worker closes. Existing close/shutdown, cooperative timeout, runtime creation, plugin load, malformed result, output-limit, and unsupported-format tests remain in the focused class.

### TDD and verification output

The new regression tests were added before the production queue change. The required focused command was run in the pre-fix state:

```text
./gradlew :app:testDebugUnitTest --tests "com.omnichat.util.JsDocumentRuntimeTest" --no-configuration-cache

> Task :app:testDebugUnitTest FAILED
23 tests completed, 1 failed
JsDocumentRuntimeTest > repeatedBackToBackParsesDoNotHitTransientRuntimeUnavailable FAILED
    com.omnichat.util.DocumentParseException at JsDocumentRuntimeTest.kt:49
        Caused by: java.util.concurrent.RejectedExecutionException at JsDocumentRuntimeTest.kt:49
```

After switching to the bounded queue and fixing the queue-removal type, the required focused command passed:

```text
./gradlew :app:testDebugUnitTest --tests "com.omnichat.util.JsDocumentRuntimeTest" --no-configuration-cache

BUILD SUCCESSFUL in 1m 23s
33 actionable tasks: 8 executed, 25 up-to-date
```

The focused class was then repeated five times; all five runs passed. The utility suite and debug build also passed:

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests "com.omnichat.util.*" --no-configuration-cache

BUILD SUCCESSFUL in 4s
50 actionable tasks: 4 executed, 46 up-to-date
```

Whitespace verification passed with `git diff --check`. The isolated real-device adapter check was run without modifying or retaining changes to unrelated Android tests:

```text
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.omnichat.util.BundledQuickJsDocumentRuntimeInstrumentedTest \
  --no-configuration-cache

Starting 1 tests on CPH2695 - 16
Finished 1 tests on CPH2695 - 16
BUILD SUCCESSFUL in 34s
73 actionable tasks: 3 executed, 70 up-to-date
```

### Changed files and scope

- `E:\omnichat\app\src\main\java\com\omnichat\util\JsDocumentRuntime.kt`
  - Bounded queue import/configuration, queue-removal support for not-started cancellation, and the corresponding `FutureTask` field type.
- `E:\omnichat\app\src\test\java\com\omnichat\util\JsDocumentRuntimeTest.kt`
  - 500-iteration back-to-back regression and deterministic two-worker concurrency regression, plus test runtime fixture.
- `E:\omnichat\.superpowers\sdd\2026-07-31-js-document-reader\task-2-report.md`
  - This round-2 report.

No PDF/DOCX parser, parser bundle, `ChatScreen`, `DocumentParser`, `AnimationOptimizerTest`, or build dependency was modified.

### Remaining risks

1. QuickJS native evaluation remains cooperatively cancellable only. A native call that ignores interruption can outlive the caller timeout and non-blocking `close()` until it returns; the active registry and orphan budget still bound replacement work.
2. `ArrayBlockingQueue(maxConcurrentTasks)` absorbs only the bounded executor handoff window. If future code changes admission accounting or allows queued work beyond the active-task budget, the queue/rejection contract must be revisited; the current coordinator keeps registry admission and queue capacity aligned.
3. The ordinary connected Android suite remains blocked by the pre-existing `AnimationOptimizerTest.kt` compilation errors documented above. The isolated production QuickJS adapter test passed on the connected device.
4. The conservative 4 MiB input and 4 MiB output limits, QuickJS memory/stack caps, and parser-bundle compatibility still require later representative PDF/DOCX validation.

### Fix-round 2 commit

`fix: bound document runtime executor handoff`
