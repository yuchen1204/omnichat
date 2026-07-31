# JavaScript Document Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Android PDF/PPT/TXT parser with an embedded JavaScript runtime and built-in PDF/DOCX plugins that extract正文 and table text, while preserving the existing full-text chat attachment flow.

**Architecture:** `ChatScreen` will call a Kotlin `JsDocumentReader` facade. The facade validates the selected `Uri`, reads bounded bytes, dispatches to an asset-bundled PDF or DOCX plugin, executes it in an isolated QuickJS context, validates a JSON result, and returns parsed full text. The PDF path must use a synchronous, runtime-compatible PDF parser core; the complete asynchronous PDF.js implementation must not be a direct implementation dependency. The existing `<document_attachment>` message format and `Message.content` persistence remain unchanged; document images are not produced.

**Tech Stack:** Android/Kotlin/Compose, Gradle version catalog, QuickJS Android wrapper selected by the runtime spike, JavaScript plugin assets, a synchronous runtime-compatible PDF text-parser core (not the complete asynchronous PDF.js implementation), JavaScript ZIP/XML helpers for DOCX, JUnit/Robolectric, Node-based plugin fixture tests.

## Global Constraints

- This version requires every document plugin to expose a synchronous entry point.
- Do not depend on Promise/async/await, Worker, DOM, Canvas, Node APIs, or network access; one plugin invocation must complete in one synchronous `evaluate()` call.
- Supported document formats in this change are PDF and DOCX only.
- PDF/DOCX reading must execute through the embedded JavaScript runtime and built-in JavaScript plugins; Kotlin must not call PDFBox or Apache POI for reading.
- Extract正文 and table text; do not extract document images and do not perform OCR.
- Keep parsed full text in the existing `Message.content`/`<document_attachment>` flow; do not add a document database, chunk index, vector index, or RAG retrieval.
- Plugins are bundled app assets only; no network loading, user-installed parser code, Java/Android reflection, Room, or arbitrary filesystem access.
- Preserve existing model API calls, message history replay, and retry behavior; no Room migration is needed.
- App compatibility remains `minSdk = 26`, `targetSdk = 36`, Java 11 source/target, Kotlin 2.2.10, and supported release ABIs.
- Every parser failure must be explicit and localized; never decode an unsupported or malformed binary document as UTF-8 fallback.
- Do not remove Apache POI while `CreateDocumentTool.kt` still uses it for document generation.

---

## File Map

### Files to create

- `app/src/main/java/com/omnichat/util/JsDocumentReader.kt` — public Android facade, URI validation, bounded input, plugin dispatch, result/error mapping.
- `app/src/main/java/com/omnichat/util/JsDocumentRuntime.kt` — runtime abstraction and QuickJS implementation; owns one isolated context per parse.
- `app/src/main/java/com/omnichat/util/DocumentParseResult.kt` — typed result and stable error categories if they are not kept nested in the facade.
- `app/src/main/assets/document_plugins/runtime.js` — deterministic host-independent helpers required by both plugins.
- `app/src/main/assets/document_plugins/pdf-reader.js` — bundled/adapted PDF text extraction plugin and simple coordinate-based table formatting.
- `app/src/main/assets/document_plugins/docx-reader.js` — DOCX ZIP/XML/text/table extraction plugin.
- `tools/document-plugins/package.json` — pinned JavaScript build/test dependencies and scripts.
- `tools/document-plugins/build.mjs` — reproducible bundling/compatibility build that emits the checked-in or generated app assets.
- `tools/document-plugins/test/*.test.mjs` — plugin fixture tests for PDF/DOCX success and failure cases.
- `app/src/test/java/com/omnichat/util/JsDocumentReaderTest.kt` — dispatch, validation, result and error-mapping unit tests with a fake runtime.
- `app/src/test/java/com/omnichat/util/DocumentPluginProtocolTest.kt` — protocol/JSON shape tests where JVM-safe.
- `app/src/test/resources/documents/` — small binary PDF/DOCX fixtures and malformed inputs.

### Files to modify

