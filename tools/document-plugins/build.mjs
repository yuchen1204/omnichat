/**
 * build.mjs — Build script for document plugin JavaScript assets.
 *
 * Reads source files from app/src/main/assets/document_plugins/ and
 * ensures they are consistent and valid for the QuickJS engine.
 *
 * For Task 3, this is a validation-only build step. When Task 4/5 add
 * external dependencies (e.g. ZIP reader, XML parser, PDF parser), this
 * script will be extended to bundle them using esbuild with the
 * synchronous-only restrictions enforced.
 *
 * Run: node tools/document-plugins/build.mjs
 * Expected exit code: 0 on success, 1 on failure
 *
 * Dependencies: esbuild (npm), verify-assets.mjs
 */

import { existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(__dirname, "../..");
const ASSETS_DIR = resolve(
  PROJECT_ROOT,
  "app/src/main/assets/document_plugins"
);

const REQUIRED_SOURCES = ["runtime.js", "pdf-reader.js", "docx-reader.js"];

let exitCode = 0;

console.log("[document-plugins build] Starting...\n");

// ── Step 1: Verify source files exist ─────────────────────────────────

for (const fileName of REQUIRED_SOURCES) {
  const filePath = resolve(ASSETS_DIR, fileName);
  if (!existsSync(filePath)) {
    console.error("  MISSING: " + filePath);
    exitCode = 1;
  } else {
    console.log("  FOUND:   " + fileName);
  }
}

if (exitCode !== 0) {
  console.error(
    "\n[document-plugins build] FAILED: missing source files"
  );
  process.exit(exitCode);
}

// ── Step 2: Run the verification script ───────────────────────────────

console.log("\n  Running asset verification...\n");
const verifyScript = resolve(__dirname, "verify-assets.mjs");
const result = spawnSync("node", [verifyScript], {
  cwd: PROJECT_ROOT,
  stdio: "inherit",
  shell: false,
});

if (result.status !== 0) {
  console.error(
    "\n[document-plugins build] FAILED: verification errors"
  );
  process.exit(result.status || 1);
}

// ── Step 3: Future bundling step (placeholder) ────────────────────────
// When Task 4/5 add external dependencies, use esbuild here:
//
//   import * as esbuild from "esbuild";
//   await esbuild.build({
//     entryPoints: [...],
//     bundle: true,
//     outdir: ASSETS_DIR,
//     platform: "neutral",  // ensures no node: polyfills
//     format: "iife",       // produces an IIFE, no imports
//     target: "es5",        // matches QuickJS's ES2020 support
//     external: [],
//   });

console.log(
  "\n[document-plugins build] Complete. All assets verified."
);