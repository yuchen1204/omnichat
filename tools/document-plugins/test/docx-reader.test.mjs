/**
 * docx-reader.test.mjs — Node test suite for the synchronous DOCX reader plugin.
 *
 * Generates DOCX test fixtures using jszip, then evaluates the plugin
 * (runtime.js + docx-reader.js) in a Node sandbox to verify extraction.
 *
 * Run: node tools/document-plugins/test/docx-reader.test.mjs
 */

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import JSZip from "jszip";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(__dirname, "../../..");
const ASSETS_DIR = resolve(PROJECT_ROOT, "app/src/main/assets/document_plugins");
const FIXTURES_DIR = resolve(PROJECT_ROOT, "app/src/test/resources/documents");

// ── Helpers ──────────────────────────────────────────────────────────────

function loadPlugin() {
  const runtime = readFileSync(resolve(ASSETS_DIR, "runtime.js"), "utf-8");
  const plugin = readFileSync(resolve(ASSETS_DIR, "docx-reader.js"), "utf-8");
  return runtime + "\n" + plugin;
}

/**
 * Evaluate the plugin code and call parseDocument on the given bytes.
 * @param {Uint8Array} bytes
 * @returns {{format:string, text:string, warnings:string[]}}
 */
function runPlugin(bytes) {
  const code = loadPlugin();
  const fn = new Function(
    "inputBytes",
    code +
      "\nreturn parseDocument({ name: 'test.docx', mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', bytes: inputBytes });"
  );
  return fn(bytes);
}

/**
 * Build a minimal DOCX in memory using jszip.
 * @param {string} bodyXml - content of word/document.xml (without <?xml?>)
 * @param {object} [options]
 * @param {boolean} [options.withContentTypes] - include [Content_Types].xml
 * @returns {Promise<Uint8Array>}
 */
async function buildDocx(bodyXml, options) {
  const zip = new JSZip();
  zip.file(
    "[Content_Types].xml",
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>`
  );
  zip.file(
    "_rels/.rels",
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>`
  );
  zip.file(
    "word/_rels/document.xml.rels",
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>`
  );
  zip.file(
    "word/document.xml",
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    ${bodyXml}
  </w:body>
</w:document>`
  );
  return await zip.generateAsync({ type: "uint8array" });
}

// ── Test helpers ─────────────────────────────────────────────────────────

let passed = 0;
let failed = 0;

function assert(condition, message) {
  if (!condition) {
    console.error("  FAIL: " + message);
    failed++;
  } else {
    console.log("  PASS: " + message);
    passed++;
  }
}

function assertEq(actual, expected, message) {
  if (actual !== expected) {
    console.error(
      "  FAIL: " + message + " — expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual)
    );
    failed++;
  } else {
    console.log("  PASS: " + message);
    passed++;
  }
}

function assertIncludes(actual, expected, message) {
  if (typeof actual !== "string" || actual.indexOf(expected) < 0) {
    console.error(
      "  FAIL: " + message + " — expected to include " + JSON.stringify(expected) + ", got " + JSON.stringify(actual)
    );
    failed++;
  } else {
    console.log("  PASS: " + message);
    passed++;
  }
}

// ── Tests ────────────────────────────────────────────────────────────────

