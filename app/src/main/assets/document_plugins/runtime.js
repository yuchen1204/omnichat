/**
 * runtime.js — Synchronous shared helpers for bundled document plugins.
 *
 * This file is concatenated with the plugin source before being evaluated by
 * QuickJS. All helpers are synchronous — no Promise, async/await, Worker,
 * DOM, Canvas, node:, require, process, or Buffer.
 *
 * Every plugin must define a synchronous `parseDocument({name, mimeType, bytes})`
 * function that returns `{format, text, warnings}`.
 *
 * The `bytes` parameter is a copied Uint8Array supplied by the host after
 * base64-decoding the input. The host already decodes; the `decodeBase64`
 * helper is provided here for standalone testing or alternative entry points.
 */

"use strict";

// ── Result factory ────────────────────────────────────────────────────

/**
 * Produce a plugin result object.
 * @param {string} format - "pdf", "docx", or "txt"
 * @param {string} text - extracted text content
 * @param {string[]} [warnings] - optional non-fatal warning messages
 * @returns {{format:string, text:string, warnings:string[]}}
 */
function result(format, text, warnings) {
  if (typeof format !== "string" || format.length === 0) {
    throw new Error("result: format must be a non-empty string");
  }
  if (typeof text !== "string") {
    throw new Error("result: text must be a string");
  }
  if (warnings != null && !Array.isArray(warnings)) {
    throw new Error("result: warnings must be an array or undefined");
  }
  return { format: format, text: text, warnings: warnings || [] };
}

// ── Base64 → Uint8Array (synchronous, no atob) ────────────────────────

/**
 * Decode a base64 string into a Uint8Array.
 * Pure synchronous implementation — no atob, no Buffer, no TextDecoder.
 * @param {string} base64
 * @returns {Uint8Array}
 */
function decodeBase64(base64) {
  var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  var padding = base64.endsWith("==") ? 2 : (base64.endsWith("=") ? 1 : 0);
  var output = new Uint8Array(Math.floor(base64.length * 3 / 4) - padding);
  var buffer = 0;
  var bits = 0;
  var outputIndex = 0;
  for (var i = 0; i < base64.length; i++) {
    var code = alphabet.indexOf(base64[i]);
    if (code < 0) continue;
    buffer = (buffer << 6) | code;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      output[outputIndex++] = (buffer >> bits) & 255;
    }
  }
  return output;
}

// ── UTF-8 bytes → string (synchronous, no TextDecoder) ────────────────

/**
 * Decode a Uint8Array of UTF-8 bytes into a JavaScript string.
 * Pure synchronous implementation — no TextDecoder, no Buffer.
 * Handles full Unicode including surrogate pairs (4-byte sequences).
 * @param {Uint8Array} bytes
 * @returns {string}
 */
function utf8ToString(bytes) {
  var result = "";
  var i = 0;
  while (i < bytes.length) {
    var byte1 = bytes[i++];
    if (byte1 < 0x80) {
      result += String.fromCharCode(byte1);
    } else if (byte1 < 0xE0) {
      var byte2 = bytes[i++];
      result += String.fromCharCode(((byte1 & 0x1F) << 6) | (byte2 & 0x3F));
    } else if (byte1 < 0xF0) {
      var byte2 = bytes[i++];
      var byte3 = bytes[i++];
      result += String.fromCharCode(
        ((byte1 & 0x0F) << 12) | ((byte2 & 0x3F) << 6) | (byte3 & 0x3F)
      );
    } else {
      var byte2 = bytes[i++];
      var byte3 = bytes[i++];
      var byte4 = bytes[i++];
      var codePoint =
        ((byte1 & 0x07) << 18) |
        ((byte2 & 0x3F) << 12) |
        ((byte3 & 0x3F) << 6) |
        (byte4 & 0x3F);
      codePoint -= 0x10000;
      result += String.fromCharCode(
        0xD800 + (codePoint >> 10),
        0xDC00 + (codePoint & 0x3FF)
      );
    }
  }
  return result;
}

// ── Line normalization (CRLF/CR → LF) ─────────────────────────────────

/**
 * Normalize line endings: CRLF and CR become LF.
 * Preserves blank paragraphs deliberately.
 * @param {string} text
 * @returns {string}
 */
function normalizeLines(text) {
  return text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
}

