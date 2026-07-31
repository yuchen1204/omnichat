import { fileURLToPath } from "node:url";

export function assertPluginResult(value, expectedFormat) {
  if (!value || value.format !== expectedFormat || typeof value.text !== "string") {
    throw new Error("invalid document plugin result");
  }
  if (!Array.isArray(value.warnings)) throw new Error("warnings must be an array");
}

function runContractSmoke() {
  const valid = { format: "pdf", text: "smoke", warnings: [] };
  assertPluginResult(valid, "pdf");

  for (const invalid of [
    null,
    { format: "docx", text: "smoke", warnings: [] },
    { format: "pdf", text: 42, warnings: [] },
    { format: "pdf", text: "smoke", warnings: "none" },
  ]) {
    let rejected = false;
    try {
      assertPluginResult(invalid, "pdf");
    } catch {
      rejected = true;
    }
    if (!rejected) throw new Error("invalid plugin result was accepted");
  }

  console.log("plugin contract smoke passed");
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  runContractSmoke();
}
