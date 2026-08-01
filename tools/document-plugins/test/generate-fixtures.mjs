/**
 * generate-fixtures.mjs — Generate binary DOCX fixtures for Android tests.
 *
 * Run: node tools/document-plugins/test/generate-fixtures.mjs
 */

import { writeFileSync, mkdirSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import JSZip from "jszip";

const __dirname = dirname(fileURLToPath(import.meta.url));
const FIXTURES_DIR = resolve(__dirname, "../../../app/src/test/resources/documents");

function makeContentTypes() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>`;
}

function makeRels() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>`;
}

function makeWordRels() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>`;
}

async function generateSimpleTable() {
  const zip = new JSZip();
  zip.file("[Content_Types].xml", makeContentTypes());
  zip.file("_rels/.rels", makeRels());
  zip.file("word/_rels/document.xml.rels", makeWordRels());
  zip.file("word/document.xml", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p>
      <w:r><w:t>Table Example</w:t></w:r>
    </w:p>
    <w:tbl>
      <w:tr>
        <w:tc><w:p><w:r><w:t>Name</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>Age</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>City</w:t></w:r></w:p></w:tc>
      </w:tr>
      <w:tr>
        <w:tc><w:p><w:r><w:t>Alice</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>30</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>New York</w:t></w:r></w:p></w:tc>
      </w:tr>
      <w:tr>
        <w:tc><w:p><w:r><w:t>Bob</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>25</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>London</w:t></w:r></w:p></w:tc>
      </w:tr>
    </w:tbl>
  </w:body>
</w:document>`);
  return await zip.generateAsync({ type: "uint8array" });
}

async function generateMalformed() {
  // Create a ZIP that is not a valid DOCX (missing word/document.xml)
  const zip = new JSZip();
  zip.file("some_random_file.txt", "this is not a docx");
  return await zip.generateAsync({ type: "uint8array" });
}

async function main() {
  mkdirSync(FIXTURES_DIR, { recursive: true });

  const simpleTable = await generateSimpleTable();
  writeFileSync(resolve(FIXTURES_DIR, "simple-table.docx"), Buffer.from(simpleTable));
  console.log("Created: simple-table.docx (" + simpleTable.length + " bytes)");

  const malformed = await generateMalformed();
  writeFileSync(resolve(FIXTURES_DIR, "malformed.docx"), Buffer.from(malformed));
  console.log("Created: malformed.docx (" + malformed.length + " bytes)");
}

main().catch(console.error);