- `gradle/libs.versions.toml` — add the selected QuickJS Android artifact version and any host-side dependency declaration.
- `app/build.gradle.kts` — add the QuickJS dependency, wire deterministic plugin asset generation before asset merge, and retain POI because `CreateDocumentTool` still generates DOCX/XLSX/PPTX.
- `app/src/main/java/com/omnichat/ui/screens/ChatScreen.kt:92-181,609-621,768-855,1061-1195` — call the new facade, filter to PDF/DOCX, remove document image-path coupling, and surface parse errors without mutating attachment state on failure.
- `app/src/main/java/com/omnichat/MyApplication.kt:5,14-17` — remove PDFBox initialization only after the dependency/source search confirms no remaining PDFBox use.
- `app/src/main/java/com/omnichat/util/DocumentParser.kt` — delete after all callers are migrated and no native parser references remain; preserve no UTF-8 binary fallback.
- `app/src/main/res/values/strings.xml` and `app/src/main/res/values-zh-rCN/strings.xml` — add localized parser errors and supported-format text following existing resource conventions.
- `CLAUDE.md:117` — update the utility package description and parser/build notes after implementation.

---

### Task 1: Runtime and plugin compatibility spike

**Files:**
- Create: `tools/document-plugins/package.json` — declare `{ "type": "module" }` for the Node ESM smoke fixture; Task 1 owns this configuration.
- Create: `tools/document-plugins/smoke/quickjs-smoke.js`
- Create: `tools/document-plugins/smoke/README.md`
- Modify: `gradle/libs.versions.toml` only after the candidate artifact is verified
- Modify: `app/build.gradle.kts` only with the verified candidate dependency

**Interfaces:**
- Produces a documented engine choice and a reproducible proof that Android can load the engine, evaluate an asset script, pass a binary buffer, and close the context.
- Produces a plugin runtime contract: `parseDocument({ name, mimeType, bytes }) -> { format, text, warnings }`.
- **Verified synchronous constraint:** `wang.harlon.quickjs:wrapper-android:3.2.3` was verified on a real Android smoke path to not drain Promise jobs from `evaluate()` and to expose no public pending-job API. The original smoke record above remains unchanged; this plan records the resulting constraint only: plugins must complete synchronously in the single `evaluate()` call, with no Promise pump.

- [ ] **Step 1: Add a failing compatibility checklist/test script.**

Create a Node smoke fixture that asserts the plugin contract shape and a Kotlin/JVM test seam that expects an engine capable of evaluating a script returning the byte length and a JSON object. The test must fail until the selected engine adapter is implemented.

```javascript
export function assertPluginResult(value, expectedFormat) {
  if (!value || value.format !== expectedFormat || typeof value.text !== "string") {
    throw new Error("invalid document plugin result");
  }
  if (!Array.isArray(value.warnings)) throw new Error("warnings must be an array");
}
```

- [ ] **Step 2: Verify candidate artifacts and licenses.**

Evaluate `wang.harlon.quickjs:wrapper-android:323` first, then QuickJS-NG/custom JNI only if the candidate cannot run the chosen parser bundle. Confirm the artifact has `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` libraries, works with `minSdk 26`, and has a license compatible with app distribution. Do not lock a dependency based only on a web search; run a Gradle dependency resolution/build probe.

- [ ] **Step 3: Implement the smallest Android engine smoke adapter.**

Use a dedicated single-thread executor. Create a context, evaluate a script such as `JSON.stringify({ok:true, length: bytes.length})`, pass a copied `ByteArray`/`Uint8Array` through the wrapper's supported API, assert the result, then always close the context. Do not expose `Context`, `Uri`, `File`, `ContentResolver`, or Java reflection to JavaScript.

- [ ] **Step 4: Run the smoke build/test and record the result.**

Run:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests "com.omnichat.util.*"
```

Expected: the engine loads on all configured build ABIs and the smoke protocol test passes. If the engine cannot provide the required binary/Promise behavior, stop and document the alternative engine before proceeding to parser tasks.

- [ ] **Step 5: Commit.**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts tools/document-plugins
git commit -m "build: validate embedded JavaScript document runtime"
```

### Task 2: Define the host/plugin protocol and runtime adapter

**Files:**
- Create: `app/src/main/java/com/omnichat/util/DocumentParseResult.kt`
- Create: `app/src/main/java/com/omnichat/util/JsDocumentRuntime.kt`
- Create: `app/src/test/java/com/omnichat/util/JsDocumentRuntimeTest.kt`

**Interfaces:**

