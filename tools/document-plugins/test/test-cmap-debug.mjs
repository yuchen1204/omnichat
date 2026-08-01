/**
 * Debug CMap parser step by step.
 */
import fs from "node:fs";

const pdfSrc = fs.readFileSync("../../app/src/main/assets/document_plugins/pdf-reader.js", "utf8");
const runtime = fs.readFileSync("../../app/src/main/assets/document_plugins/runtime.js", "utf8");

// Evaluate the module code to define all functions
const code = (runtime + "\n" + pdfSrc).replace(/"use strict";?\s*/g, "");
eval(code);

// Simple test: parse a bfchar line
var line = "<4F60> <4F60>";
var parts = parseCmapLine(line);
console.log("parseCmapLine result:", JSON.stringify(parts));
console.log("parts[0] type:", typeof parts[0], "value:", parts[0]);
console.log("parts[1] type:", typeof parts[1], "value:", parts[1]);

// Test the full CMap parser
var cmapStr = "/CIDInit /ProcSet findresource begin\n" +
  "12 dict begin\n" +
  "begincmap\n" +
  "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n" +
  "/CMapName /Adobe-Identity-UCS def\n" +
  "/CMapType 2 def\n" +
  "1 begincodespacerange\n" +
  "<0000> <FFFF>\n" +
  "endcodespacerange\n" +
  "2 beginbfchar\n" +
  "<4F60> <4F60>\n" +
  "<597D> <597D>\n" +
  "endbfchar\n" +
  "endcmap\n" +
  "end\n" +
  "end";

console.log("\nCMap string:");
console.log(cmapStr);

var lookup = parseToUnicodeCMap(cmapStr);
console.log("\nlookup function:", typeof lookup);

var r1 = lookup(0x4F60);
console.log("lookup(0x4F60):", r1, typeof r1);
console.log("lookup(0x4F60) === '4F60':", r1 === "4F60");

var r2 = lookup(0x597D);
console.log("lookup(0x597D):", r2, typeof r2);
console.log("lookup(0x597D) === '597D':", r2 === "597D");