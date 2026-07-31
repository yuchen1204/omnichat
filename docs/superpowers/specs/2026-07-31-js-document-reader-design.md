# JavaScript Document Reader Design

> **Created:** 2026-07-31
> **Status:** Draft for user review

## Overview

Replace the current Kotlin/PDFBox/Apache POI document parsing path with an embedded JavaScript runtime and two built-in JavaScript reader plugins. The first version supports PDF and DOCX only, extracts正文 and table text, and keeps the existing chat behavior: the parsed full text is embedded in the user message and sent through the existing `/chat/completions` flow.

This is a parser-layer replacement, not a document database or RAG implementation.

## Goals

- Read `.pdf` and `.docx` through built-in JavaScript plugins.
- Embed a small JavaScript runtime in the Android app.
- Keep parsing offline and independent of provider/model APIs.
- Extract ordinary text and table text.
- Preserve enough source boundaries for readable output (PDF page markers and DOCX block/table order where available).
- Reuse the existing attachment preview, message persistence, history replay, and full-text chat path.
- Return explicit parse failures instead of treating binary documents as UTF-8 text.
- Keep the runtime and plugins sandboxed to the current document's bytes.

## Non-Goals

- No PPT/PPTX, legacy `.doc`, TXT, Markdown, or other formats in this change.
- No image extraction or image-to-model attachments from documents.
- No OCR for scanned/image-only PDFs.
- No persistent document entity, chunk table, vector index, document search, or RAG retrieval.
- No document viewer UI or page-level citation UI.
- No network-loaded or user-installed document parser plugins.
- No change to the model provider API contract.

## Current Path and Replacement Boundary

Current flow:

```text
OpenDocument -> DocumentParser.kt -> AttachedFile.text
             -> <document_attachment>...full text...</document_attachment>
             -> Message.content -> /chat/completions
```

New flow:

```text
OpenDocument -> JsDocumentReader -> embedded JS runtime
             -> built-in pdf-reader.js/docx-reader.js
             -> ParseResult(text, warnings)
             -> AttachedFile.text
             -> <document_attachment>...full text...</document_attachment>
             -> Message.content -> /chat/completions
```

The replacement boundary is the document parser. `ApiClient`, `Message.content`, the attachment wrapper format, and the model request path remain unchanged in the first version.

## Architecture

### 1. Kotlin host: `JsDocumentReader`

Add a Kotlin service under `app/src/main/java/com/omnichat/util/` that:

1. Resolves the display name and MIME/extension from the selected `Uri`.
2. Reads the selected document into a bounded byte array on `Dispatchers.IO`.
3. Selects the built-in plugin by normalized extension/MIME.
4. Creates or reuses a short-lived JavaScript runtime instance.
5. Exposes only the current document's metadata and bytes to the plugin.
6. Converts the plugin result into a typed Kotlin `ParseResult`.
7. Maps runtime, plugin, size, timeout, and malformed-result failures to user-visible parse errors.

The host must not expose `Context`, `ContentResolver`, filesystem paths, Room, Java reflection, network clients, or arbitrary Android objects to JavaScript.

Suggested result shape:

```kotlin
data class ParseResult(
    val text: String,
    val warnings: List<String> = emptyList()
)
```

A successful result must contain non-blank text or an explicit warning indicating that the document has no extractable text. An empty result from a binary document must not silently fall back to UTF-8 decoding.

### 2. Embedded runtime

Use a small embeddable JavaScript engine suitable for Android (QuickJS-family runtime is the recommended direction). The selected library must support:

- Evaluating bundled JavaScript assets.
- Passing byte arrays or an equivalent binary representation.
- Deterministic synchronous/asynchronous plugin invocation.
- Explicit interrupt/timeout handling, or a host-side execution guard.
- Android API 26 and the app's supported ABIs.

The runtime is an implementation dependency, not a user-facing runtime selector. It is initialized lazily and closed after a bounded parse operation; pooling is optional only if the chosen engine can safely reset all global state between documents.

Runtime limits:

- No network or socket APIs.
- No Java/Android bridge or reflection.
- No unrestricted file or path APIs.
- Maximum input size enforced by Kotlin before execution.
- Maximum output length enforced by Kotlin after execution.
- Maximum execution duration with interruption/cancellation where supported.
- A fresh plugin context per document so one parse cannot retain another document's bytes.

The exact QuickJS Android artifact and JavaScript library versions are implementation-plan decisions and must be verified against API 26, supported ABIs, license terms, and release APK size before coding.

### 3. Built-in plugins

Package bundled plugins under:

```text
app/src/main/assets/document_plugins/
├── runtime/        # small shared helpers/polyfills, if needed
├── pdf-reader.js
└── docx-reader.js
```

Plugins expose one stable entry point:

```javascript
parseDocument({
  name,
  mimeType,
  bytes
}) -> {
  format,
  text,
  warnings
}
```

Plugins are shipped with the app and loaded only from assets. They are not downloaded, installed, or selected by users in this version.

## PDF Plugin

The PDF plugin must use a JavaScript PDF parser that can run inside the selected embedded runtime without browser-only APIs. It should:

- Iterate pages in source order.
- Extract text items and preserve their reading order as far as the library exposes it.
- Add stable page markers such as `--- Page 1 ---`.
- Attempt simple table reconstruction from text item coordinates when available.
- Emit simple tables as Markdown or readable delimited text.
- Fall back to ordered text when table geometry is ambiguous.
- Return warnings for pages with no text, malformed objects, or unsupported constructs.

PDF table extraction is heuristic because PDF generally stores positioned drawing/text operations rather than table semantics. Merged cells, complex nested tables, and multi-page table continuation may degrade to plain ordered text.

