# Task 1 Report: Runtime and Plugin Compatibility Spike

Date: 2026-07-31
Branch: `feat/js-document-reader`
Implementation commit: `24f80e718f8e6aab8436590ce1edd3c8a62d0270` (`build: validate embedded JavaScript document runtime`)

## Outcome

**DONE_WITH_CONCERNS**

The Android QuickJS runtime smoke path is verified on the connected Android device. The selected artifact is `wang.harlon.quickjs:wrapper-android:3.2.3`. The requested coordinate `wang.harlon.quickjs:wrapper-android:323` does not exist in Maven Central, so it was not locked. The replacement `3.2.3` resolves through Gradle and is documented as the engine choice.

The spike proves that the app can load an asset script, create a QuickJS context on a dedicated single-thread executor, pass a copied binary `ByteArray`, evaluate JSON, and close the context on the creating thread. It does not implement PDF/DOCX parsing, parser assets, ChatScreen integration, or changes to the existing `DocumentParser`.

## Changes

- Added `gradle/libs.versions.toml` entry for `quickjsAndroid = "3.2.3"` and `wang.harlon.quickjs:wrapper-android`.
- Added `implementation(libs.quickjs.android)` to `app/build.gradle.kts`.
- Added `app/src/main/java/com/omnichat/util/QuickJsSmokeAdapter.kt`:
  - Dedicated single-thread executor named `quickjs-smoke`.
  - Fresh QuickJS context for each probe.
  - Copies the input `ByteArray` before exposing it to JS.
  - Uses `QuickJSLoader.init()`, `QuickJSContext.create()`, `evaluate()`, and `close()`.
  - Always closes the context in `finally` on the executor thread.
  - Does not expose Android `Context`, `Uri`, `File`, `ContentResolver`, reflection, filesystem, or network capabilities to JS.
- Added `app/src/main/assets/quickjs_smoke.js`, the asset script used by the device test.
- Added `app/src/test/java/com/omnichat/util/QuickJsSmokeAdapterTest.kt` for the JVM seam, copied-input assertion, JSON result assertion, dedicated-context script shape, and cleanup on evaluation failure.
- Added `app/src/androidTest/java/com/omnichat/util/QuickJsSmokeInstrumentedTest.kt` for real native wrapper, asset loading, binary input, JSON evaluation, and context cleanup.
- Added `tools/document-plugins/smoke/quickjs-smoke.js`, including the required exported `assertPluginResult(value, expectedFormat)` contract validator and invalid-shape checks.
- Added `tools/document-plugins/smoke/README.md` documenting the engine choice, protocol contract, artifact/license/ABI verification, commands, and risks.

The plugin contract recorded by the fixture is:

```javascript
parseDocument({ name, mimeType, bytes })
  -> { format: string, text: string, warnings: string[] }
```

## Artifact and API verification

### Requested candidate

Command:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://repo.maven.apache.org/maven2/wang/harlon/quickjs/wrapper-android/323/wrapper-android-323.aar
```

Complete result:

```text
404
```

Conclusion: the exact requested version `323` is unavailable from Maven Central and cannot be resolved by Gradle. No dependency was pinned to that coordinate.

### Selected replacement

Command:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://repo.maven.apache.org/maven2/wang/harlon/quickjs/wrapper-android/3.2.3/wrapper-android-3.2.3.aar
```

Complete result:

```text
200
```

The published Maven metadata lists `3.2.3` as the latest/release version. The POM resolves the matching dependency `wang.harlon.quickjs:wrapper-java:3.2.3`.

### AAR ABI contents

Command:

```bash
unzip -l /tmp/quickjs-probe/wrapper-android-3.2.3.aar
```

Relevant complete entries:

```text
1327232  jni/arm64-v8a/libquickjs-android-wrapper.so
 946424  jni/armeabi-v7a/libquickjs-android-wrapper.so
1357880  jni/x86/libquickjs-android-wrapper.so
1400768  jni/x86_64/libquickjs-android-wrapper.so
```

