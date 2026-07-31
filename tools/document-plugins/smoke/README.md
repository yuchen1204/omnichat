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
- `JSObject.setProperty(String, byte[])` passes binary data through the wrapper as a byte array; JavaScript can read its `length`.
- `QuickJSContext.setMemoryLimit(int)` and `setMaxStackSize(int)` are available for later runtime hardening.
- Context operations require the same thread that created the context, so the adapter uses one dedicated single-thread executor and closes in `finally`.

The AAR metadata declares `minCompileSdk=1`; this app builds with `minSdk=26` (and `compileSdk=36`). The app build probe below is the authoritative compatibility check.

## Android smoke proof

`QuickJsSmokeAdapter` is intentionally a runtime-only probe, not a parser. It copies the input `ByteArray`, evaluates:

```javascript
JSON.stringify({ok:true, length:bytes.length})
```

and always closes the context on the executor thread. The JVM test uses a recording context seam to prove copying, result validation, executor-safe lifecycle, and cleanup on evaluation failure. The instrumented test runs the real native wrapper and verifies the same behavior on a device/emulator.

Run the focused checks:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests "com.omnichat.util.*"
./gradlew :app:connectedDebugAndroidTest --tests "com.omnichat.util.QuickJsSmokeInstrumentedTest"
```

The second command requires an Android device or emulator. The normal Android build packages all four AAR ABIs; the instrumented smoke test exercises the ABI of the connected device.

## License and remaining risks

Apache License 2.0 is compatible with app distribution, subject to retaining the required notices in the final distribution. This spike does not yet bundle or execute PDF/DOCX parsers, does not validate Promise behavior, and does not prove every ABI on physical hardware. The selected wrapper's Java API is synchronous; later parser work must keep all evaluation off the UI thread and enforce bounded input/output and context lifetime. The requested version `323` remains unresolved as an exact artifact coordinate; `3.2.3` is the documented replacement.
