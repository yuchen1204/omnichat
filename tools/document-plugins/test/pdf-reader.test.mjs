/**
 * pdf-reader.test.mjs — Node test suite for the synchronous PDF reader plugin.
 *
 * Generates PDF test fixtures, then evaluates the plugin (runtime.js + pdf-reader.js)
 * in a Node sandbox to verify text extraction.
 *
 * Run: node tools/document-plugins/test/pdf-reader.test.mjs
 */

import { readFileSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import zlib from "node:zlib";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(__dirname, "../../..");
const ASSETS_DIR = resolve(PROJECT_ROOT, "app/src/main/assets/document_plugins");
const FIXTURES_DIR = resolve(PROJECT_ROOT, "app/src/test/resources/documents");

// ── Helpers ────────────────────────────────────────────────────────────────

function loadPlugin() {
  const runtime = readFileSync(resolve(ASSETS_DIR, "runtime.js"), "utf-8");
  const plugin = readFileSync(resolve(ASSETS_DIR, "pdf-reader.js"), "utf-8");
  return runtime + "\n" + plugin;
}

function loadPluginApi() {
  return new Function(loadPlugin() + "\nreturn { collectPages, parseDocument };")();
}

/**
 * Evaluate the plugin code and call parseDocument on the given bytes.
 * @param {Uint8Array} bytes
 * @param {string} [name]
 * @returns {{format:string, text:string, warnings:string[]}}
 */
function runPlugin(bytes, name) {
  const code = loadPlugin();
  const fn = new Function(
    "inputBytes",
    code +
      "\nreturn parseDocument({ name: '" + (name || "test.pdf") + "', mimeType: 'application/pdf', bytes: inputBytes });"
  );
  return fn(bytes);
}

/**
 * Read a fixture file as a Uint8Array.
 */
function readFixture(name) {
  const data = readFileSync(resolve(FIXTURES_DIR, name));
  return new Uint8Array(data);
}

function pdfStream(data) {
  return `<< /Length ${Buffer.byteLength(data, "latin1")} >>\nstream\n${data}\nendstream`;
}

function buildPdf(objects, rootObjectNumber) {
  const objectNumbers = Object.keys(objects).map(Number).sort((a, b) => a - b);
  const maxObjectNumber = objectNumbers[objectNumbers.length - 1];
  let pdf = "%PDF-1.4\n%\xFF\xFF\xFF\xFF\n";
  const offsets = {};

  for (const objectNumber of objectNumbers) {
    offsets[objectNumber] = Buffer.byteLength(pdf, "latin1");
    pdf += `${objectNumber} 0 obj\n${objects[objectNumber]}\nendobj\n`;
  }

  const xrefOffset = Buffer.byteLength(pdf, "latin1");
  pdf += `xref\n0 ${maxObjectNumber + 1}\n`;
  pdf += "0000000000 65535 f \n";
  for (let objectNumber = 1; objectNumber <= maxObjectNumber; objectNumber++) {
    const offset = offsets[objectNumber];
    pdf += offset === undefined
      ? "0000000000 00000 f \n"
      : `${String(offset).padStart(10, "0")} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${maxObjectNumber + 1} /Root ${rootObjectNumber} 0 R >>\n`;
  pdf += `startxref\n${xrefOffset}\n%%EOF\n`;
  return new Uint8Array(Buffer.from(pdf, "latin1"));
}

function createIdentityHCjkPdf() {
  const cmap = `/CIDInit /ProcSet findresource begin
12 dict begin
begincmap
/CMapType 2 def
1 begincodespacerange
<0000> <FFFF>
endcodespacerange
2 beginbfchar
<0001> <4F60>
<0002> <597D>
endbfchar
1 beginbfrange
<0003> <0004> [<4E16> <754C>]
endbfrange
endcmap
end
end`;
  const content = `BT
/F1 12 Tf
1 0 0 1 50 750 Tm
<0001000200030004> Tj
ET`;

  return buildPdf({
    1: "<< /Type /Pages /Kids [3 0 R] /Count 1 /Resources << /Font << /F1 2 0 R >> >> >>",
    2: "<< /Type /Font /Subtype /Type0 /BaseFont /TestCjk /Encoding /Identity-H /DescendantFonts [7 0 R] /ToUnicode 6 0 R >>",
    3: "<< /Type /Page /Parent 1 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>",
    4: pdfStream(content),
    5: "<< /Type /Catalog /Pages 1 0 R >>",
    6: pdfStream(cmap),
    7: "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /TestCjk >>",
  }, 5);
}

// ── Test helpers ───────────────────────────────────────────────────────────

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

// ── Tests ──────────────────────────────────────────────────────────────────

function runTests() {
  console.log("=== PDF Reader Plugin Tests ===\n");

  // ── Test 1: Empty input ──────────────────────────────────────────────
  console.log("Test 1: Empty input");
  {
    const bytes = new Uint8Array(0);
    const result = runPlugin(bytes);
    assertEq(result.format, "pdf", "format is pdf");
    assert(typeof result.text === "string", "text is a string");
    assert(Array.isArray(result.warnings), "warnings is an array");
    assert(result.warnings.length > 0, "warnings for empty input");
  }

  // ── Test 2: Multi-page text PDF ──────────────────────────────────────
  console.log("\nTest 2: Multi-page text PDF");
  {
    const bytes = readFixture("text-pages.pdf");
    const result = runPlugin(bytes);
    assertEq(result.format, "pdf", "format is pdf");
    assertIncludes(result.text, "--- Page 1 ---", "page 1 marker present");
    assertIncludes(result.text, "--- Page 2 ---", "page 2 marker present");
    assertIncludes(result.text, "--- Page 3 ---", "page 3 marker present");
    assertIncludes(result.text, "Hello, World!", "page 1 text found");
    assertIncludes(result.text, "This is page one.", "page 1 second line found");
    assertIncludes(result.text, "Page two content", "page 2 text found");
    assertIncludes(result.text, "Page three", "page 3 text found");
    assertIncludes(result.text, "Special chars", "page 3 special chars text found");
  }

  // ── Test 3: Page ordering ────────────────────────────────────────────
  console.log("\nTest 3: Page ordering");
  {
    const bytes = readFixture("text-pages.pdf");
    const result = runPlugin(bytes);
    const page1Idx = result.text.indexOf("--- Page 1 ---");
    const page2Idx = result.text.indexOf("--- Page 2 ---");
    const page3Idx = result.text.indexOf("--- Page 3 ---");
    assert(page1Idx >= 0, "Page 1 marker found");
    assert(page2Idx >= 0, "Page 2 marker found");
    assert(page3Idx >= 0, "Page 3 marker found");
    assert(page1Idx < page2Idx, "Page 1 comes before Page 2");
    assert(page2Idx < page3Idx, "Page 2 comes before Page 3");
  }

  // ── Test 4: Text order within page ───────────────────────────────────
  console.log("\nTest 4: Text order within page");
  {
    const bytes = readFixture("text-pages.pdf");
    const result = runPlugin(bytes);
    // On page 1, "Hello, World!" should come before "This is page one."
    const page1Section = result.text.substring(result.text.indexOf("--- Page 1 ---"));
    const helloIdx = page1Section.indexOf("Hello, World!");
    const thisIdx = page1Section.indexOf("This is page one.");
    assert(helloIdx >= 0, "Hello, World! found on page 1");
    assert(thisIdx >= 0, "This is page one. found on page 1");
    assert(helloIdx < thisIdx, "Hello, World! comes before This is page one.");
  }

  // ── Test 5: Simple table detection ───────────────────────────────────
  console.log("\nTest 5: Simple table detection");
  {
    const bytes = readFixture("simple-table.pdf");
    const result = runPlugin(bytes);
    // The table should be detected and formatted as Markdown
    assertIncludes(result.text, "Name", "table header Name found");
    assertIncludes(result.text, "Age", "table header Age found");
    assertIncludes(result.text, "City", "table header City found");
    assertIncludes(result.text, "Alice", "data Alice found");
    assertIncludes(result.text, "Bob", "data Bob found");
    assertIncludes(result.text, "Charlie", "data Charlie found");
    // Check for Markdown table markers
    assertIncludes(result.text, "|", "table uses pipe separators");
    assertIncludes(result.text, "---", "table has separator row");
    // Check warning about table detection
    const hasTableWarning = result.warnings.some(function(w) {
      return w.toLowerCase().indexOf("table") >= 0;
    });
    assert(hasTableWarning, "warning about table detection");
  }

  // ── Test 6: Image-only PDF ───────────────────────────────────────────
  console.log("\nTest 6: Image-only PDF");
  {
    const bytes = readFixture("image-only.pdf");
    const result = runPlugin(bytes);
    assertEq(result.format, "pdf", "format is pdf");
    // Should have a warning about image-only
    const hasImageWarning = result.warnings.some(function(w) {
      return w.toLowerCase().indexOf("image") >= 0;
    });
    assert(hasImageWarning, "warning about image-only");
    // Text should contain page marker but no text content
    assertIncludes(result.text, "--- Page 1 ---", "page marker present");
  }

  // ── Test 7: Malformed PDF ────────────────────────────────────────────
  console.log("\nTest 7: Malformed PDF");
  {
    const bytes = readFixture("malformed.pdf");
    const result = runPlugin(bytes);
    assertEq(result.format, "pdf", "format is pdf even on error");
    // Should have a warning about failed parsing
    assert(result.warnings.length > 0, "warnings on malformed input");
    const hasParseWarning = result.warnings.some(function(w) {
      return w.toLowerCase().indexOf("fail") >= 0 || w.toLowerCase().indexOf("not a valid") >= 0 || w.toLowerCase().indexOf("header") >= 0;
    });
    assert(hasParseWarning, "warning mentions parse failure or invalid");
  }

  // ── Test 8: Cyclic Pages tree ────────────────────────────────────────
  console.log("\nTest 8: Cyclic Pages tree");
  {
    const api = loadPluginApi();
    const cyclicPages = {
      type: "dictionary",
      dict: {
        Type: { type: "name", value: "Pages" },
        Kids: { type: "array", items: [{ type: "reference", objNum: 1, genNum: 0 }] },
      },
    };
    const objMap = {
      1: { objNum: 1, genNum: 0, value: cyclicPages },
    };
    const pageRefs = [];
    const warnings = [];
    api.collectPages({ type: "reference", objNum: 1, genNum: 0 }, objMap, pageRefs, warnings);
    assertEq(pageRefs.length, 0, "cyclic Pages tree yields no page references");
    assert(
      warnings.some((w) => w.toLowerCase().indexOf("circular") >= 0),
      "warning mentions circular Pages/Kids reference"
    );
  }

  // ── Test 9: FlateDecode (compressed) PDF ──────────────────────────────
  console.log("\nTest 9: FlateDecode compressed PDF");
  {
    // Build a minimal PDF with FlateDecode compressed content streams
    const content = "BT /F1 12 Tf 1 0 0 1 50 750 Tm (FlateDecode works!) Tj ET";
    const compressed = zlib.deflateSync(content);

    // Build the PDF manually with correct xref offsets
    var header = "%PDF-1.4\n%\xFF\xFF\xFF\xFF\n";
    var obj1 = "1 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n";
    var obj2 = "2 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n";
    var obj3 = "3 0 obj\n<< /Type /Page /Parent 1 0 R /Resources << /Font << /F1 2 0 R >> >> /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n";
    var obj4Header = "4 0 obj\n<< /Length " + compressed.length + " /Filter /FlateDecode >>\nstream\n";
    var rawStream = "";
    for (var bi = 0; bi < compressed.length; bi++) {
      rawStream += String.fromCharCode(compressed[bi]);
    }
    var obj4Footer = "\nendstream\nendobj\n";
    var obj5 = "5 0 obj\n<< /Type /Catalog /Pages 1 0 R >>\nendobj\n";

    var off1 = header.length;
    var off2 = off1 + obj1.length;
    var off3 = off2 + obj2.length;
    var off4 = off3 + obj3.length;
    var off5 = off4 + obj4Header.length + rawStream.length + obj4Footer.length;
    var off5end = off5 + obj5.length;

    function pad10(n) { var s = "0000000000" + n; return s.substring(s.length - 10); }

    var fullPdf = header + obj1 + obj2 + obj3 + obj4Header + rawStream + obj4Footer + obj5;
    fullPdf += "xref\n0 6\n" + pad10(0) + " 65535 f \n";
    fullPdf += pad10(off1) + " 00000 n \n";
    fullPdf += pad10(off2) + " 00000 n \n";
    fullPdf += pad10(off3) + " 00000 n \n";
    fullPdf += pad10(off4) + " 00000 n \n";
    fullPdf += pad10(off5) + " 00000 n \n";
    fullPdf += "trailer\n<< /Size 6 /Root 5 0 R >>\n";
    fullPdf += "startxref\n" + off5end + "\n%%%%EOF";

    const bytes = new Uint8Array(fullPdf.length);
    for (var i = 0; i < fullPdf.length; i++) {
      bytes[i] = fullPdf.charCodeAt(i) & 0xFF;
    }

    const result = runPlugin(bytes);
    assertEq(result.format, "pdf", "format is pdf");
    assertIncludes(result.text, "FlateDecode works!", "FlateDecode compressed text extracted");
    assertIncludes(result.text, "--- Page 1 ---", "page marker present");
    // No warnings about unsupported filters
    const hasFilterWarning = result.warnings.some(function(w) {
      return w.toLowerCase().indexOf("filter") >= 0;
    });
    assert(!hasFilterWarning, "no warning about unsupported filter");
  }

  // -- Test 10: Identity-H CJK text with inherited resources ------------
  console.log("\nTest 10: Identity-H CJK ToUnicode decoding");
  {
    const result = runPlugin(createIdentityHCjkPdf(), "cjk.pdf");
    assertEq(result.format, "pdf", "format is pdf");
    assertIncludes(result.text, "\u4F60\u597D\u4E16\u754C", "two-byte CIDs decode through ToUnicode CMap");
    assert(
      result.text.indexOf("\u0000") < 0 && result.text.indexOf("\uFFFD") < 0,
      "decoded CJK text contains no raw NUL or replacement characters"
    );
  }

  // ── Test 11: Synchronous return (no Promise) ──────────────────────────
  console.log("\nTest 11: Synchronous return");
  {
    const bytes = readFixture("text-pages.pdf");
    var result;
    var threw = false;
    try {
      result = runPlugin(bytes);
    } catch (e) {
      threw = true;
      console.error("  FAIL: plugin threw unexpectedly: " + e.message);
      failed++;
    }
    if (!threw) {
      assert(result && typeof result.text === "string", "plugin returned synchronously");
      assert(typeof result.then !== "function", "result is not a Promise");
    }
  }

  // ── Test 12: No forbidden APIs in plugin code (comment-stripped check) ─
  console.log("\nTest 12: No forbidden APIs in plugin source");
  {
    const code = readFileSync(resolve(ASSETS_DIR, "pdf-reader.js"), "utf-8");
    // Strip comments
    var stripped = "";
    var i = 0;
    while (i < code.length) {
      if (code[i] === "/" && code[i + 1] === "/") {
        while (i < code.length && code[i] !== "\n") i++;
        i++;
        continue;
      }
      if (code[i] === "/" && code[i + 1] === "*") {
        i += 2;
        while (i < code.length && !(code[i] === "*" && code[i + 1] === "/")) i++;
        i += 2;
        continue;
      }
      if (code[i] === '"' || code[i] === "'") {
        var quote = code[i];
        stripped += quote;
        i++;
        while (i < code.length && code[i] !== quote) {
          if (code[i] === "\\") { stripped += code[i]; i++; if (i < code.length) { stripped += code[i]; i++; } }
          else { stripped += code[i]; i++; }
        }
        if (i < code.length) { stripped += code[i]; i++; }
        continue;
      }
      stripped += code[i];
      i++;
    }
    const forbiddenPatterns = [
      { pattern: /\basync\b/, label: "async" },
      { pattern: /\bawait\b/, label: "await" },
      { pattern: /\bPromise\b/, label: "Promise" },
      { pattern: /\bWorker\b/, label: "Worker" },
      { pattern: /\bdocument\./, label: "DOM document" },
      { pattern: /\bwindow\./, label: "DOM window" },
      { pattern: /\bnavigator\./, label: "DOM navigator" },
      { pattern: /\bCanvas\b/, label: "Canvas" },
      { pattern: /\bnode:(\w+)/, label: "node: protocol" },
      { pattern: /\brequire\s*\(/, label: "require()" },
      { pattern: /\bprocess\b/, label: "process" },
      { pattern: /\bBuffer\b/, label: "Buffer" },
    ];
    var allClean = true;
    for (var pi = 0; pi < forbiddenPatterns.length; pi++) {
      var fp = forbiddenPatterns[pi];
      if (fp.pattern.test(stripped)) {
        console.error("  FAIL: source contains forbidden " + fp.label);
        allClean = false;
        failed++;
      }
    }
    if (allClean) {
      console.log("  PASS: no forbidden APIs in source");
      passed++;
    }
  }

  // ── Summary ──────────────────────────────────────────────────────────
  console.log("\n=== Summary ===");
  console.log("Passed: " + passed + ", Failed: " + failed);
  if (failed > 0) process.exit(1);
}

runTests();