The runtime interface is synchronous: one `parse(...)` call returns the JSON result from the same `evaluate()` invocation. It must not return a Promise, schedule Promise jobs, or implement a Promise/pending-job pump. Preserve the existing error taxonomy below, including timeout and resource-limit categories.

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

- [ ] **Step 1: Write failing protocol tests.**

Cover valid JSON, missing `format`, non-string `text`, non-array `warnings`, oversized output, plugin exception, and unsupported binary input. Assert that failures map to stable `DocumentParseErrorCategory` values and that the runtime is closed exactly once.

- [ ] **Step 2: Implement the runtime abstraction and fake runtime.**

Keep the protocol testable without native libraries. The fake runtime returns a supplied JSON string or throws a supplied exception. Validate JSON in Kotlin with the existing JSON dependency; do not accept arbitrary Java objects from a JS engine.

- [ ] **Step 3: Implement the real QuickJS adapter behind the interface.**

Load `runtime.js` and one plugin asset from `AssetManager`, inject only `{name, mimeType, bytes}`, call one exported/global `parseDocument` entry point, and consume the JSON string returned synchronously by that same `evaluate()` call before closing in `finally`. Do not return a Promise, schedule Promise jobs, or implement a Promise/pending-job pump. Use a fresh context per document. If the selected wrapper only accepts primitive bridge values, pass bytes as a bounded base64 string or chunked numeric array and decode to `Uint8Array` inside JS; never pass a filesystem path.

- [ ] **Step 4: Add cooperative execution limits.**

Enforce Kotlin input size before runtime creation and output size after evaluation. Run each parse on the dedicated executor and cancel/interupt the future after the configured deadline; on timeout close the context and classify `PluginTimeout`. Do not claim a hard QuickJS memory limit if the selected Java API does not expose one; use bounded input/output, one context per parse, executor cancellation, and a documented best-effort memory guard.

- [ ] **Step 5: Run focused tests.**

```bash
./gradlew testDebugUnitTest --tests "com.omnichat.util.JsDocumentRuntimeTest"
```

Expected: PASS for validation, cleanup, failure categories, and input/output limits.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/omnichat/util app/src/test/java/com/omnichat/util/JsDocumentRuntimeTest.kt
git commit -m "feat: add sandboxed document JavaScript runtime"
```

### Task 3: Build the shared JavaScript plugin bundle

**Files:**
- Modify: `tools/document-plugins/package.json` — retain the Task 1 `{ "type": "module" }` declaration; add the Task 3 build/test dependencies and scripts without removing that ESM setting.
- Create: `tools/document-plugins/build.mjs`
- Create: `app/src/main/assets/document_plugins/runtime.js`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- `runtime.js` provides synchronous `parseDocument` registration, bounded string/array helpers, UTF-8 decoding, XML escaping, line normalization, and `result(format, text, warnings)`.
- The build emits deterministic plugin assets under `app/src/main/assets/document_plugins/` before Android asset merging.
- Every plugin must remain synchronous and must not create or return a Promise; the generated bundle must be runtime-compatible without Promise/async/await, Worker, DOM, Canvas, `node:`, `require`, `process`, or `Buffer`.

- [ ] **Step 1: Add the failing asset build verification.**

Create a Gradle verification task/test that fails if `runtime.js`, `pdf-reader.js`, or `docx-reader.js` is absent, contains `async`, `await`, `Promise`, `Worker`, `DOM`, `Canvas`, or Node-only APIs (`node:`, `require`, `process`, `Buffer`), or does not expose the synchronous protocol entry point.

- [ ] **Step 2: Pin JS tool dependencies and scripts.**

Use a local `package-lock.json`; select only libraries that run synchronously without Promise/async/await, Worker, DOM, Canvas, or Node runtime APIs in the final bundle. The ZIP helper must expose a `Uint8Array`-based reader, and the XML helper must parse strings without DOM globals. If a candidate library cannot be bundled to the selected engine without those APIs, replace it with a small self-contained synchronous helper rather than adding a Node polyfill surface.

- [ ] **Step 3: Implement shared helpers.**

Make the bundle strict-mode and deterministic. Normalize CRLF/CR to LF, preserve blank paragraphs deliberately, cap accumulated output, convert unsupported control characters safely, and collect warnings rather than logging document content.

- [ ] **Step 4: Wire asset generation and run the verification.**

Make `mergeDebugAssets`/`mergeReleaseAssets` depend on the plugin build task. Run:

```bash
./gradlew generateUiTextKeys :app:mergeDebugAssets
```

Expected: all three assets are present and contain no Node/browser-only imports.

- [ ] **Step 5: Commit.**

```bash
git add tools/document-plugins app/src/main/assets/document_plugins app/build.gradle.kts
 git commit -m "build: bundle document JavaScript plugin helpers"
