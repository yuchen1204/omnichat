/**
 * generate-pdf-fixtures.mjs — Generate PDF test fixtures for the PDF reader plugin.
 *
 * Produces real PDF files by constructing them byte-by-byte. No external PDF
 * library is used — the output is valid PDF 1.4 that the JavaScript parser
 * in pdf-reader.js can consume.
 *
 * Run: node tools/document-plugins/test/generate-pdf-fixtures.mjs
 */

import { writeFileSync, mkdirSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const FIXTURES_DIR = resolve(__dirname, "../../../app/src/test/resources/documents");

// ── Low-level PDF building blocks ──────────────────────────────────────────

function pdfHeader() {
  return "%PDF-1.4\n%\xFF\xFF\xFF\xFF\n";
}

function pdfXref(objectOffsets, numObjects) {
  let xref = "xref\n";
  xref += `0 ${numObjects + 1}\n`;
  xref += "0000000000 65535 f \n";
  for (let i = 1; i <= numObjects; i++) {
    const offset = objectOffsets[i];
    if (offset !== undefined) {
      xref += `${String(offset).padStart(10, "0")} 00000 n \n`;
    } else {
      xref += "0000000000 00000 f \n";
    }
  }
  return xref;
}

function pdfTrailer(rootObjNum, numObjects) {
  return `trailer\n<< /Size ${numObjects + 1} /Root ${rootObjNum} 0 R >>\n`;
}

function pdfStartxref(offset) {
  return `startxref\n${offset}\n%%%%EOF\n`;
}

// ── Content stream helpers ─────────────────────────────────────────────────

function textContent(texts) {
  // texts is array of {text, x, y, size}
  let stream = "BT\n";
  for (const t of texts) {
    stream += `/F1 ${t.size || 12} Tf\n`;
    stream += `1 0 0 1 ${t.x || 0} ${t.y || 0} Tm\n`;
    const escaped = t.text
      .replace(/\\/g, "\\\\")
      .replace(/\(/g, "\\(")
      .replace(/\)/g, "\\)");
    stream += `(${escaped}) Tj\n`;
  }
  stream += "ET\n";
  return stream;
}

function pdfStream(content) {
  const len = Buffer.byteLength(content, "latin1");
  return `<< /Length ${len} >>\nstream\n${content}\nendstream\n`;
}

// ── Fixture generators ─────────────────────────────────────────────────────

/**
 * Generate a multi-page text PDF.
 * Page 1: "Hello, World!" + "This is page one."
 * Page 2: "Page two content" + CJK chars
 * Page 3: "Page three" + "Special chars: plus-minus-star-slash-pct"
 */
function generateTextPages() {
  const objects = [];
  const offsets = {};
  let currentOffset = 0;

  function emit(objNum, content) {
    const objStr = `1 0 obj\n${content}\nendobj\n`;
    // Actually we need the correct objNum
    // Let me rebuild
    return;
  }

  // Better approach: collect all parts and track offsets
  const parts = [];
  let offset = 0;

  function add(num, text) {
    offsets[num] = offset;
    parts.push(text);
    offset += Buffer.byteLength(text, "latin1");
  }

  // Font resource
  const fontDict = `<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>`;
  // Page 1 resources
  const res1 = `<< /Font << /F1 2 0 R >> >>`;
  // Page 1 content
  const content1 = textContent([
    { text: "Hello, World!", x: 50, y: 750, size: 18 },
    { text: "This is page one.", x: 50, y: 720, size: 12 }
  ]);
  const stream1 = pdfStream(content1);

  // Page 2 resources
  const res2 = `<< /Font << /F1 2 0 R >> >>`;
  // Page 2 content
  const content2 = textContent([
    { text: "Page two content", x: 50, y: 750, size: 14 },
    { text: "\u65E5\u672C\u8A9E", x: 50, y: 720, size: 12 } // Nihongo
  ]);
  const stream2 = pdfStream(content2);

  // Page 3 resources
  const res3 = `<< /Font << /F1 2 0 R >> >>`;
  // Page 3 content
  const content3 = textContent([
    { text: "Page three", x: 50, y: 750, size: 16 },
    { text: "Special chars: +-*/%", x: 50, y: 720, size: 12 }
  ]);
  const stream3 = pdfStream(content3);

  // Build objects
  // obj 1: Pages tree
  add(1, `1 0 obj\n<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R] /Count 3 >>\nendobj\n`);
  // obj 2: Font
  add(2, `2 0 obj\n${fontDict}\nendobj\n`);
  // obj 3: Page 1
  add(3, `3 0 obj\n<< /Type /Page /Parent 1 0 R /Resources ${res1} /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n`);
  // obj 4: Content stream 1
  add(4, `4 0 obj\n${stream1}\nendobj\n`);
  // obj 5: Page 2
  add(5, `5 0 obj\n<< /Type /Page /Parent 1 0 R /Resources ${res2} /MediaBox [0 0 612 792] /Contents 6 0 R >>\nendobj\n`);
  // obj 6: Content stream 2
  add(6, `6 0 obj\n${stream2}\nendobj\n`);
  // obj 7: Page 3
  add(7, `7 0 obj\n<< /Type /Page /Parent 1 0 R /Resources ${res3} /MediaBox [0 0 612 792] /Contents 8 0 R >>\nendobj\n`);
  // obj 8: Content stream 3
  add(8, `8 0 obj\n${stream3}\nendobj\n`);
  // obj 9: Catalog
  add(9, `9 0 obj\n<< /Type /Catalog /Pages 1 0 R >>\nendobj\n`);

  const header = pdfHeader();
  const headerLen = Buffer.byteLength(header, "latin1");

  // Adjust offsets for header
  for (const key in offsets) {
    if (offsets.hasOwnProperty(key)) {
      offsets[key] += headerLen;
    }
  }

  const body = parts.join("");
  const bodyLen = Buffer.byteLength(body, "latin1");
  const xrefOffset = headerLen + bodyLen;

  const xref = pdfXref(offsets, 9);
  const trailer = pdfTrailer(9, 9);
  const startxrefStr = pdfStartxref(xrefOffset);

  return Buffer.from(header + body + xref + trailer + startxrefStr, "latin1");
}

/**
 * Generate a PDF with a simple table using coordinate-based positioning.
 */
function generateSimpleTable() {
  const parts = [];
  const offsets = {};
  let offset = 0;

  function add(num, text) {
    offsets[num] = offset;
    parts.push(text);
    offset += Buffer.byteLength(text, "latin1");
  }

  const fontDict = `<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>`;
  const res = `<< /Font << /F1 2 0 R >> >>`;

  // Table content with positioned text
  const content = textContent([
    { text: "Name", x: 50, y: 740, size: 12 },
    { text: "Age", x: 200, y: 740, size: 12 },
    { text: "City", x: 350, y: 740, size: 12 },
    { text: "Alice", x: 50, y: 720, size: 12 },
    { text: "30", x: 200, y: 720, size: 12 },
    { text: "New York", x: 350, y: 720, size: 12 },
    { text: "Bob", x: 50, y: 700, size: 12 },
    { text: "25", x: 200, y: 700, size: 12 },
    { text: "London", x: 350, y: 700, size: 12 },
    { text: "Charlie", x: 50, y: 680, size: 12 },
    { text: "35", x: 200, y: 680, size: 12 },
    { text: "Paris", x: 350, y: 680, size: 12 }
  ]);
  const stream = pdfStream(content);

  add(1, `1 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n`);
  add(2, `2 0 obj\n${fontDict}\nendobj\n`);
  add(3, `3 0 obj\n<< /Type /Page /Parent 1 0 R /Resources ${res} /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n`);
  add(4, `4 0 obj\n${stream}\nendobj\n`);
  add(5, `5 0 obj\n<< /Type /Catalog /Pages 1 0 R >>\nendobj\n`);

  const header = pdfHeader();
  const headerLen = Buffer.byteLength(header, "latin1");

  for (const key in offsets) {
    if (offsets.hasOwnProperty(key)) {
      offsets[key] += headerLen;
    }
  }

  const body = parts.join("");
  const bodyLen = Buffer.byteLength(body, "latin1");
  const xrefOffset = headerLen + bodyLen;

  const xref = pdfXref(offsets, 5);
  const trailer = pdfTrailer(5, 5);
  const startxrefStr = pdfStartxref(xrefOffset);

  return Buffer.from(header + body + xref + trailer + startxrefStr, "latin1");
}

/**
 * Generate a PDF with only image display (no text content).
 * Content stream has only Do operator, no BT/ET.
 */
function generateImageOnly() {
  const parts = [];
  const offsets = {};
  let offset = 0;

  function add(num, text) {
    offsets[num] = offset;
    parts.push(text);
    offset += Buffer.byteLength(text, "latin1");
  }

  // Image XObject (1x1 white pixel)
  const imageData = "\x00";
  const imageXObject = `<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 1 /Length 1 >>\nstream\n${imageData}\nendstream`;
  const res = `<< /XObject << /Im0 2 0 R >> >>`;

  // Content stream with only image display (no text operations)
  const content = "q\n1 0 0 1 0 0 cm\n/Im0 Do\nQ\n";
  const stream = pdfStream(content);

  add(1, `1 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n`);
  add(2, `2 0 obj\n${imageXObject}\nendobj\n`);
  add(3, `3 0 obj\n<< /Type /Page /Parent 1 0 R /Resources ${res} /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n`);
  add(4, `4 0 obj\n${stream}\nendobj\n`);
  add(5, `5 0 obj\n<< /Type /Catalog /Pages 1 0 R >>\nendobj\n`);

  const header = pdfHeader();
  const headerLen = Buffer.byteLength(header, "latin1");

  for (const key in offsets) {
    if (offsets.hasOwnProperty(key)) {
      offsets[key] += headerLen;
    }
  }

  const body = parts.join("");
  const bodyLen = Buffer.byteLength(body, "latin1");
  const xrefOffset = headerLen + bodyLen;

  const xref = pdfXref(offsets, 5);
  const trailer = pdfTrailer(5, 5);
  const startxrefStr = pdfStartxref(xrefOffset);

  return Buffer.from(header + body + xref + trailer + startxrefStr, "latin1");
}

/**
 * Generate a malformed PDF (truncated, no valid structure).
 */
function generateMalformed() {
  return Buffer.from("%PDF-1.4\n%\xFF\xFF\xFF\xFF\nThis is not a valid PDF", "latin1");
}

// ── Main ───────────────────────────────────────────────────────────────────

function main() {
  mkdirSync(FIXTURES_DIR, { recursive: true });

  const textPages = generateTextPages();
  writeFileSync(resolve(FIXTURES_DIR, "text-pages.pdf"), textPages);
  console.log(`Created: text-pages.pdf (${textPages.length} bytes)`);

  const simpleTable = generateSimpleTable();
  writeFileSync(resolve(FIXTURES_DIR, "simple-table.pdf"), simpleTable);
  console.log(`Created: simple-table.pdf (${simpleTable.length} bytes)`);

  const imageOnly = generateImageOnly();
  writeFileSync(resolve(FIXTURES_DIR, "image-only.pdf"), imageOnly);
  console.log(`Created: image-only.pdf (${imageOnly.length} bytes)`);

  const malformed = generateMalformed();
  writeFileSync(resolve(FIXTURES_DIR, "malformed.pdf"), malformed);
  console.log(`Created: malformed.pdf (${malformed.length} bytes)`);

  console.log("\nAll PDF fixtures generated.");
}

main();