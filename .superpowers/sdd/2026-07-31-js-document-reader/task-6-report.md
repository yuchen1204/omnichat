# Task 6 Report: Kotlin JsDocumentReader Facade

Date: 2026-07-31
Task brief: `E:\omnichat\.superpowers\sdd\2026-07-31-js-document-reader\task-6-brief.md`
Status: **DONE_WITH_CONCERNS**

## Summary

Implemented and tested the Kotlin `JsDocumentReader` facade over the Task 2 synchronous bundled QuickJS runtime. The facade accepts only PDF and DOCX, validates extension/MIME agreement before opening bytes, reads bounded content from a `ContentResolver`, passes only typed document metadata and bytes to the selected bundled plugin, validates plugin output, and always closes the runtime after a created runtime has been used.

The reader input limit is explicitly **4 MiB**, matching the current production runtime default. The original brief's 50 MiB example was not retained because the Task 2 runtime rejects inputs above 4 MiB; allowing 50 MiB in the reader would only defer the same failure to the runtime. Reader output validation is also capped at **4 MiB**, matching the runtime's output budget.

No `Promise`, `async`, or `await` support was added. No changes were made to `ChatScreen`, `DocumentParser`, PDF/DOCX plugin assets, or the existing parser implementations.

## Changed files

- `E:\omnichat\app\src\main\java\com\omnichat\util\JsDocumentReader.kt`
  - Added the URI-reading and dispatch facade.
  - Uses `BundledQuickJsDocumentRuntime(context.assets)` as the production default.
  - Uses a typed `JsDocumentParseRuntime` factory seam for JVM tests; this does not expose raw JavaScript source or the internal runtime coordinator.
  - Uses `Dispatchers.IO` by default and permits a dispatcher override only for deterministic tests.
  - Resolves `OpenableColumns.DISPLAY_NAME`, then `Uri.lastPathSegment`, then `unknown_file`.
  - Normalizes extension and MIME case and strips MIME parameters after `;`.
  - Dispatches only to `document_plugins/pdf-reader.js` or `document_plugins/docx-reader.js`.
  - Rejects unsupported formats and extension/MIME mismatches before opening the stream.
  - Reads no more than `MAX_INPUT_BYTES + 1` bytes, closes the stream with `use`, and maps provider/read failures to the existing taxonomy.
  - Preserves coroutine cancellation rather than converting cancellation into a parse or input error.
  - Validates output UTF-8 size and blank-text/warning semantics without logging document content.
  - Ensures a created runtime is closed on success, parser error, and result-validation error; cleanup failure does not replace the primary result/error.

- `E:\omnichat\app\src\main\java\com\omnichat\util\JsDocumentRuntime.kt`
  - Reverted the unsafe local `open class`/`open fun` test workaround.
  - Added the high-level typed `JsDocumentParseRuntime` interface.
  - Made the closed production `BundledQuickJsDocumentRuntime` implement that interface.
  - The production constructor remains `BundledQuickJsDocumentRuntime(AssetManager)` and still owns bundled asset loading and the synchronous runtime boundary.
  - No arbitrary-script constructor or raw source injection path was added.

- `E:\omnichat\app\src\test\java\com\omnichat\util\JsDocumentReaderTest.kt`
  - Replaced production-class subclassing with a controlled fake implementing `JsDocumentParseRuntime`.
  - Added/updated coverage for dispatch, MIME parameters, fallback names, unsupported formats, mismatch rejection, bounded reads, exact-limit acceptance, stream closure, runtime closure, runtime errors, output validation, blank text/warnings, runtime creation failure, metadata-only runtime input, and provider failures.

- `E:\omnichat\.superpowers\sdd\2026-07-31-js-document-reader\task-6-report.md`
  - This report.

`DocumentPluginProtocolTest.kt` was not present in the current repository, so it was not created or modified. Existing `JsDocumentRuntimeTest.kt` remains the protocol/runtime test location from Task 2.

## Facade contract

The production default remains equivalent to:

```kotlin
JsDocumentReader(context).parse(uri)
```

The reader's test seam is typed at the high-level result boundary:

```kotlin
interface JsDocumentParseRuntime : AutoCloseable {
    fun parse(pluginAsset: String, input: JsDocumentInput): DocumentParseResult
    override fun close()
}
```

The production runtime still loads only app-bundled assets through `AssetManager`; the test fake does not provide arbitrary script source to production code.

## Format and metadata behavior

Accepted combinations:

- `.pdf` with `application/pdf`, including MIME parameters and case variations.
- `.docx` with `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, including MIME parameters.
- A missing/ambiguous extension with one of the two supported MIME types.

Rejected before `openInputStream`:

- TXT, PPT, unknown extensions, unknown binary MIME types, and no extension/no MIME.
- Any explicit extension/MIME mismatch, such as `.pdf` with the DOCX MIME.
- Any unsupported MIME, even when the extension appears supported.

Display-name resolution is:

1. `OpenableColumns.DISPLAY_NAME` when available and non-blank.
2. `Uri.lastPathSegment` when metadata is unavailable, null, or blank.
3. `unknown_file` when neither source provides a name.

The URI itself is never placed in `JsDocumentInput`; only the resolved name, MIME type, and bounded byte snapshot cross the reader/runtime boundary.

## Limits and cleanup

- `JsDocumentReader.MAX_INPUT_BYTES = 4 * 1024 * 1024`.
- `JsDocumentReader.MAX_OUTPUT_BYTES = 4 * 1024 * 1024`.
- The reader probes at most one byte beyond the input limit to distinguish exact-limit input from overflow.
- A zero-progress stream is stopped after three consecutive zero-byte reads and mapped to `UnreadableInput`, avoiding an infinite loop.
- Streams are closed on success, read failure, and overflow.
- A created runtime is closed in `finally` after parse/result validation, including when parsing throws.
- Runtime close failures are intentionally suppressed so they cannot replace the primary parse result or failure.

## Error mapping

The facade uses the existing `DocumentParseErrorCategory` values:

- Unsupported extension/MIME/mismatch: `UnsupportedFormat`.
- Null/open/read/provider stream failures: `UnreadableInput`.
- Input over 4 MiB: `FileTooLarge`.
- Runtime factory failure: `RuntimeUnavailable`.
- Runtime/plugin errors already represented as `DocumentParseException`: propagated unchanged.
- Other runtime parse failures: `ParseFailed`.
- Output text/warning bytes over 4 MiB: `PluginMemoryLimit`.
- Blank text with no meaningful warning: `NoExtractableText`.
- Runtime-owned plugin load, timeout, malformed-result, and memory classifications remain supplied by the Task 2 runtime and are not flattened by the reader.

No document text, bytes, URI, or plugin output is logged.

## Tests added or updated

The focused class has **31 tests**, all passing. Coverage includes:

- PDF extension dispatch.
- DOCX extension dispatch.
- PDF and DOCX MIME dispatch when extension is ambiguous.
- MIME parameters and case normalization.
- Uppercase extension normalization.
- Extension/MIME mismatch rejection before stream opening.
- TXT, PPT, unknown MIME, and unknown format rejection.
- Display-name, last-path-segment, and `unknown_file` fallback.
- Exact 4 MiB input acceptance.
- 4 MiB + 1 byte rejection with exactly the bounded probe read and stream closure.
- Successful stream closure and read-failure stream closure.
- Missing stream mapping.
- Runtime close after success and parser error.
- Runtime close failure not replacing a successful result.
- Runtime factory failure mapping.
- Existing runtime `DocumentParseException` preservation.
- Generic runtime exception mapping.
- Output-size mapping.
- Blank output with only blank warnings.
- Blank output with a meaningful warning.
- Runtime input contains name/MIME/bytes but not the original URI.
- Metadata query failure fallback.

## Verification evidence

### Focused reader tests

Command:

```text
./gradlew :app:testDebugUnitTest --tests "com.omnichat.util.JsDocumentReaderTest" --no-configuration-cache
```

Result:

```text
BUILD SUCCESSFUL in 2m 19s
34 actionable tasks: 9 executed, 25 up-to-date
```

The generated XML reports:

```text
tests="31" skipped="0" failures="0" errors="0"
```

### Utility JVM suite and debug build

Command:

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests "com.omnichat.util.*" --no-configuration-cache
```

Result:

```text
BUILD SUCCESSFUL in 18s
51 actionable tasks: 5 executed, 46 up-to-date
```

A final rerun after the last cancellation and bounded-read hardening also passed:

```text
BUILD SUCCESSFUL in 2s
51 actionable tasks: 1 executed, 50 up-to-date
```

The document-plugin asset build and verification completed successfully during both runs. `git diff --check` passed with no whitespace errors.

## Concerns

1. The Task 2 runtime's timeout remains cooperative rather than a guaranteed native hard kill because the selected QuickJS wrapper exposes no verified hard-interrupt API. This is an existing Task 2 limitation, not introduced by Task 6.
2. The ordinary connected Android test suite remains subject to the unrelated pre-existing `AnimationOptimizerTest.kt` compilation failures documented in the Task 2 report. Task 6 verification was intentionally focused on JVM utility tests and the debug build.
3. The 4 MiB reader/runtime limit is conservative and intentionally aligned with the current production runtime. Raising it requires representative PDF/DOCX device validation and coordinated runtime changes; the reader must not be raised independently.
4. The repository did not contain `DocumentPluginProtocolTest.kt`; the existing Task 2 runtime protocol tests were left unchanged.

## Commit

Pending at report creation; the implementation and report are committed by the Task 6 commit accompanying this report.