```

### Task 4: Implement the DOCX JavaScript reader

**Files:**
- Modify: `app/src/main/assets/document_plugins/docx-reader.js`
- Create: `tools/document-plugins/test/docx-reader.test.mjs`
- Create: `app/src/test/resources/documents/simple-table.docx`
- Create: `app/src/test/resources/documents/malformed.docx`

**Interfaces:**
- Registers a synchronous `parseDocument` for MIME `application/vnd.openxmlformats-officedocument.wordprocessingml.document` and extension `.docx`.
- Returns `{ format: "docx", text: string, warnings: string[] }` directly from the same `evaluate()` call and never creates or returns a Promise.
- The DOCX plugin must not use or produce `async`/`await`, Promise, Worker, DOM, Canvas, `node:`, `require`, `process`, or `Buffer`; Task 3's build validation must reject any such generated bundle.

- [ ] **Step 1: Add failing fixture tests.**

Create DOCX fixtures containing headings, ordinary paragraphs, escaped XML text, line breaks, empty cells, and a table. Assert that output preserves document order and renders a simple table in readable Markdown/delimited form. Assert corrupt ZIP/XML input returns a classified parse failure/warning rather than garbage.

- [ ] **Step 2: Implement ZIP package access.**

Read the OOXML ZIP package from the injected bytes, require `word/document.xml`, reject path traversal/unsupported compression metadata, and decode XML as UTF-8. Do not read external relationships, images, headers, or arbitrary package paths.

- [ ] **Step 3: Implement XML block extraction.**

Traverse `w:body` in order. For `w:p`, collect `w:t`, convert `w:tab` to a tab or spaces, convert `w:br`/`w:cr` to line breaks, and preserve paragraph boundaries. For `w:tbl`, collect `w:tr`/`w:tc` cell text and emit a Markdown table with escaped pipe characters and padded missing cells.

- [ ] **Step 4: Add headings and unsupported-feature warnings.**

Use common paragraph style names (`Heading1`…`Heading6`) to add readable heading markers without inventing content. Ignore image runs and unsupported floating objects while adding a warning. Do not extract binary media.

- [ ] **Step 5: Run plugin tests and Android protocol tests.**

```bash
node tools/document-plugins/test/docx-reader.test.mjs
./gradlew testDebugUnitTest --tests "com.omnichat.util.DocumentPluginProtocolTest"
```

Expected: PASS for paragraphs, tables, XML escaping, malformed package handling, and result validation.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/assets/document_plugins/docx-reader.js tools/document-plugins/test/docx-reader.test.mjs app/src/test/resources/documents
 git commit -m "feat: add JavaScript DOCX reader"
```

### Task 5: Implement the PDF JavaScript reader and table heuristic

**Files:**
- Modify: `app/src/main/assets/document_plugins/pdf-reader.js`
- Create: `tools/document-plugins/test/pdf-reader.test.mjs`
- Create: `app/src/test/resources/documents/text-pages.pdf`
- Create: `app/src/test/resources/documents/simple-table.pdf`
- Create: `app/src/test/resources/documents/image-only.pdf`
- Create: `app/src/test/resources/documents/malformed.pdf`

**Interfaces:**
- Registers a synchronous `parseDocument` for MIME `application/pdf` and extension `.pdf`.
- Returns `{ format: "pdf", text: string, warnings: string[] }` directly from the same `evaluate()` call and never creates or returns a Promise.
- The PDF plugin must not use or produce `async`/`await`, Promise, Worker, DOM, Canvas, `node:`, `require`, `process`, or `Buffer`; Task 3's build validation must reject any such generated bundle.

- [ ] **Step 1: Run the parser compatibility spike against the real target engine.**