// ── XML escaping ──────────────────────────────────────────────────────

/**
 * Escape a string for safe inclusion in XML text content.
 * @param {string} text
 * @returns {string}
 */
function escapeXml(text) {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

// ── Bounded string helpers ────────────────────────────────────────────

/**
 * Safely extract a substring without exceeding the input length.
 * Returns the original string if indices are within bounds, otherwise
 * returns a safe substring. Never throws on out-of-range access.
 * @param {string} str
 * @param {number} start
 * @param {number} [end]
 * @returns {string}
 */
function safeSubstring(str, start, end) {
  if (typeof str !== "string") return "";
  var len = str.length;
  var s = Math.max(0, Math.min(start, len));
  var e = end !== undefined ? Math.max(s, Math.min(end, len)) : len;
  return str.substring(s, e);
}

/**
 * Truncate a string to a maximum length, appending an ellipsis if truncated.
 * @param {string} str
 * @param {number} maxLength
 * @param {string} [ellipsis]
 * @returns {string}
 */
function truncate(str, maxLength, ellipsis) {
  if (typeof str !== "string") return "";
  if (str.length <= maxLength) return str;
  var suffix = ellipsis !== undefined ? ellipsis : "...";
  return str.substring(0, Math.max(0, maxLength - suffix.length)) + suffix;
}

// ── Bounded array helpers ─────────────────────────────────────────────

/**
 * Push an item into an array only if the array is below the max length.
 * Returns true if the item was added, false if the array is full.
 * @param {Array} arr
 * @param {*} item
 * @param {number} maxLength
 * @returns {boolean}
 */
function safeArrayPush(arr, item, maxLength) {
  if (arr.length >= maxLength) return false;
  arr.push(item);
  return true;
}

// ── Control character sanitization ────────────────────────────────────

/**
 * Remove or replace control characters that are unsafe for text output.
 * Preserves \n (0x0A), \r (0x0D), and \t (0x09).
 * @param {string} text
 * @returns {string}
 */
function sanitizeText(text) {
  return text.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, "");
}

// ── XML text content extraction helper ─────────────────────────────────

/**
 * Extract the text content of an XML element by tag name.
 * Very simple synchronous parser — no DOM, no DOMParser.
 * Finds the first opening tag and extracts until the matching close tag.
 * Does not handle nested same-named elements; use for simple cases only.
 * @param {string} xml
 * @param {string} tagName
 * @returns {string|null}
 */
function extractXmlText(xml, tagName) {
  var openTag = "<" + tagName;
  var openIndex = xml.indexOf(openTag);
  if (openIndex < 0) return null;
  var contentStart = xml.indexOf(">", openIndex);
  if (contentStart < 0) return null;
  contentStart += 1;
  var closeTag = "</" + tagName + ">";
  var closeIndex = xml.indexOf(closeTag, contentStart);
  if (closeIndex < 0) return null;
  return xml.substring(contentStart, closeIndex);
}

// ── ParseDocument registration helper ─────────────────────────────────

/**
 * Registry of parseDocument implementations by format key.
 * Plugins can register themselves so the runtime can dispatch.
 * @type {Object<string, function>}
 */
var parseDocumentRegistry = {};

/**
 * Register a document parser for a given format.
 * @param {string} format - "pdf" or "docx"
 * @param {function} parserFn - synchronous parse function
 */
function registerParser(format, parserFn) {
  if (typeof format !== "string" || format.length === 0) {
    throw new Error("registerParser: format must be a non-empty string");
  }
  if (typeof parserFn !== "function") {
    throw new Error("registerParser: parserFn must be a function");
  }
  parseDocumentRegistry[format] = parserFn;
}

/**
 * Get a registered parser by format name.
 * @param {string} format
 * @returns {function|undefined}
 */
function getParser(format) {
  return parseDocumentRegistry[format];
}

// ── Little-endian byte readers (used by inflate for ZIP/PDF) ──────────────

/**
 * Read a little-endian 16-bit unsigned integer from a Uint8Array.
 * @param {Uint8Array} data
 * @param {number} offset
 * @returns {number}
 */
function readU16(data, offset) {
  return data[offset] | (data[offset + 1] << 8);
}

// ── DEFLATE (RFC 1951) inflate implementation ───────────────────────────