The PDF plugin does not extract embedded images and does not perform OCR. A scan-only PDF may therefore produce an empty or partial text result and must surface a warning.

## DOCX Plugin

The DOCX plugin must parse the OOXML package in JavaScript:

- Read the ZIP package.
- Parse `word/document.xml` and the required relationships/namespaces.
- Preserve block order for paragraphs and tables.
- Convert paragraph text to plain text.
- Convert table rows/cells to Markdown tables or readable delimited text.
- Preserve heading text and basic paragraph separation where represented in the XML.
- Handle common XML escaping, tabs, line breaks, and repeated/empty cells.
- Return warnings for unsupported blocks or malformed XML.

The first version does not promise full fidelity for floating text boxes, tracked revisions, comments, headers/footers, fields, embedded objects, or complex layout. Images are ignored rather than extracted.

## File Selection and UI Integration

Update the existing `OpenDocument` MIME list to allow only the supported document formats:

- `application/pdf`
- `application/vnd.openxmlformats-officedocument.wordprocessingml.document`

The picker may include `*/*` only if Android providers cannot reliably filter by both formats; the host must still reject unsupported extensions/MIME values before invoking JavaScript.

Update the existing parsing coroutine in `ChatScreen.kt` to call `JsDocumentReader` rather than `DocumentParser`. Keep `AttachedFile(name, text, path)` for the first version, but do not depend on `path` for replay because the persisted message continues to contain the parsed text.

Parsing failures must:

- Leave the selected attachment list unchanged.
- Stop the parsing indicator.
- Show a concise localized error identifying the file and failure category.
- Avoid sending a partially parsed document unless the parser explicitly returns a successful result with warnings.

Because document images are out of scope, parsing no longer appends extracted image paths to `selectedImagePaths`.

## Chat and Persistence Integration

Keep the existing attachment wrapper and message contract:

```text
<document_attachment name="file.pdf">
...full parsed text...
</document_attachment>

user question
```

The existing send paths (button, IME, and hardware keyboard) remain behaviorally equivalent. `Message.content` continues to store the parsed full text so history replay and retry work without reopening the original `Uri`. No schema migration is required for this parser-only change.

The existing long-term memory flow should be reviewed during implementation. Since the full attachment text is currently passed as ordinary user content, it can be considered by memory selection/synchronization. Preventing document text from entering memory is outside this first parser scope, but the implementation should not expand that coupling.

## Error Handling

Define stable host error categories:

- `UnsupportedFormat`
- `FileTooLarge`
- `UnreadableInput`
- `RuntimeUnavailable`
- `PluginLoadFailed`
- `PluginTimeout`
- `PluginMemoryLimit`
- `MalformedPluginResult`
- `ParseFailed`
- `NoExtractableText`

Log diagnostic details locally without logging document contents. UI messages should be localized and should not expose stack traces or raw plugin internals.

A plugin must never return arbitrary host objects. The host validates the result type, format, text length, and warning types before accepting it.

## Testing Strategy

### Kotlin unit tests

- Extension/MIME dispatch selects PDF and DOCX plugins and rejects all other formats.
- URI display-name fallback works without a provider display name.
- Input/output size limits and error mapping are enforced.
- Malformed or incomplete plugin results are rejected.
- Parser cancellation/timeout clears parsing state.
- No document image paths are added by the new path.

### JavaScript plugin tests

Run plugin logic in a host-compatible JS test environment where possible, using fixture documents:

- PDF with one text page.
- PDF with multiple pages and page markers.
- PDF with a simple coordinate-based table.
- Scan/image-only PDF warning case.
- DOCX with paragraphs and headings.
- DOCX with a simple table, empty cells, and escaped XML text.
- Corrupt/truncated PDF and DOCX packages.

### Android/integration tests

- File picker accepts PDF and DOCX and rejects unsupported binary files.
- Successful parse displays the attachment and sends the same wrapper format as before.
- Parse errors do not leave stale attachment/image state.
- History replay and retry send persisted parsed text.
- App builds on API 26-compatible configuration and supported release ABIs.

## Dependencies and Build Changes

- Add the selected QuickJS Android runtime dependency.
- Add only JavaScript parser/ZIP/XML libraries that can run in the chosen runtime without browser globals or Node-specific modules.
- Remove `pdfbox-android` once no other source uses it.
- Remove Apache POI dependencies only if no other feature uses them; current document-generation code must be checked before removal.
- Add plugin assets and any build step needed to bundle/minify them deterministically.
- Document licenses and third-party versions in the repository's existing dependency documentation convention.

## Rollout and Compatibility

This change preserves the `Message.content` format, so existing chat history remains readable. Previously stored messages are not re-parsed. New PDF/DOCX attachments use the JS path; unsupported legacy attachment formats no longer enter through the document picker.

If the runtime or plugin fails to initialize, the app should show a parse error rather than silently reverting to the old native parser, because the product requirement is that PDF/DOCX reading is performed by the JavaScript plugins. A temporary developer-only diagnostic switch may be added during implementation, but it must not be enabled in production.

## Acceptance Criteria

1. A normal PDF can be selected and its text appears in the outgoing attachment message.
2. A multi-page PDF includes readable page boundaries.
3. A simple PDF table is represented as readable text or Markdown.
4. A normal DOCX can be selected and its paragraphs appear in the outgoing message.
5. A DOCX table appears in readable text or Markdown in document order.
6. PDF/DOCX parsing executes through the embedded JavaScript runtime and built-in plugins; Kotlin does not call PDFBox/POI for reading.
7. Images are not extracted or sent as document image attachments.
8. Unsupported formats and malformed files produce localized errors, not UTF-8 garbage.
9. Existing message persistence, history replay, retry, and model API calls continue to work.
10. Unit, plugin, and integration tests cover the supported and failure paths.