Before committing to a PDF library, execute the actual bundled `pdf-reader.js` in the Android QuickJS adapter against `text-pages.pdf`. The spike must prove that the parser is synchronous, completes in the single `evaluate()` call, decodes the cross-reference/object streams, and exposes text items without `async`, `await`, `Promise`, `DOM`, `Canvas`, `Worker`, `fs`, `node:`, `require`, `process`, `Buffer`, or network APIs. If any candidate PDF parser requires async/await or Promise jobs, stop at this Task 5 compatibility gate and do not adopt it or fall back to PDFBox; choose a synchronous runtime-compatible parser core only after an explicit design decision.

- [ ] **Step 2: Add failing PDF fixture tests.**

Assert page markers (`--- Page 1 ---`), readable text order, simple table output from coordinates when available, a warning for image-only pages, and explicit failure for corrupt/truncated input. Test text containing Unicode/CJK and escaped characters.

- [ ] **Step 3: Implement PDF byte parsing and page traversal.**

Use the verified runtime-compatible parser core. Feed a `Uint8Array` from the host, iterate pages in source order, and collect text items with their coordinates/widths if exposed. Do not instantiate rendering, canvas, image, font, or worker subsystems.

- [ ] **Step 4: Implement deterministic text/table formatting.**

Group text items into lines using a documented y-coordinate tolerance, sort each line by x-coordinate, and detect repeated column boundaries for simple tables. Emit Markdown only when at least two stable columns and a header/data shape are detected; otherwise emit ordered line text. Cap per-page and total output and add warnings for ambiguous layout.

- [ ] **Step 5: Run plugin tests in Node and the real Android runtime.**

```bash
node tools/document-plugins/test/pdf-reader.test.mjs
./gradlew connectedDebugAndroidTest --tests "com.omnichat.util.DocumentPluginAndroidTest"
```

Expected: the exact same fixture produces valid page text in the target QuickJS runtime. A Node-only pass is insufficient.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/assets/document_plugins/pdf-reader.js tools/document-plugins/test/pdf-reader.test.mjs app/src/test/resources/documents
 git commit -m "feat: add JavaScript PDF text reader"
```

### Task 6: Add the Kotlin `JsDocumentReader` facade and dispatch tests

**Files:**
- Create: `app/src/main/java/com/omnichat/util/JsDocumentReader.kt`
- Modify: `app/src/test/java/com/omnichat/util/JsDocumentReaderTest.kt`
- Modify: `app/src/test/java/com/omnichat/util/DocumentPluginProtocolTest.kt`

**Interfaces:**

```kotlin
class JsDocumentReader(
    private val context: Context,
    private val runtimeFactory: () -> JsDocumentRuntime = { QuickJsDocumentRuntime(context) }
) {
    suspend fun parse(uri: Uri): DocumentParseResult
    fun getFileName(uri: Uri): String
}
```

- [ ] **Step 1: Add failing dispatch/URI tests.**

Use a fake `ContentResolver`/runtime to assert `.pdf` and DOCX MIME dispatch, display-name fallback to `lastPathSegment`, rejection of TXT/PPT/unknown files, bounded input reads, and that the original URI is never passed into JS. Assert the resolver stream is closed.

- [ ] **Step 2: Implement file metadata and supported-format validation.**

Resolve `OpenableColumns.DISPLAY_NAME`, normalize lowercase extension and MIME, accept PDF or DOCX only, and reject mismatches before reading bytes. Set explicit size limits (for example, 50 MiB input and 8 MiB output) as named constants so tests and UI copy use the same values.

- [ ] **Step 3: Implement bounded URI reading and runtime dispatch.**

Read from `ContentResolver.openInputStream(uri)` on the caller's IO dispatcher, stop at `MAX_INPUT_BYTES + 1`, call the appropriate asset plugin, validate `format/text/warnings`, reject blank text as `NoExtractableText` unless the plugin result contains a meaningful warning, and return a typed result.

- [ ] **Step 4: Implement stable error mapping.**

Map unsupported format, missing stream, size overflow, runtime initialization, plugin load, timeout, malformed JSON/result, and parser exceptions to `DocumentParseException`. Log only category and file metadata; never log document text or bytes.

- [ ] **Step 5: Run focused tests.**

```bash
./gradlew testDebugUnitTest --tests "com.omnichat.util.JsDocumentReaderTest" --tests "com.omnichat.util.DocumentPluginProtocolTest"
```

Expected: PASS for dispatch, validation, resource cleanup, result validation, and explicit failures.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/omnichat/util app/src/test/java/com/omnichat/util
 git commit -m "feat: add Kotlin JavaScript document reader facade"
```

