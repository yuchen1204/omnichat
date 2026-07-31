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
