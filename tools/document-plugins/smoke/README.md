# Embedded JavaScript document-plugin compatibility spike

Status: **verified candidate: `wang.harlon.quickjs:wrapper-android:3.2.3`**.

The requested coordinate `wang.harlon.quickjs:wrapper-android:323` does not exist in Maven Central. Maven Central publishes the same artifact with version `3.2.3` (and `3.2.0`); `3.2.3` is the selected, verifiable replacement. The dependency is declared only after resolving and inspecting the published AAR, POM, sources, and Android smoke-test API.

## Runtime contract

A future bundled document plugin must expose:

```javascript
parseDocument({ name, mimeType, bytes })
  -> { format: string, text: string, warnings: string[] }
```

`bytes` is a copied binary buffer supplied by the host. Plugins must not receive Android `Context`, `Uri`, `File`, `ContentResolver`, reflection, or filesystem/network capabilities.

The Node fixture exports `assertPluginResult(value, expectedFormat)` and checks the result shape without implementing a parser:

```bash
node tools/document-plugins/smoke/quickjs-smoke.js
```

Expected output:

```text
plugin contract smoke passed
```

## Artifact verification

Commands used from the repository root:

```bash
curl -fsSL -o /tmp/quickjs-probe/wrapper-android-3.2.3.aar \
  https://repo.maven.apache.org/maven2/wang/harlon/quickjs/wrapper-android/3.2.3/wrapper-android-3.2.3.aar
unzip -l /tmp/quickjs-probe/wrapper-android-3.2.3.aar
curl -fsSL -o /tmp/quickjs-probe/wrapper-android-3.2.3.pom \
  https://repo.maven.apache.org/maven2/wang/harlon/quickjs/wrapper-android/3.2.3/wrapper-android-3.2.3.pom
```

The AAR contains native libraries for all required release ABIs:

- `arm64-v8a/libquickjs-android-wrapper.so`
- `armeabi-v7a/libquickjs-android-wrapper.so`
- `x86/libquickjs-android-wrapper.so`
- `x86_64/libquickjs-android-wrapper.so`

The POM declares Apache License 2.0 and a matching `wang.harlon.quickjs:wrapper-java:3.2.3` dependency. The wrapper sources verify these APIs:

- `QuickJSLoader.init()` loads `quickjs-android-wrapper`.
- `QuickJSContext.create()` creates a context and `close()` destroys it.
- `QuickJSContext.evaluate(String)` evaluates a script.
- `JSObject.setProperty(String, byte[])` passes binary data through the wrapper as an ArrayBuffer-compatible value; the verified smoke script reads it with `new Uint8Array(bytes).byteLength`.
- `QuickJSContext.setMemoryLimit(int)` and `setMaxStackSize(int)` are available for later runtime hardening.
- The published Java/Android API exposes synchronous `evaluate()`/`evaluateModule()`/`execute()` only. The inspected source has no public pending-job or job-loop API.
- Context operations require the same thread that created the context, so the adapter uses one dedicated single-thread executor and closes in `finally`.

The AAR metadata declares `minCompileSdk=1`; this app builds with `minSdk=26` (and `compileSdk=36`). The app build probe below is the authoritative compatibility check.

## Android smoke proof

`QuickJsSmokeAdapter` is intentionally a runtime-only probe, not a parser. It copies the input `ByteArray`, evaluates:

```javascript
JSON.stringify({ok:true, length:new Uint8Array(bytes).byteLength})
```

and always closes the context on the executor thread. The JVM test uses a recording context seam to prove copying, result validation, executor-safe lifecycle, and cleanup on evaluation failure. The instrumented test runs the real native wrapper and verifies the same behavior on a device/emulator.

The real-device Promise smoke evaluates `Promise.resolve("resolved").then(...)` and observes `pending` in the returned JSON. This demonstrates that `evaluate()` returns before the pending Promise job runs. No verified job-loop API was found, so this route does not provide Promise/job-queue behavior. Task 2 through Task 5 must not assume asynchronous plugin support; asynchronous parser bundles are blocked pending an engine/API decision.

Run the focused checks:

```bash
node tools/document-plugins/smoke/quickjs-smoke.js
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests "com.omnichat.util.*"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.omnichat.util.QuickJsSmokeInstrumentedTest
```

The instrumentation command requires an Android device or emulator. `--tests` is a JVM test option and must not be passed to `connectedDebugAndroidTest`; use the instrumentation runner property shown above. The normal Android build packages all four AAR ABIs; the instrumented smoke test exercises the ABI of the connected device. The complete connected suite is currently blocked by pre-existing compilation errors in `app/src/androidTest/java/com/omnichat/ui/performance/AnimationOptimizerTest.kt` (`Spring` type arguments, `durationMs`, `assertNotNull`, and `intValue`).

## License and remaining risks

- The wrapper Java/Android artifacts declare Apache-2.0 in both published POMs, and the upstream `quickjs-wrapper` repository `LICENSE` is Apache-2.0.
- The inspected `wrapper-android-3.2.3.aar` contains the four native `.so` files but no `LICENSE`, `NOTICE`, or source archive. The AAR alone does not establish the license/copyright/notice terms for the embedded QuickJS native submodule. The upstream wrapper repository points to `native/quickjs` as a separate submodule; its native license provenance must be verified from the exact upstream revision or a vendor notice before release. It is intentionally not inferred here.
- Final app distribution notice/attribution integration has not been added in this spike.
- The real-device Promise smoke proves that `evaluate()` leaves a `Promise.resolve(...).then(...)` callback pending; no public job-loop API was found. Async-dependent parser work is therefore blocked until an engine/API decision is made.
- The spike does not yet bundle or execute PDF/DOCX parsers and does not prove every ABI on physical hardware. The selected wrapper's API is synchronous; later work must keep evaluation off the UI thread and enforce bounded input/output and context lifetime.
- The requested version `323` remains unresolved as an exact artifact coordinate; `3.2.3` is the documented replacement.