### Task 7: Replace the ChatScreen parser path and localize errors

**Files:**
- Modify: `app/src/main/java/com/omnichat/ui/screens/ChatScreen.kt:92-181,609-621,768-855,1061-1195`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/assets/ui_text_keys.json` only through `generateUiTextKeys`
- Create/modify: `app/src/test/java/com/omnichat/ui/screens/ChatScreenDocumentTest.kt` if UI test seams are available

**Interfaces:**
- `ChatScreen` calls `JsDocumentReader.parse(uri)` and receives `DocumentParseResult`.
- Successful attachments remain `AttachedFile(name, text, path)`; no document-derived image paths are appended.

- [ ] **Step 1: Add failing UI/state tests.**

Test that a successful PDF/DOCX result adds one attachment and no image path, an unsupported/failed parse leaves existing attachments unchanged, `isParsingFile` returns to false, and send still produces the existing `<document_attachment name="...">` wrapper.

- [ ] **Step 2: Restrict the picker MIME list.**

Replace the existing TXT/PPT MIME array at `ChatScreen.kt:614-620` with PDF and DOCX MIME types. Keep host-side validation because Android document providers may ignore filters.

- [ ] **Step 3: Migrate the coroutine to `JsDocumentReader`.**

Instantiate the reader with `context.applicationContext`, parse on `Dispatchers.IO`, add the returned text only on success, and remove `extractImages = currentModelHasVision`. Do not add `result.imagePaths` to `selectedImagePaths`.

- [ ] **Step 4: Add localized error presentation.**

Add concise English/Chinese strings for unsupported format, unreadable file, oversized file, parse failure, timeout, and no extractable text. Use the existing screen context to show a transient error (Toast or an existing app-consistent mechanism) without exposing stack traces. Always reset `isParsingFile` in `finally`.

- [ ] **Step 5: Fix attachment state cleanup while touching the code.**

Do not associate document images because the new path produces none. Keep removal of document attachments independent from `selectedImagePaths`, and ensure the three send paths clear only the state they own after a successful send.

- [ ] **Step 6: Run UI/unit tests.**

```bash
./gradlew testDebugUnitTest --tests "com.omnichat.ui.screens.ChatScreenDocumentTest"
```

Expected: PASS for picker format, success/failure state transitions, wrapper preservation, and no document image coupling.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/com/omnichat/ui/screens/ChatScreen.kt app/src/main/res/values app/src/main/res/values-zh-rCN
 git commit -m "feat: route PDF and DOCX attachments through JavaScript"
```

### Task 8: Remove obsolete native reader wiring without breaking generation

**Files:**
- Delete: `app/src/main/java/com/omnichat/util/DocumentParser.kt`
- Modify: `app/src/main/java/com/omnichat/MyApplication.kt:5,16`
- Modify: `app/build.gradle.kts:227-230`
- Modify: `gradle/libs.versions.toml:42,46,49,100-101`
- Modify: `CLAUDE.md:117` and dependency notes

**Interfaces:**
- No production code references `DocumentParser`, `PDFBoxResourceLoader`, or POI reader APIs.
- Apache POI remains available for `CreateDocumentTool.kt` generation unless a separate, verified refactor removes it.

- [ ] **Step 1: Add a failing source/dependency audit.**

Run source searches and make the audit fail if any production reader path still imports `com.tom_roush.pdfbox` or `org.apache.poi` from `DocumentParser`, or if any picker still exposes TXT/PPT. Keep POI generation references explicitly allowed.

- [ ] **Step 2: Remove PDFBox-only initialization and reader dependency.**

Delete the `PDFBoxResourceLoader` import/call from `MyApplication.kt`. Remove `pdfbox-android` from Gradle/version catalog only after `grep` confirms no remaining production PDFBox references.

- [ ] **Step 3: Preserve POI for document generation.**

Do not remove `poi`/`poi-ooxml`: `CreateDocumentTool.kt:367-599` still creates XLSX/DOCX/PPTX. If dependency size reduction is desired, record it as a separate change rather than weakening this reader migration.

- [ ] **Step 4: Delete the old parser and update project docs.**

Delete `DocumentParser.kt`, update the package map to mention `JsDocumentReader` and built-in JS assets, and document the plugin build/test commands and supported formats.