All four required ABIs are present. The AAR metadata was also inspected:

```text
aarFormatVersion=1.0
 aarMetadataVersion=1.0
 minCompileSdk=1
 minCompileSdkExtension=0
 minAndroidGradlePluginVersion=1.0.0
 coreLibraryDesugaringEnabled=false
```

The project uses `minSdk = 26` and `compileSdk = 36`; the AAR metadata does not impose a higher minimum. The final APK contains all four native libraries and the smoke asset:

```text
1327232  lib/arm64-v8a/libquickjs-android-wrapper.so
 946424  lib/armeabi-v7a/libquickjs-android-wrapper.so
1357880  lib/x86/libquickjs-android-wrapper.so
1400768  lib/x86_64/libquickjs-android-wrapper.so
     67  assets/quickjs_smoke.js
```

### API/source verification

The published source jars were inspected. Confirmed APIs:

- `com.whl.quickjs.android.QuickJSLoader.init()` loads `quickjs-android-wrapper`.
- `QuickJSContext.create()` creates a context.
- `QuickJSContext.evaluate(String)` evaluates synchronously.
- `QuickJSContext.close()`/`destroy()` releases the context and requires the creating thread.
- `JSObject.setProperty(String, byte[])` accepts binary data.
- `QuickJSContext.setMemoryLimit(int)` and `setMaxStackSize(int)` exist for later hardening.

The native wrapper exposed the input `byte[]` to JavaScript as an `ArrayBuffer`-compatible value rather than an array with a `.length` property. The initial device run exposed this fact because `JSON.stringify({ok:true, length:bytes.length})` returned `{"ok":true}`. The smoke script was corrected to:

```javascript
JSON.stringify({ok:true, length:new Uint8Array(bytes).byteLength})
```

The corrected script returns the expected binary length on the real device.

### Gradle resolution

Command:

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath --refresh-dependencies
```

Relevant complete result:

```text
+--- wang.harlon.quickjs:wrapper-android:3.2.3
|    \--- wang.harlon.quickjs:wrapper-java:3.2.3
```

The dependency was therefore added only after repository resolution, AAR inspection, source/API inspection, and license inspection.

## License confirmation

The `wrapper-android:3.2.3` POM declares:

```text
The Apache License, Version 2.0
http://www.apache.org/licenses/LICENSE-2.0.txt
```

The upstream repository license was also fetched from `https://raw.githubusercontent.com/HarlonWang/quickjs-wrapper/master/LICENSE` and contains the Apache License notice. Apache-2.0 is compatible with app distribution, subject to retaining required notices in the final distributable. The app's final distribution/notices process remains outside this spike.

Recorded checksums:

```text
app-debug.apk:
db66ce3638dabbf6c251568c7d65b46133b2cab2436bb428f1d142ec120b22b2

wrapper-android-3.2.3.aar:
b1d0c550818f07e411b6e6f0eb21b28d83ed2e227d6129395be9e5a2167f4f0c
```

## Tests and commands

### Initial TDD red step

The JVM smoke test was written before the adapter. Command:

```bash
./gradlew :app:testDebugUnitTest --tests "com.omnichat.util.QuickJsSmokeAdapterTest" --no-configuration-cache
```

Complete failure class:

```text
Unresolved reference 'QuickJsSmokeAdapter'
Unresolved reference 'QuickJsSmokeContextFactory'
Unresolved reference 'QuickJsSmokeResult'
Unresolved reference 'QuickJsSmokeContext'

Execution failed for task ':app:compileDebugUnitTestKotlin'
```

This was the expected failure before the adapter implementation.

### Node contract smoke

Command:

```bash
node tools/document-plugins/smoke/quickjs-smoke.js
```

Complete result:

```text
plugin contract smoke passed
```

### Required JVM/build command

Command:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests "com.omnichat.util.*"
```

Complete result:

```text
BUILD SUCCESSFUL in 3s
50 actionable tasks: 50 up-to-date
```

The dependency-refresh run of the same build also passed:

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  --tests "com.omnichat.util.*" \
  --no-configuration-cache --refresh-dependencies

BUILD SUCCESSFUL in 2m 36s
50 actionable tasks: 15 executed, 35 up-to-date
```

