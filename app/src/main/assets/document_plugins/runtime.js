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