- [ ] **Step 5: Run the source audit.**

```bash
grep -RIn --exclude-dir=build --exclude-dir=.gradle -E 'DocumentParser|PDFBoxResourceLoader|com\.tom_roush\.pdfbox' app/src/main || true
grep -RIn --exclude-dir=build --exclude-dir=.gradle -E 'application/vnd\.ms-powerpoint|text/plain' app/src/main/java/com/omnichat/ui/screens/ChatScreen.kt || true
```

Expected: no old parser/PDFBox references; no legacy picker MIME types; POI references remain only where generation requires them.

- [ ] **Step 6: Commit.**

```bash
git add -u app/src/main/java/com/omnichat/util/DocumentParser.kt app/src/main/java/com/omnichat/MyApplication.kt app/build.gradle.kts gradle/libs.versions.toml CLAUDE.md
git commit -m "refactor: remove native document reader"
```

### Task 9: End-to-end verification and release-size review

**Files:**
- Modify: `docs/superpowers/specs/2026-07-31-js-document-reader-design.md` only if implementation findings require an approved correction
- Create: `tools/document-plugins/README.md` with final build/test commands and license notes

- [ ] **Step 1: Run JavaScript fixture tests.**

```bash
npm --prefix tools/document-plugins test
npm --prefix tools/document-plugins run build
```

Expected: all PDF/DOCX success, table, Unicode, image-only warning, malformed-input, and Node/browser-API audit tests pass.

- [ ] **Step 2: Run Android unit tests and build.**

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Expected: all existing tests plus document-reader tests pass and debug APK packages the plugin assets and native engine libraries.

- [ ] **Step 3: Run device/instrumentation verification.**

```bash
./gradlew connectedDebugAndroidTest
```

Verify on an API 26+ device/emulator that PDF and DOCX picker selection, parsing progress, localized errors, attachment removal, send wrapper, history replay, and retry all work. Verify no document images are appended.

- [ ] **Step 4: Audit runtime sandbox and artifacts.**

Inspect the final JS bundle for `fetch`, `XMLHttpRequest`, `WebSocket`, `require`, `node:`, `process`, `eval` of external strings, Java bridge calls, and filesystem paths. Inspect the APK for the expected QuickJS ABIs and compare size impact with the prior debug APK.

- [ ] **Step 5: Verify no accidental data/API changes.**

Confirm Room schema/version is unchanged, existing `Message.content` wrapper is unchanged, no document bytes or source URI are logged, and provider API request payloads are unchanged except that document text now comes from JS.

- [ ] **Step 6: Run final verification before claiming completion.**

```bash
git diff --check
git status --short
./gradlew testDebugUnitTest assembleDebug
```

Report exact command results, test counts, APK size delta, and any skipped device-only checks. Do not claim completion if the real target QuickJS runtime was not exercised with both fixture formats.

- [ ] **Step 7: Commit verification docs.**

```bash
git add tools/document-plugins/README.md
git commit -m "test: verify JavaScript document reader end to end"
```

---

## Plan Self-Review

- **Spec coverage:** Runtime selection/limits are covered by Tasks 1-2; bundled plugins and offline sandbox by Tasks 2-5; PDF page/table behavior by Task 5; DOCX paragraphs/tables by Task 4; ChatScreen and localized errors by Task 7; dependency cleanup and POI generation preservation by Task 8; testing and acceptance criteria by Task 9.
- **Important feasibility gate:** Task 5 explicitly requires the real Android target runtime to execute the PDF fixture. A Node-only PDF parser test is not sufficient. If no compatible pure-JS PDF core is found, implementation must stop for a design decision rather than violating the “PDF through JS plugin” requirement or silently calling PDFBox.
- **Placeholder scan:** No `TBD`, `TODO`, or unspecified “add appropriate handling” steps remain. Candidate versions are verified during Task 1 rather than asserted without a build probe.
- **Type consistency:** `JsDocumentInput`, `JsDocumentRuntime.parse`, `DocumentParseResult`, `DocumentParseException`, and `JsDocumentReader.parse` are defined before their consuming tasks. The runtime returns JSON text; the facade owns JSON/result validation.
- **Scope check:** This is one parser migration with independent runtime, plugin, UI, cleanup, and verification tasks; no document-library/RAG subsystem is included.
