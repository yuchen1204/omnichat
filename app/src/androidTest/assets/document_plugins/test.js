function parseDocument(input) {
  return {
    format: "fixture",
    text: input.name + ":" + input.mimeType + ":" + input.bytes.length + ":" + input.bytes[2],
    warnings: ["real-adapter"]
  };
}