The build emitted existing project warnings (resource substitution warnings and Kotlin/Java deprecation/unchecked-cast warnings); no new Task 1 compilation error remained.

### Connected native smoke

The first invocation incorrectly attempted to pass `--tests` to the Android connected test task:

```bash
./gradlew :app:connectedDebugAndroidTest \
  --tests "com.omnichat.util.QuickJsSmokeInstrumentedTest" \
  --no-configuration-cache
```

Complete result:

```text
Unknown command-line option '--tests'
```

A full connected run then exposed pre-existing unrelated Android-test compilation errors in `AnimationOptimizerTest.kt` (`Spring` type arguments, `durationMs`, `assertNotNull`, and `intValue`). Those files were not modified. To isolate the new smoke test, the three pre-existing Android-test source files were temporarily renamed outside the source set during the command and restored by a shell trap; no such files remain changed or untracked.

The first isolated native smoke run reached the real wrapper but failed with:

```text
QuickJS smoke result was not the expected JSON object: {"ok":true}
```

This was the binary bridge behavior described above (`ArrayBuffer` versus `.length`), not an ABI/load/close failure. After the minimal script correction, the isolated command was:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.omnichat.util.QuickJsSmokeInstrumentedTest \
  --no-configuration-cache
```

Complete result:

```text
Starting 1 tests on CPH2695 - 16
Finished 1 tests on CPH2695 - 16

BUILD SUCCESSFUL in 29s
73 actionable tasks: 3 executed, 70 up-to-date
```

The connected device was `CPH2695 - 16`, and the test exercised the actual native wrapper, app asset, binary input, JSON output, and cleanup. A full connected suite remains blocked by the pre-existing `AnimationOptimizerTest.kt` compile failures and was not changed as part of Task 1.

### Diff/scope check

Command:

```bash
git diff --check
```

Result: passed with no whitespace errors before the implementation commit. The old `app/src/main/java/com/omnichat/util/DocumentParser.kt` was not modified. `ChatScreen.kt`, parser generation, PDFBox use, and Apache POI use were not modified.

## Unresolved risks and constraints

1. **Exact requested coordinate unresolved.** `wang.harlon.quickjs:wrapper-android:323` is a Maven Central 404. The verified replacement is `3.2.3`; this must remain explicit in future dependency reviews.
2. **Promise behavior is not proven.** The selected wrapper exposes synchronous `evaluate()` and no obvious promise-job/event-loop API in the inspected `QuickJSContext` source. This spike proves synchronous binary evaluation only. Parser tasks must not assume Promise support; if parser bundles require Promise scheduling, the runtime adapter must either add a verified job-loop strategy or stop and select a different engine before parser work.
3. **All four ABIs are packaged, but hardware execution covered one ABI.** The APK inspection proves the four `.so` files are packaged. The connected device test proves the device's ABI path. Physical execution on each ABI was not performed.
4. **No hard production memory/timeout policy is implemented.** The wrapper exposes memory-limit APIs, but this spike only verifies the minimal engine boundary. Bounded input/output, timeout, memory, and cancellation policy belong to the runtime/parser task.
5. **Full connected Android test suite remains red from pre-existing sources.** `AnimationOptimizerTest.kt` fails to compile independently of this spike. The new isolated QuickJS instrumented test passes.
6. **No parser is included.** PDF/DOCX parser behavior, ZIP/XML helpers, plugin bundling, malformed document handling, and result-size limits remain future tasks. PDF parsing was not routed back to PDFBox.
7. **License notice integration is not complete.** Apache-2.0 compatibility was confirmed, but final app notices/attribution packaging still needs to be handled by the release process.
8. **The current adapter is a smoke probe, not the final `JsDocumentRuntime`.** It intentionally does not expose a production parser facade or integrate with the existing document flow.