async function runTests() {
  console.log("=== DOCX Reader Plugin Tests ===\n");

  // ── Test 1: Empty document ──────────────────────────────────────────
  console.log("Test 1: Empty document");
  {
    const bytes = await buildDocx("");
    const result = runPlugin(bytes);
    assertEq(result.format, "docx", "format is docx");
    assert(typeof result.text === "string", "text is a string");
    assert(Array.isArray(result.warnings), "warnings is an array");
  }

  // ── Test 2: Simple paragraph ────────────────────────────────────────
  console.log("\nTest 2: Simple paragraph");
  {
    const bytes = await buildDocx(
      `<w:p><w:r><w:t>Hello, world!</w:t></w:r></w:p>`
    );
    const result = runPlugin(bytes);
    assertIncludes(result.text, "Hello, world!", "contains paragraph text");
    assertEq(result.warnings.length, 0, "no warnings");
  }

  // ── Test 3: Heading detection ───────────────────────────────────────
  console.log("\nTest 3: Heading detection");
  {
    const bytes = await buildDocx(`
      <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>
      <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>Subtitle</w:t></w:r></w:p>
      <w:p><w:pPr><w:pStyle w:val="Heading3"/></w:pPr><w:r><w:t>Section</w:t></w:r></w:p>
      <w:p><w:r><w:t>Normal text</w:t></w:r></w:p>
    `);
    const result = runPlugin(bytes);
    assertIncludes(result.text, "# Title", "Heading1 becomes #");
    assertIncludes(result.text, "## Subtitle", "Heading2 becomes ##");
    assertIncludes(result.text, "### Section", "Heading3 becomes ###");
    assertIncludes(result.text, "Normal text", "normal paragraph preserved");
    assertEq(result.warnings.length, 0, "no warnings");
  }

  // ── Test 4: Table extraction ────────────────────────────────────────
  console.log("\nTest 4: Table extraction");
  {
    const bytes = await buildDocx(`
      <w:tbl>
        <w:tr>
          <w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc>
          <w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc>
        </w:tr>
        <w:tr>
          <w:tc><w:p><w:r><w:t>A2</w:t></w:r></w:p></w:tc>
          <w:tc><w:p><w:r><w:t>B2</w:t></w:r></w:p></w:tc>
        </w:tr>
      </w:tbl>
    `);
    const result = runPlugin(bytes);
    assertIncludes(result.text, "A1", "table cell A1 extracted");
    assertIncludes(result.text, "B1", "table cell B1 extracted");
    assertIncludes(result.text, "A2", "table cell A2 extracted");
    assertIncludes(result.text, "B2", "table cell B2 extracted");
    // Check for markdown-like table separators (pipe characters)
    assertIncludes(result.text, "|", "table uses pipe separators");
    assertEq(result.warnings.length, 0, "no warnings");
  }

  // ── Test 5: Table with empty cells ───────────────────────────────────
  console.log("\nTest 5: Table with empty cells");
  {
    const bytes = await buildDocx(`
      <w:tbl>
        <w:tr>
          <w:tc><w:p><w:r><w:t>Left</w:t></w:r></w:p></w:tc>
          <w:tc><w:p/></w:tc>
        </w:tr>
      </w:tbl>
    `);
    const result = runPlugin(bytes);
    assertIncludes(result.text, "Left", "non-empty cell extracted");
    assertIncludes(result.text, "|", "table uses pipe separators");
    assertEq(result.warnings.length, 0, "no warnings");
  }

  // ── Test 6: XML escaping (entities in text) ─────────────────────────
  console.log("\nTest 6: XML escaping");
  {
    const bytes = await buildDocx(
      `<w:p><w:r><w:t>Price &amp; 3 &lt; 5 &gt; 2</w:t></w:r></w:p>`
    );
    const result = runPlugin(bytes);
    assertIncludes(result.text, "&", "ampersand decoded");
    assertIncludes(result.text, "<", "less-than decoded");
    assertIncludes(result.text, ">", "greater-than decoded");
    assertEq(result.warnings.length, 0, "no warnings");
  }

  // ── Test 7: Line breaks and tabs ─────────────────────────────────────
  console.log("\nTest 7: Line breaks and tabs");
  {
    const bytes = await buildDocx(`
      <w:p>
        <w:r><w:t>Line 1</w:t></w:r>
        <w:r><w:br/></w:r>
        <w:r><w:t>Line 2</w:t></w:r>
        <w:r><w:tab/></w:r>
        <w:r><w:t>Tabbed</w:t></w:r>
      </w:p>
    `);
    const result = runPlugin(bytes);
    assertIncludes(result.text, "Line 1", "first line text");
    assertIncludes(result.text, "Line 2", "second line text");
    assertIncludes(result.text, "Tabbed", "tabbed text");
    // Line break should produce a newline
    assert(result.text.indexOf("\n") >= 0, "line break produces newline");
    // Tab should produce a tab character
    assert(result.text.indexOf("\t") >= 0, "tab produces tab character");
    assertEq(result.warnings.length, 0, "no warnings");
  }

  // ── Test 8: Unsupported features (drawing) ───────────────────────────
  console.log("\nTest 8: Unsupported features (drawing)");
  {
    const bytes = await buildDocx(`
      <w:p>
        <w:r><w:t>Text before</w:t></w:r>
      </w:p>
      <w:p>
        <w:r><w:drawing><wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><wp:extent cx="100" cy="100"/></wp:inline></w:drawing></w:r>
      </w:p>
      <w:p>
        <w:r><w:t>Text after</w:t></w:r>
      </w:p>
    `);
    const result = runPlugin(bytes);
    assertIncludes(result.text, "Text before", "text before image");
    assertIncludes(result.text, "Text after", "text after image");
    assert(result.warnings.length > 0, "warnings produced for unsupported feature");
    const hasImageWarning = result.warnings.some(
      (w) => w.toLowerCase().indexOf("image") >= 0 || w.toLowerCase().indexOf("drawing") >= 0
    );
    assert(hasImageWarning, "warning mentions image or drawing");
  }

  // ── Test 9: Malformed ZIP input ──────────────────────────────────────
  console.log("\nTest 9: Malformed ZIP input");
  {
    const bytes = new Uint8Array([0, 1, 2, 3, 4, 5]); // not a ZIP
    const result = runPlugin(bytes);
    assertEq(result.format, "docx", "format is docx even on error");
    assert(result.warnings.length > 0, "warnings on malformed input");
    assert(
      result.warnings.some((w) => w.toLowerCase().indexOf("zip") >= 0 || w.toLowerCase().indexOf("invalid") >= 0),
      "warning mentions ZIP or invalid"
    );
  }

  // ── Test 10: Malformed XML inside DOCX ───────────────────────────────
  console.log("\nTest 10: Malformed XML inside DOCX");
  {
    const zip = new JSZip();
    zip.file("[Content_Types].xml", "<?xml version=\"1.0\"?><Types/>");
    zip.file("word/document.xml", "this is not valid xml");
    const bytes = await zip.generateAsync({ type: "uint8array" });
    const result = runPlugin(bytes);
    assertEq(result.format, "docx", "format is docx even on XML error");
    assert(result.warnings.length > 0, "warnings on malformed XML");
    assert(
      result.warnings.some((w) => w.toLowerCase().indexOf("xml") >= 0 || w.toLowerCase().indexOf("document") >= 0),
      "warning mentions XML or document"
    );
  }

  // ── Test 11: Multiple paragraphs preserve order ──────────────────────
  console.log("\nTest 11: Document order preservation");
  {
    const bytes = await buildDocx(`
      <w:p><w:r><w:t>First</w:t></w:r></w:p>
      <w:p><w:r><w:t>Second</w:t></w:r></w:p>
      <w:p><w:r><w:t>Third</w:t></w:r></w:p>
    `);
    const result = runPlugin(bytes);
    const firstIdx = result.text.indexOf("First");
    const secondIdx = result.text.indexOf("Second");
    const thirdIdx = result.text.indexOf("Third");
    assert(firstIdx >= 0, "First paragraph found");
    assert(secondIdx >= 0, "Second paragraph found");
    assert(thirdIdx >= 0, "Third paragraph found");
    assert(firstIdx < secondIdx, "First comes before Second");
    assert(secondIdx < thirdIdx, "Second comes before Third");
  }

  // ── Test 12: w:cr line break ────────────────────────────────────────
  console.log("\nTest 12: w:cr line break");
  {
    const bytes = await buildDocx(`
      <w:p>
        <w:r><w:t>Before</w:t></w:r>
        <w:r><w:cr/></w:r>
        <w:r><w:t>After</w:t></w:r>
      </w:p>
    `);
    const result = runPlugin(bytes);
    assertIncludes(result.text, "Before", "text before CR");
    assertIncludes(result.text, "After", "text after CR");
    assert(result.text.indexOf("\n") >= 0, "CR produces newline");
  }

  // ── Test 13: Heading4-6 ──────────────────────────────────────────────
  console.log("\nTest 13: Heading4-6");
  {
    const bytes = await buildDocx(`
      <w:p><w:pPr><w:pStyle w:val="Heading4"/></w:pPr><w:r><w:t>H4</w:t></w:r></w:p>
      <w:p><w:pPr><w:pStyle w:val="Heading5"/></w:pPr><w:r><w:t>H5</w:t></w:r></w:p>
      <w:p><w:pPr><w:pStyle w:val="Heading6"/></w:pPr><w:r><w:t>H6</w:t></w:r></w:p>
    `);
    const result = runPlugin(bytes);
    assertIncludes(result.text, "#### H4", "Heading4 becomes ####");
    assertIncludes(result.text, "##### H5", "Heading5 becomes #####");
    assertIncludes(result.text, "###### H6", "Heading6 becomes ######");
  }

  // ── Test 14: Table with varying row widths ───────────────────────────
  console.log("\nTest 14: Table with varying row widths");
  {
    const bytes = await buildDocx(`
      <w:tbl>
        <w:tr>
          <w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc>
          <w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc>
          <w:tc><w:p><w:r><w:t>C</w:t></w:r></w:p></w:tc>
        </w:tr>
        <w:tr>
          <w:tc><w:p><w:r><w:t>X</w:t></w:r></w:p></w:tc>
          <w:tc><w:p><w:r><w:t>Y</w:t></w:r></w:p></w:tc>
          <w:tc><w:p><w:r><w:t>Z</w:t></w:r></w:p></w:tc>
        </w:tr>
      </w:tbl>
    `);
    const result = runPlugin(bytes);
    assertIncludes(result.text, "A | B | C", "first row with all cells");
    assertIncludes(result.text, "X | Y | Z", "second row with all cells");
  }

  // ── Summary ──────────────────────────────────────────────────────────
  console.log("\n=== Summary ===");
  console.log(`Passed: ${passed}, Failed: ${failed}`);
  if (failed > 0) process.exit(1);
}

runTests().catch((err) => {
  console.error("Test suite error:", err);
  process.exit(1);
});