/**
 * Inflate (decompress) DEFLATE-compressed data.
 * Used for ZIP/PDF decompression.
 * @param {Uint8Array} compressed - raw DEFLATE stream (no zlib header)
 * @param {number} [expectedSize] - hint for output buffer size
 * @returns {Uint8Array}
 */
function inflate(compressed, expectedSize) {
  var bitBuf = 0;
  var bitCount = 0;
  var pos = 0;
  var output = [];
  var outputSize = expectedSize > 0 ? expectedSize : 65536;
  // Pre-allocate rough capacity
  // (we'll just push to array)

  function readBit() {
    if (bitCount === 0) {
      bitBuf = compressed[pos++];
      bitCount = 8;
    }
    var bit = bitBuf & 1;
    bitBuf >>>= 1;
    bitCount--;
    return bit;
  }

  function readBits(n) {
    var val = 0;
    for (var i = 0; i < n; i++) {
      val |= readBit() << i;
    }
    return val;
  }

  function readBitsMSB(n) {
    // For reading code lengths in dynamic Huffman: MSB first
    // Actually DEFLATE uses LSB first everywhere, including code lengths
    // So just use readBits
    return readBits(n);
  }

  /**
   * Build a Huffman tree from code lengths.
   * Returns a tree array where internal nodes are [left, right] and leaves are numbers.
   */
  function buildTree(codeLengths) {
    var maxBits = 0;
    for (var i = 0; i < codeLengths.length; i++) {
      if (codeLengths[i] > maxBits) maxBits = codeLengths[i];
    }
    if (maxBits === 0) return null;

    // Count codes per length
    var blCount = new Array(maxBits + 1);
    for (var i = 0; i <= maxBits; i++) blCount[i] = 0;
    for (var i = 0; i < codeLengths.length; i++) {
      if (codeLengths[i] > 0) blCount[codeLengths[i]]++;
    }

    // Compute starting codes
    var code = 0;
    var startCodes = new Array(maxBits + 1);
    for (var bits = 1; bits <= maxBits; bits++) {
      code = (code + blCount[bits - 1]) << 1;
      startCodes[bits] = code;
    }

    // Build tree: insert each symbol
    var tree = [null, null];
    for (var symbol = 0; symbol < codeLengths.length; symbol++) {
      var len = codeLengths[symbol];
      if (len === 0) continue;
      var symCode = startCodes[len];
      startCodes[len]++;

      var node = tree;
      for (var b = len - 1; b >= 0; b--) {
        var bit = (symCode >> b) & 1;
        if (b === 0) {
          if (node[bit] === null || node[bit] === undefined) {
            node[bit] = symbol;
          } else if (typeof node[bit] === 'number') {
            // Collision - shouldn't happen
          }
        } else {
          if (node[bit] === null || node[bit] === undefined) {
            node[bit] = [null, null];
          }
          node = node[bit];
        }
      }
    }
    return tree;
  }

  function decodeSymbol(tree) {
    var node = tree;
    while (typeof node !== 'number') {
      if (node === null) throw new Error("Invalid Huffman code");
      var bit = readBit();
      node = node[bit];
      if (node === null || node === undefined) throw new Error("Invalid Huffman code");
    }
    return node;
  }

  // Fixed Huffman code lengths
  function getFixedLitLengths() {
    var lengths = new Array(288);
    for (var i = 0; i <= 143; i++) lengths[i] = 8;
    for (var i = 144; i <= 255; i++) lengths[i] = 9;
    for (var i = 256; i <= 279; i++) lengths[i] = 7;
    for (var i = 280; i <= 287; i++) lengths[i] = 8;
    return lengths;
  }

  function getFixedDistLengths() {
    var lengths = new Array(30);
    for (var i = 0; i < 30; i++) lengths[i] = 5;
    return lengths;
  }

  // Length base values and extra bits (index 257-285)
  var lengthBase = [
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
    67, 83, 99, 115, 131, 163, 195, 227, 258,
  ];
  var lengthExtra = [
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4,
    5, 5, 5, 5, 0,
  ];

  // Distance base values and extra bits (index 0-29)
  var distBase = [
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513,
    769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
  ];
  var distExtra = [
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
    11, 11, 12, 12, 13, 13,
  ];

  // Code length alphabet order (for dynamic Huffman)
  var codeLenOrder = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];

  var isFinal = false;
  while (!isFinal) {
    isFinal = readBit() === 1;
    var blockType = readBits(2);

    if (blockType === 0) {
      // Stored block (no compression)
      // Skip to byte boundary
      bitCount = 0;
      bitBuf = 0;
      // Read length and 1's complement
      var storedLen = readU16(compressed, pos);
      pos += 2;
      // Skip nlen (1's complement)
      pos += 2;
      for (var si = 0; si < storedLen && pos < compressed.length; si++) {
        output.push(compressed[pos++]);
      }
    } else if (blockType === 1) {
      // Fixed Huffman codes
      var litTree = buildTree(getFixedLitLengths());
      var distTree = buildTree(getFixedDistLengths());

      while (true) {
        var sym = decodeSymbol(litTree);
        if (sym < 256) {
          output.push(sym);
        } else if (sym === 256) {
          break; // End of block
        } else {
          // Length code (257-285)
          var lenIdx = sym - 257;
          var length = lengthBase[lenIdx];
          if (lengthExtra[lenIdx] > 0) {
            length += readBits(lengthExtra[lenIdx]);
          }

          var distSym = decodeSymbol(distTree);
          var distance = distBase[distSym];
          if (distExtra[distSym] > 0) {
            distance += readBits(distExtra[distSym]);
          }

          // Copy from output
          var outLen = output.length;
          for (var ci = 0; ci < length; ci++) {
            output.push(output[outLen - distance + ci]);
          }
        }
      }
    } else if (blockType === 2) {
      // Dynamic Huffman codes
      var hlit = readBits(5) + 257; // number of literal/length codes
      var hdist = readBits(5) + 1;  // number of distance codes
      var hclen = readBits(4) + 4;  // number of code length codes

      // Read code length code lengths
      var clLengths = new Array(19);
      for (var i = 0; i < 19; i++) clLengths[i] = 0;
      for (var i = 0; i < hclen; i++) {
        clLengths[codeLenOrder[i]] = readBits(3);
      }

      var clTree = buildTree(clLengths);

      // Read literal/length and distance code lengths
      var allLengths = new Array(hlit + hdist);
      var allIdx = 0;
      while (allIdx < hlit + hdist) {
        var clSym = decodeSymbol(clTree);
        if (clSym < 16) {
          allLengths[allIdx++] = clSym;
        } else if (clSym === 16) {
          // Repeat previous length 3-6 times
          var repeat = readBits(2) + 3;
          var prev = allIdx > 0 ? allLengths[allIdx - 1] : 0;
          for (var ri = 0; ri < repeat; ri++) {
            allLengths[allIdx++] = prev;
          }
        } else if (clSym === 17) {
          // Repeat 0 for 3-10 times
          var repeat = readBits(3) + 3;
          for (var ri = 0; ri < repeat; ri++) {
            allLengths[allIdx++] = 0;
          }
        } else if (clSym === 18) {
          // Repeat 0 for 11-138 times
          var repeat = readBits(7) + 11;
          for (var ri = 0; ri < repeat; ri++) {
            allLengths[allIdx++] = 0;
          }
        }
      }

      var litLengths = new Array(hlit);
      for (var i = 0; i < hlit; i++) litLengths[i] = allLengths[i];
      var distLengths = new Array(hdist);
      for (var i = 0; i < hdist; i++) distLengths[i] = allLengths[hlit + i];

      var dynLitTree = buildTree(litLengths);
      var dynDistTree = buildTree(distLengths);

      while (true) {
        var sym = decodeSymbol(dynLitTree);
        if (sym < 256) {
          output.push(sym);
        } else if (sym === 256) {
          break;
        } else {
          var lenIdx = sym - 257;
          var length = lengthBase[lenIdx];
          if (lengthExtra[lenIdx] > 0) {
            length += readBits(lengthExtra[lenIdx]);
          }

          var distSym = decodeSymbol(dynDistTree);
          var distance = distBase[distSym];
          if (distExtra[distSym] > 0) {
            distance += readBits(distExtra[distSym]);
          }

          var outLen = output.length;
          for (var ci = 0; ci < length; ci++) {
            output.push(output[outLen - distance + ci]);
          }
        }
      }
    }
  }

  return new Uint8Array(output);
}