/**
 * verify-assets.mjs — Validate bundled document plugin assets for Task 3.
 *
 * Checks:
 *  - All three required files exist under app/src/main/assets/document_plugins/
 *  - No file contains forbidden Node/browser/Promise APIs
 *  - Plugin files (pdf-reader.js, docx-reader.js) define parseDocument
 *  - runtime.js provides expected shared helpers
 *
 * Run: node tools/document-plugins/verify-assets.mjs
 * Expected exit code: 0 on success, 1 on failure
 */

import { existsSync, readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(__dirname, "../..");
const ASSETS_DIR = resolve(PROJECT_ROOT, "app/src/main/assets/document_plugins");

const REQUIRED_FILES = ["runtime.js", "pdf-reader.js", "docx-reader.js"];

// QuickJS engine does not support these APIs.
// This list is the synchronous-restriction enforcement set.
// NOTE: Patterns are checked against comment-stripped source to avoid
// false positives from JSDoc descriptions.
const FORBIDDEN_PATTERNS = [
  { pattern: /\basync\b/,        label: "async function" },
  { pattern: /\bawait\b/,        label: "await expression" },
  { pattern: /\bPromise\b/,      label: "Promise (only async-capable)" },
  { pattern: /\bWorker\b/,       label: "Worker API" },
  { pattern: /\bdocument\./,     label: "DOM document API" },
  { pattern: /\bwindow\./,       label: "DOM window API" },
  { pattern: /\bnavigator\./,    label: "DOM navigator API" },
  { pattern: /\bCanvas\b/,       label: "Canvas API" },
  { pattern: /\bcanvas\b/,       label: "canvas element" },
  { pattern: /\bnode:(\w+)/,     label: "node: protocol import" },
  { pattern: /\brequire\s*\(/,   label: "require() call" },
  { pattern: /\bprocess\b/,      label: "process global" },
  { pattern: /\bBuffer\b/,       label: "Buffer global" },
  { pattern: /\bimport\s+.*\s+from\s+["']/, label: "ES import statement (not supported)" },
  { pattern: /\bexport\s+/,      label: "ES export statement (not supported)" },
];

/**
 * Strip JavaScript comments (both // and /* *​/ from source code.
 * Preserves string contents that may contain comment-like patterns.
 * This is a best-effort stripper; very edge cases (regex literals with
 * comment-like content) are not handled.
 */
function stripComments(code) {
  var result = "";
  var i = 0;
  var inSingleLine = false;
  var inMultiLine = false;
  var inString = false;
  var stringChar = null;
  while (i < code.length) {
    var ch = code[i];
    // Handle string literals
    if (!inSingleLine && !inMultiLine) {
      if ((ch === '"' || ch === "'" || ch === "`") && !inString) {
        inString = true;
        stringChar = ch;
        result += ch;
        i++;
        continue;
      }
      if (inString && ch === stringChar) {
        // Check for escaped quote
        if (i > 0 && code[i - 1] === "\\") {
          // escaped, continue
        } else {
          inString = false;
          stringChar = null;
        }
        result += ch;
        i++;
        continue;
      }
      if (inString) {
        result += ch;
        i++;
        continue;
      }
    }
    // Line comment
    if (!inMultiLine && ch === "/" && i + 1 < code.length && code[i + 1] === "/") {
      inSingleLine = true;
      i += 2;
      continue;
    }
    // Block comment
    if (!inSingleLine && ch === "/" && i + 1 < code.length && code[i + 1] === "*") {
      inMultiLine = true;
      i += 2;
      continue;
    }
    // End of line comment
    if (inSingleLine && ch === "\n") {
      inSingleLine = false;
      result += ch;
      i++;
      continue;
    }
    if (inSingleLine) {
      i++;
      continue;
    }
    // End of block comment
    if (inMultiLine && ch === "*" && i + 1 < code.length && code[i + 1] === "/") {
      inMultiLine = false;
      i += 2;
      continue;
    }
    if (inMultiLine) {
      i++;
      continue;
    }
    result += ch;
    i++;
  }
  return result;
}

// Helpers that runtime.js must provide (checked by name, not exhaustive)
const REQUIRED_RUNTIME_HELPERS = [
  "registerParser",
  "result",
  "decodeBase64",
  "utf8ToString",
  "escapeXml",
  "normalizeLines",
  "safeSubstring",
  "safeArrayPush",
  "inflate",
  "readU16",
];

// Plugin files must define a synchronous parseDocument function
const PLUGIN_FILES = ["pdf-reader.js", "docx-reader.js"];

let exitCode = 0;
const errors = [];

function fail(message) {
  errors.push(message);
  console.error("  FAIL: " + message);
  exitCode = 1;
}

// ── Step 1: Check file existence ──────────────────────────────────────

console.log("Verifying document plugin assets...\n");

for (const fileName of REQUIRED_FILES) {
  const filePath = resolve(ASSETS_DIR, fileName);
  if (!existsSync(filePath)) {
    fail("Missing required asset: " + fileName);
  } else {
    console.log("  OK: " + fileName + " exists");
  }
}

// ── Step 2: Check forbidden API patterns ──────────────────────────────

const existingFiles = REQUIRED_FILES.filter((f) =>
  existsSync(resolve(ASSETS_DIR, f))
);

for (const fileName of existingFiles) {
  const filePath = resolve(ASSETS_DIR, fileName);
  const content = readFileSync(filePath, "utf-8");

  var stripped = stripComments(content);
  for (const { pattern, label } of FORBIDDEN_PATTERNS) {
    const match = stripped.match(pattern);
    if (match) {
      fail(
        `${fileName} contains forbidden ${label} (matched: "${match[0].trim()}")`
      );
    }
  }
  console.log("  OK: " + fileName + " has no forbidden APIs");
}

// ── Step 3: Check runtime.js provides required helpers ────────────────

const runtimePath = resolve(ASSETS_DIR, "runtime.js");
if (existsSync(runtimePath)) {
  const runtimeContent = readFileSync(runtimePath, "utf-8");

  for (const helper of REQUIRED_RUNTIME_HELPERS) {
    // Check that the helper name appears as a function definition or assignment
    const helperPattern = new RegExp(
      "function\\s+" + helper + "\\s*\\("
    );
    if (!helperPattern.test(runtimeContent)) {
      // Also accept var/let/const assignment patterns
      const assignPattern = new RegExp(
        "(?:var|let|const)\\s+" + helper + "\\s*="
      );
      if (!assignPattern.test(runtimeContent)) {
        fail(
          "runtime.js is missing required helper: " + helper
        );
      }
    }
  }
  console.log("  OK: runtime.js provides all required helpers");
}

// ── Step 4: Check plugin files define parseDocument ────────────────────

for (const fileName of PLUGIN_FILES) {
  const filePath = resolve(ASSETS_DIR, fileName);
  if (!existsSync(filePath)) {
    continue; // already reported above
  }
  const content = readFileSync(filePath, "utf-8");

  // Check that parseDocument is defined as a function
  if (!/function\s+parseDocument\s*\(/.test(content)) {
    fail(
      fileName +
        " does not define a synchronous parseDocument function"
    );
  }
  console.log("  OK: " + fileName + " defines parseDocument");
}

// ── Summary ───────────────────────────────────────────────────────────

console.log("");
if (exitCode === 0) {
  console.log("All asset verification checks passed.");
} else {
  console.error(errors.length + " verification check(s) failed.");
}
process.exit(exitCode);