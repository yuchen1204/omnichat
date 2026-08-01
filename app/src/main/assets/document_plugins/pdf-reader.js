/**
 * pdf-reader.js — Synchronous PDF document parser.
 *
 * Pure JavaScript implementation for QuickJS runtime. No Promise, async/await,
 * Worker, DOM, Canvas, node:, require, process, or Buffer.
 *
 * Parses traditional (non-stream) xref tables, traverses the Pages tree,
 * extracts text from content streams (BT...ET blocks), tracks text coordinates
 * for reading order, and detects simple tables from coordinate clusters.
 *
 * Depends on: runtime.js (result, registerParser, utf8ToString, safeArrayPush)
 */

"use strict";

// ── PDF tokenizer ──────────────────────────────────────────────────────────

/**
 * Create a PDF tokenizer that works on a byte array treated as Latin-1 string.
 * @param {string} src - the PDF source as a string (byte values 0-255)
 * @returns {object} tokenizer with nextToken(), peekToken(), skipPast()
 */
function createTokenizer(src) {
  var pos = 0;
  var len = src.length;
  // Multi-token peek buffer: peekToken() fills this; nextToken() drains it.
  var peeked = [];

  function isWhitespace(ch) {
    return ch === " " || ch === "\n" || ch === "\r" || ch === "\t" || ch === "\f" || ch === "\0";
  }

  function isDelimiter(ch) {
    return ch === "(" || ch === ")" || ch === "<" || ch === ">" ||
           ch === "[" || ch === "]" || ch === "{" || ch === "}" ||
           ch === "/" || ch === "%";
  }

  function skipWhitespace() {
    while (pos < len && isWhitespace(src[pos])) {
      if (src[pos] === "\r" && pos + 1 < len && src[pos + 1] === "\n") pos++;
      pos++;
    }
  }

  function skipComment() {
    // % ... until end of line
    while (pos < len && src[pos] !== "\n" && src[pos] !== "\r") pos++;
  }

  function readString() {
    // Parenthesized string ( ... ) with escapes
    pos++; // skip (
    var depth = 1;
    var result = "";
    while (pos < len && depth > 0) {
      var ch = src[pos];
      if (ch === "\\") {
        pos++;
        if (pos >= len) break;
        var esc = src[pos];
        switch (esc) {
          case "n": result += "\n"; break;
          case "r": result += "\r"; break;
          case "t": result += "\t"; break;
          case "b": result += "\b"; break;
          case "f": result += "\f"; break;
          case "(": result += "("; break;
          case ")": result += ")"; break;
          case "\\": result += "\\"; break;
          case "\n": break; // line continuation
          case "\r": if (pos + 1 < len && src[pos + 1] === "\n") pos++; break;
          default:
            // Octal escape \ddd
            if (esc >= "0" && esc <= "9") {
              var octal = esc;
              if (pos + 1 < len && src[pos + 1] >= "0" && src[pos + 1] <= "7") {
                pos++; octal += src[pos];
              }
              if (pos + 1 < len && src[pos + 1] >= "0" && src[pos + 1] <= "7") {
                pos++; octal += src[pos];
              }
              result += String.fromCharCode(parseInt(octal, 8));
            } else {
              result += esc;
            }
            break;
        }
        pos++;
      } else if (ch === "(") {
        depth++;
        result += ch;
        pos++;
      } else if (ch === ")") {
        depth--;
        if (depth > 0) result += ch;
        pos++;
      } else {
        result += ch;
        pos++;
      }
    }
    return result;
  }

  function readHexString() {
    // <hex>
    pos++; // skip <
    var result = "";
    while (pos < len && src[pos] !== ">") {
      var ch = src[pos];
      if (isWhitespace(ch)) { pos++; continue; }
      var hex = ch;
      pos++;
      if (pos < len && src[pos] !== ">" && !isWhitespace(src[pos])) {
        hex += src[pos];
        pos++;
      } else {
        hex += "0";
      }
      result += String.fromCharCode(parseInt(hex, 16));
    }
    if (pos < len) pos++; // skip >
    return result;
  }

  function readName() {
    // /Name - the / is already consumed by tokenizer
    var result = "";
    // Names can have #XX escape sequences
    while (pos < len && !isWhitespace(src[pos]) && !isDelimiter(src[pos])) {
      if (src[pos] === "#" && pos + 2 < len) {
        var hex = src.substring(pos + 1, pos + 3);
        if (/^[0-9a-fA-F]{2}$/.test(hex)) {
          result += String.fromCharCode(parseInt(hex, 16));
          pos += 3;
          continue;
        }
      }
      result += src[pos];
      pos++;
    }
    return result;
  }

  function readNumber() {
    var start = pos;
    if (src[pos] === "+" || src[pos] === "-") pos++;
    while (pos < len && src[pos] >= "0" && src[pos] <= "9") pos++;
    if (pos < len && src[pos] === ".") {
      pos++;
      while (pos < len && src[pos] >= "0" && src[pos] <= "9") pos++;
    }
    return src.substring(start, pos);
  }

  function readRegularToken() {
    var start = pos;
    while (pos < len && !isWhitespace(src[pos]) && !isDelimiter(src[pos])) {
      pos++;
    }
    return src.substring(start, pos);
  }

  function readToken() {
    skipWhitespace();
    if (pos >= len) return null;

    var ch = src[pos];

    // Comments
    if (ch === "%") {
      skipComment();
      return readToken();
    }

    // String literals
    if (ch === "(") {
      return { type: "string", value: readString() };
    }

    // Hex strings
    if (ch === "<" && pos + 1 < len && src[pos + 1] !== "<") {
      return { type: "string", value: readHexString() };
    }

    // Dict start
    if (ch === "<" && pos + 1 < len && src[pos + 1] === "<") {
      pos += 2;
      return { type: "dictStart", value: "<<" };
    }

    // Dict end
    if (ch === ">" && pos + 1 < len && src[pos + 1] === ">") {
      pos += 2;
      return { type: "dictEnd", value: ">>" };
    }

    // Array start
    if (ch === "[") {
      pos++;
      return { type: "arrayStart", value: "[" };
    }

    // Array end
    if (ch === "]") {
      pos++;
      return { type: "arrayEnd", value: "]" };
    }

    // Names
    if (ch === "/") {
      pos++;
      return { type: "name", value: readName() };
    }

    // Numbers
    if (ch === "+" || ch === "-" || (ch >= "0" && ch <= "9") || ch === ".") {
      var numStr = readNumber();
      if (numStr.indexOf(".") >= 0) {
        return { type: "real", value: parseFloat(numStr) };
      }
      return { type: "integer", value: parseInt(numStr, 10) };
    }

    // Keywords and other tokens
    var token = readRegularToken();
    if (token === "true") return { type: "boolean", value: true };
    if (token === "false") return { type: "boolean", value: false };
    if (token === "null") return { type: "null", value: null };
    if (token === "R") return { type: "ref", value: "R" };
    if (token === "obj") return { type: "obj", value: "obj" };
    if (token === "endobj") return { type: "endobj", value: "endobj" };
    if (token === "stream") return { type: "stream", value: "stream" };
    if (token === "endstream") return { type: "endstream", value: "endstream" };
    if (token === "xref") return { type: "xref", value: "xref" };
    if (token === "trailer") return { type: "trailer", value: "trailer" };
    if (token === "startxref") return { type: "startxref", value: "startxref" };
    if (token === "BT") return { type: "operator", value: "BT" };
    if (token === "ET") return { type: "operator", value: "ET" };
    if (token === "Tj") return { type: "operator", value: "Tj" };
    if (token === "TJ") return { type: "operator", value: "TJ" };
    if (token === "'") return { type: "operator", value: "'" };
    if (token === '"') return { type: "operator", value: '"' };
    if (token === "Tm") return { type: "operator", value: "Tm" };
    if (token === "Td") return { type: "operator", value: "Td" };
    if (token === "TD") return { type: "operator", value: "TD" };
    if (token === "T*") return { type: "operator", value: "T*" };
    if (token === "Tf") return { type: "operator", value: "Tf" };
    if (token === "TL") return { type: "operator", value: "TL" };
    if (token === "Ts") return { type: "operator", value: "Ts" };
    if (token === "Tc") return { type: "operator", value: "Tc" };
    if (token === "Tw") return { type: "operator", value: "Tw" };
    if (token === "Tz") return { type: "operator", value: "Tz" };
    if (token === "cm") return { type: "operator", value: "cm" };
    if (token === "Do") return { type: "operator", value: "Do" };
    if (token === "q") return { type: "operator", value: "q" };
    if (token === "Q") return { type: "operator", value: "Q" };
    if (token === "gs") return { type: "operator", value: "gs" };
    if (token === "re") return { type: "operator", value: "re" };
    if (token === "f") return { type: "operator", value: "f" };
    if (token === "S") return { type: "operator", value: "S" };
    if (token === "s") return { type: "operator", value: "s" };
    if (token === "n") return { type: "operator", value: "n" };
    if (token === "W") return { type: "operator", value: "W" };
    if (token === "w") return { type: "operator", value: "w" };
    if (token === "J") return { type: "operator", value: "J" };
    if (token === "j") return { type: "operator", value: "j" };
    if (token === "M") return { type: "operator", value: "M" };
    if (token === "d") return { type: "operator", value: "d" };
    if (token === "ri") return { type: "operator", value: "ri" };
    if (token === "i") return { type: "operator", value: "i" };
    if (token === "RG") return { type: "operator", value: "RG" };
    if (token === "rg") return { type: "operator", value: "rg" };
    if (token === "K") return { type: "operator", value: "K" };
    if (token === "k") return { type: "operator", value: "k" };
    if (token === "CS") return { type: "operator", value: "CS" };
    if (token === "cs") return { type: "operator", value: "cs" };
    if (token === "SC") return { type: "operator", value: "SC" };
    if (token === "sc") return { type: "operator", value: "sc" };
    if (token === "SCN") return { type: "operator", value: "SCN" };
    if (token === "scn") return { type: "operator", value: "scn" };
    if (token === "BI") return { type: "operator", value: "BI" };
    if (token === "ID") return { type: "operator", value: "ID" };
    if (token === "EI") return { type: "operator", value: "EI" };
    if (token === "BX") return { type: "operator", value: "BX" };
    if (token === "EX") return { type: "operator", value: "EX" };
    if (token === "MP") return { type: "operator", value: "MP" };
    if (token === "DP") return { type: "operator", value: "DP" };
    if (token === "BMC") return { type: "operator", value: "BMC" };
    if (token === "BDC") return { type: "operator", value: "BDC" };
    if (token === "EMC") return { type: "operator", value: "EMC" };
    if (token === "d0") return { type: "operator", value: "d0" };
    if (token === "d1") return { type: "operator", value: "d1" };
    if (token === "sh") return { type: "operator", value: "sh" };
    if (token === "Do") return { type: "operator", value: "Do" };
    if (token === "INLINE") return { type: "operator", value: "INLINE" };
    if (token === "ENDINLINE") return { type: "operator", value: "ENDINLINE" };

    return { type: "keyword", value: token };
  }

  function nextToken() {
    if (peeked.length > 0) {
      return peeked.shift();
    }
    return readToken();
  }

  function peekToken(offset) {
    var index = offset || 0;
    while (peeked.length <= index) {
      var tok = readToken();
      if (tok === null) break;
      peeked.push(tok);
    }
    return peeked.length > index ? peeked[index] : null;
  }

  /**
   * Skip past the given string marker.
   */
  function skipPast(marker) {
    var idx = src.indexOf(marker, pos);
    if (idx >= 0) {
      pos = idx + marker.length;
    }
    return idx >= 0;
  }

  function getPos() { return pos; }
  function setPos(p) { pos = p; }

  return {
    nextToken: nextToken,
    peekToken: peekToken,
    skipPast: skipPast,
    getPos: getPos,
    setPos: setPos,
    getSrc: function() { return src; },
    getRemaining: function() { return src.substring(pos); }
  };
}

// ── PDF value parser ───────────────────────────────────────────────────────

/**
 * Parse a complete PDF value from the tokenizer.
 * Returns the parsed value (object, array, string, number, boolean, null, or ref).
 */
function parseValue(tok) {
  var token = tok.peekToken();
  if (!token) return null;

  switch (token.type) {
    case "dictStart":
      return parseDict(tok);
    case "arrayStart":
      return parseArray(tok);
    case "string":
      tok.nextToken();
      return token.value;
    case "integer":
    case "real":
      // Check if followed by "integer R" (indirect reference) without consuming
      // either lookahead token until the reference is confirmed.
      var next = tok.peekToken(1);
      var maybeR = tok.peekToken(2);
      if (token.type === "integer" && next && maybeR && next.type === "integer" && maybeR.type === "ref") {
        tok.nextToken(); // consume object number
        tok.nextToken(); // consume generation number
        tok.nextToken(); // consume 'R'
        return { type: "reference", objNum: token.value, genNum: next.value };
      }
      tok.nextToken();
      return { type: "number", value: token.value };
    case "boolean":
      tok.nextToken();
      return { type: "boolean", value: token.value };
    case "null":
      tok.nextToken();
      return null;
    case "ref":
      // Bare 'R' token - shouldn't happen normally
      tok.nextToken();
      return null;
    case "name":
      tok.nextToken();
      return { type: "name", value: token.value };
    case "keyword":
      tok.nextToken();
      return { type: "keyword", value: token.value };
    default:
      tok.nextToken();
      return { type: "keyword", value: token.value };
  }
}

/**
 * Parse a PDF dictionary: << /Key Value ... >>
 */
function parseDict(tok) {
  tok.nextToken(); // consume <<
  var dict = {};
  while (true) {
    var token = tok.peekToken();
    if (!token || token.type === "dictEnd") {
      if (token) tok.nextToken();
      break;
    }
    if (token.type !== "name") {
      // Unexpected token, skip it
      tok.nextToken();
      continue;
    }
    tok.nextToken(); // consume name
    var key = token.value;
    var value = parseValue(tok);
    dict[key] = value;
  }
  return { type: "dictionary", dict: dict };
}

/**
 * Parse a PDF array: [ value1 value2 ... ]
 */
function parseArray(tok) {
  tok.nextToken(); // consume [
  var arr = [];
  while (true) {
    var token = tok.peekToken();
    if (!token || token.type === "arrayEnd") {
      if (token) tok.nextToken();
      break;
    }
    var value = parseValue(tok);
    arr.push(value);
  }
  return { type: "array", items: arr };
}

/**
 * Resolve a reference by looking up the object number.
 * @param {object} ref - { type: "reference", objNum, genNum }
 * @param {object} objMap - map of objNum -> { objNum, genNum, value }
 * @returns {*} the resolved value
 */
function resolveRef(ref, objMap) {
  if (!ref || ref.type !== "reference") return ref;
  var obj = objMap[ref.objNum];
  if (!obj) return ref;
  return obj.value;
}

/**
 * Resolve a value, following references, with cycle detection.
 * Uses a resolvedRefs set to prevent infinite recursion from circular references
 * (e.g. Page -> /Parent -> Pages -> /Kids -> Page).
 */
function resolveValue(val, objMap, resolvedRefs) {
  if (resolvedRefs === undefined) resolvedRefs = {};
  if (val && val.type === "reference") {
    var key = val.objNum + ":" + val.genNum;
    if (resolvedRefs[key]) return { type: "circular", ref: key };
    resolvedRefs[key] = true;
    var resolved = resolveRef(val, objMap);
    return resolveValue(resolved, objMap, resolvedRefs);
  }
  if (val && val.type === "dictionary") {
    var resolved = {};
    for (var k in val.dict) {
      if (val.dict.hasOwnProperty(k)) {
        resolved[k] = resolveValue(val.dict[k], objMap, resolvedRefs);
      }
    }
    return resolved;
  }
  if (val && val.type === "array") {
    return val.items.map(function(item) { return resolveValue(item, objMap, resolvedRefs); });
  }
  return val;
}

// ── PDFDocEncoding / WinAnsiEncoding ───────────────────────────────────────

/**
 * Decode a PDF string using PDFDocEncoding (which is almost identical to
 * Latin-1 / ISO-8859-1, with a few differences in the 0x80-0x9F range).
 * QuickJS doesn't have TextDecoder, so we map byte-by-byte.
 */
function pdfDocDecode(str) {
  // PDFDocEncoding is close to Latin-1. The differences are in the
  // 0x80-0x9F range. For simplicity, we map common printable characters.
  // Most PDFs use either PDFDocEncoding or WinAnsiEncoding, which are
  // both essentially Latin-1 for the printable range.
  // For CJK text, fonts use CID/mapping but we don't support that.
  var result = "";
  for (var i = 0; i < str.length; i++) {
    var code = str.charCodeAt(i);
    if (code >= 0x20 && code <= 0x7E) {
      result += str[i];
    } else if (code >= 0xA0) {
      // Latin-1 range (Euro sign etc. at 0x80)
      result += str[i];
    } else if (code === 0x09 || code === 0x0A || code === 0x0D) {
      result += str[i];
    } else {
      // Control character or undefined - skip or replace with space
      result += " ";
    }
  }
  return result;
}

// ── Content stream parser ──────────────────────────────────────────────────

/**
 * Parse a PDF content stream and extract text items with positions.
 * @param {string} stream - the content stream as a string
 * @returns {Array<{text:string, x:number, y:number}>}
 */
function parseContentStream(stream) {
  var tok = createTokenizer(stream);
  var textItems = [];
  var inText = false;
  var hasTextOps = false;

  // Text state
  var tm = [1, 0, 0, 1, 0, 0]; // text matrix (a, b, c, d, e, f)
  var tlm = [1, 0, 0, 1, 0, 0]; // text line matrix
  var fontSize = 12;
  var leading = 0;

  // Stack of operands
  var operands = [];

  function getNumber(val) {
    if (val && val.type === "number") return val.value;
    return 0;
  }

  function processTextString(text) {
    hasTextOps = true;
    var decoded = pdfDocDecode(text);
    var x = tm[4];
    var y = tm[5];
    textItems.push({
      text: decoded,
      x: x,
      y: y,
      fontSize: fontSize
    });
  }

  while (true) {
    var token = tok.nextToken();
    if (!token) break;

    if (token.type === "operator") {
      var op = token.value;

      switch (op) {
        case "BT":
          inText = true;
          tm = [1, 0, 0, 1, 0, 0];
          tlm = [1, 0, 0, 1, 0, 0];
          operands = [];
          break;

        case "ET":
          inText = false;
          operands = [];
          break;

        case "Tj":
          // (text) Tj
          if (operands.length >= 1) {
            var str = operands[operands.length - 1];
            if (typeof str === "string") {
              processTextString(str);
            }
          }
          operands = [];
          break;

        case "TJ":
          // [(text) num (text) ...] TJ
          if (operands.length >= 1) {
            var arr = operands[operands.length - 1];
            if (Array.isArray(arr)) {
              var accumulated = "";
              var currentX = tm[4];
              for (var ai = 0; ai < arr.length; ai++) {
                var elem = arr[ai];
                if (typeof elem === "string") {
                  accumulated += elem;
                  currentX += estimateStringWidth(elem, fontSize);
                } else if (typeof elem === "number") {
                  // Negative number adjusts position (kerning)
                  currentX -= elem / 1000 * fontSize;
                }
              }
              if (accumulated.length > 0) {
                processTextString(accumulated);
              }
            }
          }
          operands = [];
          break;

        case "'":
          // (text) ' - move to next line and show text
          // Equivalent to T* followed by (text) Tj
          tm[4] = tlm[4];
          tm[5] = tlm[5] - leading;
          tlm[4] = tm[4];
          tlm[5] = tm[5];
          if (operands.length >= 1) {
            var str2 = operands[operands.length - 1];
            if (typeof str2 === "string") {
              processTextString(str2);
            }
          }
          operands = [];
          break;

        case '"':
          // w c (text) " - set word/char spacing, move to next line, show text
          if (operands.length >= 3) {
            // operands[0] = wordSpacing, operands[1] = charSpacing, operands[2] = text
            tm[4] = tlm[4];
            tm[5] = tlm[5] - leading;
            tlm[4] = tm[4];
            tlm[5] = tm[5];
            var str3 = operands[operands.length - 1];
            if (typeof str3 === "string") {
              processTextString(str3);
            }
          }
          operands = [];
          break;

        case "Td":
          // tx ty Td
          if (operands.length >= 2) {
            var tx = getNumber(operands[operands.length - 2]);
            var ty = getNumber(operands[operands.length - 1]);
            tm[4] = tlm[4] + tx;
            tm[5] = tlm[5] + ty;
            tlm[4] = tm[4];
            tlm[5] = tm[5];
          }
          operands = [];
          break;

        case "TD":
          // tx ty TD - move and set leading
          if (operands.length >= 2) {
            var tx2 = getNumber(operands[operands.length - 2]);
            var ty2 = getNumber(operands[operands.length - 1]);
            leading = -ty2;
            tm[4] = tlm[4] + tx2;
            tm[5] = tlm[5] + ty2;
            tlm[4] = tm[4];
            tlm[5] = tm[5];
          }
          operands = [];
          break;

        case "T*":
          // Move to start of next line
          tm[4] = tlm[4];
          tm[5] = tlm[5] - leading;
          tlm[4] = tm[4];
          tlm[5] = tm[5];
          operands = [];
          break;

        case "Tm":
          // a b c d e f Tm
          if (operands.length >= 6) {
            tm = [
              getNumber(operands[0]), getNumber(operands[1]),
              getNumber(operands[2]), getNumber(operands[3]),
              getNumber(operands[4]), getNumber(operands[5])
            ];
            tlm = tm.slice();
          }
          operands = [];
          break;

        case "Tf":
          // fontname size Tf
          if (operands.length >= 2) {
            fontSize = getNumber(operands[operands.length - 1]);
          }
          operands = [];
          break;

        case "TL":
          if (operands.length >= 1) {
            leading = getNumber(operands[operands.length - 1]);
          }
          operands = [];
          break;

        case "Tc":
        case "Tw":
        case "Tz":
        case "Ts":
          // Text state operators - consume operands
          operands = [];
          break;

        case "cm":
        case "q":
        case "Q":
        case "gs":
        case "re":
        case "f":
        case "S":
        case "s":
        case "n":
        case "W":
        case "w":
        case "J":
        case "j":
        case "M":
        case "d":
        case "ri":
        case "i":
        case "RG":
        case "rg":
        case "K":
        case "k":
        case "CS":
        case "cs":
        case "SC":
        case "sc":
        case "SCN":
        case "scn":
        case "Do":
        case "BI":
        case "ID":
        case "EI":
        case "BX":
        case "EX":
        case "MP":
        case "DP":
        case "BMC":
        case "BDC":
        case "EMC":
        case "d0":
        case "d1":
        case "sh":
          // Graphics/state operators - consume operands
          operands = [];
          break;

        default:
          // Unknown operator - consume operands
          operands = [];
          break;
      }
    } else if (token.type === "string") {
      // Push string as operand
      operands.push(token.value);
    } else if (token.type === "integer" || token.type === "real") {
      operands.push({ type: "number", value: token.value });
    } else if (token.type === "number") {
      operands.push(token);
    } else if (token.type === "arrayStart") {
      // Parse array inline for TJ operator
      var arr = [];
      var depth = 1;
      while (depth > 0) {
        var t = tok.nextToken();
        if (!t) break;
        if (t.type === "arrayStart") { depth++; continue; }
        if (t.type === "arrayEnd") { depth--; continue; }
        if (t.type === "string") { arr.push(t.value); }
        else if (t.type === "integer" || t.type === "real") { arr.push(t.value); }
        else if (t.type === "number") { arr.push(t.value); }
      }
      operands.push(arr);
    } else {
      // Skip other tokens
    }
  }

  return { items: textItems, hasTextOps: hasTextOps };
}

/**
 * Estimate the width of a string in PDF text space units.
 * Rough approximation using average character width.
 */
function estimateStringWidth(str, fontSize) {
  // Average character width is roughly 0.5 * fontSize for typical fonts
  return str.length * fontSize * 0.5;
}

// ── PDF document parser ────────────────────────────────────────────────────

/**
 * Parse a PDF file and extract text content.
 * @param {Uint8Array} bytes
 * @param {string[]} warnings
 * @returns {string}
 */
function parsePdf(bytes, warnings) {
  // Convert bytes to Latin-1 string for parsing
  var src = "";
  for (var i = 0; i < bytes.length; i++) {
    src += String.fromCharCode(bytes[i]);
  }

  // Verify PDF header
  if (src.substring(0, 5) !== "%PDF-") {
    throw new Error("Not a valid PDF file: missing PDF header");
  }

  // Find startxref from the end of the file
  var startxrefPos = src.lastIndexOf("startxref");
  if (startxrefPos < 0) {
    throw new Error("Not a valid PDF file: missing startxref");
  }

  // Parse the xref offset
  var afterStartxref = src.substring(startxrefPos + 9);
  var xrefOffsetStr = "";
  for (var si = 0; si < afterStartxref.length; si++) {
    var ch = afterStartxref[si];
    if (ch >= "0" && ch <= "9") {
      xrefOffsetStr += ch;
    } else if (xrefOffsetStr.length > 0) {
      break;
    }
  }
  var xrefOffset = parseInt(xrefOffsetStr, 10);
  if (isNaN(xrefOffset) || xrefOffset < 0) {
    throw new Error("Invalid xref offset");
  }

  // Parse the xref table and objects
  var objMap = {};
  parseXrefAndObjects(src, xrefOffset, objMap, warnings);

  // Find the trailer /Root
  var root = findRoot(objMap, warnings);
  if (!root) {
    throw new Error("Could not find /Root in PDF trailer");
  }

  // Get raw catalog object
  var catalogRaw = null;
  if (root.type === "reference") {
    var catalogObj = objMap[root.objNum];
    if (catalogObj && catalogObj.value && catalogObj.value.type === "dictionary") {
      catalogRaw = catalogObj.value;
    }
  }
  if (!catalogRaw) {
    throw new Error("Could not resolve PDF catalog");
  }

  var pagesRef = catalogRaw.dict.Pages;
  if (!pagesRef) {
    throw new Error("Could not find /Pages in PDF catalog");
  }

  // Collect all pages
  var pageRefs = [];
  collectPages(pagesRef, objMap, pageRefs, warnings);

  if (pageRefs.length === 0) {
    throw new Error("No pages found in PDF");
  }

  // Extract text from each page
  var allText = "";
  var totalTextItems = 0;
  var maxItems = 10000; // safety limit

  for (var pi = 0; pi < pageRefs.length; pi++) {
    if (totalTextItems >= maxItems) {
      safeArrayPush(warnings, "Reached text item limit; truncating output", 50);
      break;
    }

    var pageRef = pageRefs[pi];
    // Get raw page dictionary value (not resolved, to preserve references)
    var pageRaw = null;
    if (pageRef && pageRef.type === "reference") {
      var pageObj = objMap[pageRef.objNum];
      if (pageObj && pageObj.value) {
        pageRaw = pageObj.value;
      }
    }
    if (!pageRaw || pageRaw.type !== "dictionary") {
      safeArrayPush(warnings, "Page " + (pi + 1) + " could not be loaded", 50);
      continue;
    }

    // Add page marker
    if (allText.length > 0) allText += "\n";
    allText += "--- Page " + (pi + 1) + " ---\n";

    // Get the Contents reference(s) from the raw dictionary
    var contentsRef = pageRaw.dict.Contents;
    if (!contentsRef) {
      safeArrayPush(warnings, "Page " + (pi + 1) + " has no content stream", 50);
      continue;
    }

    // Get the content stream(s)
    var contentStreams = [];
    if (contentsRef.type === "array") {
      for (var ci = 0; ci < contentsRef.items.length; ci++) {
        var streamData = getStreamData(contentsRef.items[ci], objMap, src, warnings);
        if (streamData) contentStreams.push(streamData);
      }
    } else {
      var streamData = getStreamData(contentsRef, objMap, src, warnings);
      if (streamData) contentStreams.push(streamData);
    }

    if (contentStreams.length === 0) {
      safeArrayPush(warnings, "Page " + (pi + 1) + " has no extractable content stream", 50);
      continue;
    }

    // Parse content streams
    var pageTextItems = [];
    var hasText = false;
    for (var ci = 0; ci < contentStreams.length; ci++) {
      var result = parseContentStream(contentStreams[ci]);
      if (result.hasTextOps) hasText = true;
      for (var ti = 0; ti < result.items.length; ti++) {
        if (pageTextItems.length < maxItems) {
          pageTextItems.push(result.items[ti]);
        }
      }
    }

    if (!hasText) {
      safeArrayPush(warnings, "Page " + (pi + 1) + " appears to be image-only", 50);
      continue;
    }

    if (pageTextItems.length === 0) {
      continue;
    }

    totalTextItems += pageTextItems.length;

    // Sort text items by position (top-to-bottom, left-to-right)
    // PDF y-coordinates increase upward; we want top-to-bottom, so reverse y sort
    pageTextItems.sort(function(a, b) {
      var yDiff = b.y - a.y;
      if (Math.abs(yDiff) > 5) return yDiff; // different line
      return a.x - b.x; // same line, left to right
    });

    // Group into lines based on y-coordinate tolerance
    var lines = [];
    var currentLine = null;
    var yTolerance = 8;

    for (var ti = 0; ti < pageTextItems.length; ti++) {
      var item = pageTextItems[ti];
      if (!currentLine || Math.abs(item.y - currentLine.y) > yTolerance) {
        currentLine = { y: item.y, items: [item], xMin: item.x, xMax: item.x + estimateStringWidth(item.text, item.fontSize) };
        lines.push(currentLine);
      } else {
        currentLine.items.push(item);
        var itemEnd = item.x + estimateStringWidth(item.text, item.fontSize);
        if (item.x < currentLine.xMin) currentLine.xMin = item.x;
        if (itemEnd > currentLine.xMax) currentLine.xMax = itemEnd;
      }
    }

    // Detect table structure from coordinate clusters
    var tableStr = tryDetectTable(lines, warnings);
    if (tableStr) {
      allText += tableStr;
    } else {
      // Output as plain text lines
      for (var li = 0; li < lines.length; li++) {
        var line = lines[li];
        // Concatenate items in x-order (already sorted)
        var lineText = "";
        for (var ti2 = 0; ti2 < line.items.length; ti2++) {
          lineText += line.items[ti2].text;
        }
        // Trim whitespace
        lineText = lineText.trim();
        if (lineText.length > 0) {
          allText += lineText + "\n";
        }
      }
    }

    // Trim trailing newline
    if (allText.length > 0 && allText[allText.length - 1] === "\n") {
      allText = allText.substring(0, allText.length - 1);
    }
  }

  return allText;
}

/**
 * Try to detect and format a table from the text lines with coordinates.
 * Returns a Markdown table string if detected, or null.
 */
function tryDetectTable(lines, warnings) {
  if (lines.length < 3) return null; // need at least header + separator + data

  // Collect all x-coordinate boundaries across all lines
  // For each line, we look at the x positions of its items
  var allXPositions = {};
  for (var li = 0; li < lines.length; li++) {
    var line = lines[li];
    for (var ii = 0; ii < line.items.length; ii++) {
      var x = Math.round(line.items[ii].x);
      allXPositions[x] = (allXPositions[x] || 0) + 1;
    }
  }

  // Find stable x positions (appear in multiple lines)
  var stableXs = [];
  for (var xStr in allXPositions) {
    if (allXPositions.hasOwnProperty(xStr)) {
      var x = parseInt(xStr, 10);
      if (allXPositions[xStr] >= Math.min(3, lines.length)) {
        stableXs.push(x);
      }
    }
  }
  stableXs.sort(function(a, b) { return a - b; });

  // Need at least 2 stable columns (3 x positions: start, separator, separator)
  if (stableXs.length < 2) return null;

  // Group lines into rows based on these x positions
  // For each line, assign each item to the nearest stable x position
  var rows = [];
  for (var li = 0; li < lines.length; li++) {
    var line = lines[li];
    var row = new Array(stableXs.length);
    for (var ii = 0; ii < row.length; ii++) row[ii] = "";

    for (var ii = 0; ii < line.items.length; ii++) {
      var item = line.items[ii];
      // Find nearest stable x
      var nearest = 0;
      var minDist = Math.abs(item.x - stableXs[0]);
      for (var si = 1; si < stableXs.length; si++) {
        var dist = Math.abs(item.x - stableXs[si]);
        if (dist < minDist) {
          minDist = dist;
          nearest = si;
        }
      }
      row[nearest] += item.text;
    }

    // Trim each cell
    for (var ci = 0; ci < row.length; ci++) {
      row[ci] = row[ci].trim();
    }
    rows.push(row);
  }

  // Check if we have a reasonable table shape
  if (rows.length < 2) return null;

  // Count non-empty cells per column
  var nonEmpty = new Array(stableXs.length);
  for (var ci = 0; ci < nonEmpty.length; ci++) nonEmpty[ci] = 0;
  for (var ri = 0; ri < rows.length; ri++) {
    for (var ci = 0; ci < rows[ri].length; ci++) {
      if (rows[ri][ci].length > 0) nonEmpty[ci]++;
    }
  }

  // At least 2 columns should have decent content
  var filledColumns = 0;
  for (var ci = 0; ci < nonEmpty.length; ci++) {
    if (nonEmpty[ci] >= rows.length * 0.5) filledColumns++;
  }
  if (filledColumns < 2) return null;

  // Build Markdown table
  var md = "";
  // Header row
  md += "| " + rows[0].join(" | ") + " |\n";
  // Separator
  md += "| " + rows[0].map(function() { return "---"; }).join(" | ") + " |\n";
  // Data rows
  for (var ri = 1; ri < rows.length; ri++) {
    md += "| " + rows[ri].join(" | ") + " |\n";
  }

  safeArrayPush(warnings, "Table detected from coordinate analysis", 50);
  return md;
}

/**
 * Parse the xref table and all indirect objects.
 */
function parseXrefAndObjects(src, xrefOffset, objMap, warnings) {
  var pos = xrefOffset;
  var tok = createTokenizer(src);
  tok.setPos(pos);

  // Expect "xref"
  var token = tok.nextToken();
  if (!token || token.type !== "xref") {
    // Try alternate: some files have whitespace before xref
    // Actually, the tokenizer skips whitespace, so this should work
    // If we didn't find xref, try to find it manually
    var xrefIdx = src.indexOf("xref", pos);
    if (xrefIdx < 0) {
      throw new Error("Could not find xref table at offset " + xrefOffset);
    }
    tok.setPos(xrefIdx);
    tok.nextToken(); // consume "xref"
  }

  // Parse xref subsections
  while (true) {
    // Peek to see if we have a subsection header (two integers) or "trailer"
    var t1 = tok.peekToken();
    if (!t1) break;
    if (t1.type === "trailer") break;
    if (t1.type === "keyword" && t1.value === "trailer") {
      tok.nextToken();
      break;
    }

    // Subsection: startObjNum count
    if (t1.type !== "integer") break;
    tok.nextToken();
    var startObjNum = t1.value;

    var t2 = tok.nextToken();
    if (!t2 || t2.type !== "integer") break;
    var count = t2.value;

    // Parse entries
    var entryIndex = 0;
    while (entryIndex < count) {
      // Skip whitespace including newlines
      var line = "";
      // Read the next line (entry format: "NNNNNNNNNN GGGGG N")
      while (tok.getPos() < src.length) {
        var ch = src[tok.getPos()];
        if (ch === "\n" || ch === "\r") {
          if (ch === "\r" && tok.getPos() + 1 < src.length && src[tok.getPos() + 1] === "\n") {
            tok.setPos(tok.getPos() + 2);
          } else {
            tok.setPos(tok.getPos() + 1);
          }
          break;
        }
        line += ch;
        tok.setPos(tok.getPos() + 1);
      }

      // Skip empty lines (e.g. newline after subsection header)
      if (line.trim().length === 0) continue;

      // Parse the entry
      var parts = line.trim().split(/\s+/);
      if (parts.length >= 3) {
        var objNum = startObjNum + entryIndex;
        var offset = parseInt(parts[0], 10);
        var generation = parseInt(parts[1], 10);
        var inUse = parts[2] === "n";

        if (inUse) {
          objMap[objNum] = { objNum: objNum, genNum: generation, offset: offset, parsed: false, value: null };
        }
        entryIndex++;
      } else {
        entryIndex++;
      }
    }
  }

  // Parse trailer
  var trailerTok = tok.nextToken();
  if (trailerTok && trailerTok.type === "trailer") {
    // Parse trailer dictionary
    var trailerDict = parseDict(tok);
    if (trailerDict && trailerDict.type === "dictionary") {
      objMap["_trailer"] = trailerDict.dict;
    }
  }

  // Parse all indirect objects
  var objNums = Object.keys(objMap);
  for (var oi = 0; oi < objNums.length; oi++) {
    var key = objNums[oi];
    if (key === "_trailer") continue;
    var obj = objMap[key];
    if (obj.offset > 0 && !obj.parsed) {
      try {
        var objValue = parseIndirectObject(src, obj.offset, objMap);
        if (objValue !== null) {
          obj.value = objValue;
          obj.parsed = true;
        }
      } catch (e) {
        safeArrayPush(warnings, "Failed to parse object " + obj.objNum + ": " + e.message, 50);
      }
    }
  }
}

/**
 * Parse an indirect object at the given offset.
 * Format: "objNum genNum obj ...value... endobj"
 */
function parseIndirectObject(src, offset, objMap) {
  var tok = createTokenizer(src);
  tok.setPos(offset);

  // Read objNum and genNum
  var objNumTok = tok.nextToken();
  var genNumTok = tok.nextToken();
  if (!objNumTok || !genNumTok) return null;

  // Read "obj"
  var objKw = tok.nextToken();
  if (!objKw || objKw.value !== "obj") return null;

  // Parse the value
  var value = parseValue(tok);

  // Skip to endobj
  while (true) {
    var t = tok.nextToken();
    if (!t) break;
    if (t.type === "endobj" || (t.type === "keyword" && t.value === "endobj")) break;
    if (t.type === "stream") {
      // Stream content follows - we handle it separately
      break;
    }
  }

  return value;
}

/**
 * Get the decoded stream data from a content stream reference.
 */
function getStreamData(contentRef, objMap, src, warnings) {
  // If it's a reference, get the object number directly
  if (contentRef && contentRef.type === "reference") {
    var objNum = contentRef.objNum;
    var obj = objMap[objNum];
    if (obj && obj.offset > 0) {
      return extractStreamData(src, obj.offset, objMap, warnings);
    }
    return null;
  }

  // If it's already a string, return it (inline content stream)
  if (typeof contentRef === "string") return contentRef;

  // If it's a resolved dictionary, find the stream
  if (contentRef && contentRef.type === "dictionary") {
    var objNum = findObjNumForDict(objMap, contentRef);
    if (objNum >= 0) {
      var obj = objMap[objNum];
      if (obj && obj.offset > 0) {
        return extractStreamData(src, obj.offset, objMap, warnings);
      }
    }
  }

  return null;
}

/**
 * Find the object number for a given dictionary value.
 */
function findObjNumForDict(objMap, targetDict) {
  for (var key in objMap) {
    if (objMap.hasOwnProperty(key) && key !== "_trailer") {
      var obj = objMap[key];
      if (obj.value === targetDict) {
        return obj.objNum;
      }
    }
  }
  return -1;
}

// ── Stream decoding (FlateDecode) ─────────────────────────────────────────

/**
 * Decode a PDF stream given its filter and parameters.
 * Currently supports FlateDecode (zlib-wrapped DEFLATE).
 * The stream data is a Latin-1 string (each char code is a byte value).
 * @param {string} data - raw stream data as Latin-1 string
 * @param {object|string} filter - the filter name or resolved filter value
 * @param {object} [params] - optional DecodeParms dictionary
 * @returns {string} decoded stream data as Latin-1 string
 */
function decodeStream(data, filter, params) {
  // Resolve filter name
  var filterName = null;
  if (typeof filter === "string") {
    filterName = filter;
  } else if (filter && filter.type === "name") {
    filterName = filter.value;
  } else if (filter && filter.type === "array") {
    // Multiple filters - for now, process the first FlateDecode
    // Real PDFs may chain filters; we handle the simplest case
    for (var fi = 0; fi < filter.items.length; fi++) {
      var f = filter.items[fi];
      var fn = (typeof f === "string") ? f : (f && f.type === "name" ? f.value : null);
      if (fn === "FlateDecode") {
        filterName = fn;
        break;
      }
    }
  }

  if (filterName === "FlateDecode") {
    // Convert Latin-1 string to Uint8Array
    var bytes = new Uint8Array(data.length);
    for (var i = 0; i < data.length; i++) {
      bytes[i] = data.charCodeAt(i) & 0xFF;
    }

    // Strip zlib header (2 bytes) and Adler-32 checksum (4 bytes)
    // zlib wrapper: 2-byte header (CMF + FLG) + DEFLATE data + 4-byte Adler-32
    var deflateStart = 2;
    var deflateEnd = bytes.length - 4;
    if (deflateEnd <= deflateStart) {
      throw new Error("FlateDecode: stream too short for zlib wrapper");
    }

    var deflateData = new Uint8Array(deflateEnd - deflateStart);
    for (var i = 0; i < deflateData.length; i++) {
      deflateData[i] = bytes[deflateStart + i];
    }

    var decompressed = inflate(deflateData);

    // Convert back to Latin-1 string
    var result = "";
    for (var i = 0; i < decompressed.length; i++) {
      result += String.fromCharCode(decompressed[i]);
    }
    return result;
  }

  // Unsupported filter: return raw data with a warning
  // (caller should add the warning)
  return data;
}

/**
 * Extract stream data from an object in the source.
 */
function extractStreamData(src, objOffset, objMap, warnings) {
  var tok = createTokenizer(src);
  tok.setPos(objOffset);

  // Skip objNum genNum obj
  tok.nextToken(); tok.nextToken(); tok.nextToken();

  // Skip the dictionary
  var dict = parseValue(tok);
  if (!dict || dict.type !== "dictionary") return null;

  // Get the stream length
  var dictResolved = resolveValue(dict, objMap);
  var length = null;
  if (dictResolved && dictResolved.Length !== undefined) {
    var lengthVal = resolveValue(dictResolved.Length, objMap);
    if (typeof lengthVal === "number") {
      length = lengthVal;
    } else if (lengthVal && lengthVal.type === "number") {
      length = lengthVal.value;
    }
  }

  // Find "stream" keyword
  var streamTok = null;
  while (true) {
    var t = tok.nextToken();
    if (!t) break;
    if (t.type === "stream" || (t.type === "keyword" && t.value === "stream")) {
      streamTok = t;
      break;
    }
  }

  if (!streamTok) return null;

  // Skip whitespace after "stream"
  var streamPos = tok.getPos();
  // Skip CRLF or LF after "stream"
  if (streamPos < src.length && (src[streamPos] === "\r" || src[streamPos] === "\n")) {
    if (src[streamPos] === "\r" && streamPos + 1 < src.length && src[streamPos + 1] === "\n") {
      streamPos += 2;
    } else {
      streamPos += 1;
    }
  }

  // Read stream data
  var actualLength = length;
  if (actualLength === null || actualLength <= 0) {
    // Find endstream
    var endstreamIdx = src.indexOf("endstream", streamPos);
    if (endstreamIdx < 0) return null;
    actualLength = endstreamIdx - streamPos;
    // Trim trailing whitespace
    while (actualLength > 0 && (src[streamPos + actualLength - 1] === " " ||
           src[streamPos + actualLength - 1] === "\n" ||
           src[streamPos + actualLength - 1] === "\r" ||
           src[streamPos + actualLength - 1] === "\t")) {
      actualLength--;
    }
  }

  if (actualLength <= 0 || streamPos + actualLength > src.length) return null;

  var streamData = src.substring(streamPos, streamPos + actualLength);

  // Check for filters
  var filter = dictResolved ? dictResolved.Filter : null;
  if (filter) {
    var decodeParams = dictResolved ? dictResolved.DecodeParms : null;
    try {
      streamData = decodeStream(streamData, filter, decodeParams);
    } catch (e) {
      safeArrayPush(warnings, "Stream filter decoding failed: " + e.message, 50);
      return null;
    }
  }

  return streamData;
}

/**
 * Find the /Root from the trailer.
 */
function findRoot(objMap, warnings) {
  var trailer = objMap["_trailer"];
  if (trailer && trailer.Root) {
    return trailer.Root;
  }

  // Try to find the root by scanning
  for (var key in objMap) {
    if (objMap.hasOwnProperty(key) && key !== "_trailer") {
      var obj = objMap[key];
      if (obj.value && obj.value.type === "dictionary" && obj.value.dict.Type &&
          (obj.value.dict.Type === "Catalog" || (typeof obj.value.dict.Type === "object" &&
           obj.value.dict.Type.value === "Catalog"))) {
        return { type: "reference", objNum: obj.objNum, genNum: obj.genNum };
      }
    }
  }
  return null;
}

/**
 * Collect all page references from the page tree.
 * Works with raw (unresolved) dictionaries to preserve reference types.
 * Uses a visited set (keyed by objNum) to detect and break cycles in
 * malicious or malformed PDFs that have circular Pages/Kids references.
 */
function collectPages(pagesRef, objMap, pageRefs, warnings, visited) {
  if (!pagesRef) return;

  // Resolve the reference to get the raw dictionary
  var pagesObj = null;
  var objKey = null;
  if (pagesRef.type === "reference") {
    objKey = pagesRef.objNum;
    // Cycle detection: if we've already visited this object, break the loop.
    if (visited === undefined) visited = {};
    if (visited[objKey]) {
      safeArrayPush(warnings, "Circular Pages/Kids reference detected at object " + objKey + "; stopping traversal", 50);
      return;
    }
    visited[objKey] = true;
    pagesObj = objMap[pagesRef.objNum];
    if (!pagesObj || !pagesObj.value) return;
    pagesObj = pagesObj.value;
  } else {
    pagesObj = pagesRef;
  }
  if (!pagesObj || pagesObj.type !== "dictionary") return;

  var dict = pagesObj.dict;
  var type = dict.Type;
  var typeName = (type && type.type === "name") ? type.value : null;

  if (typeName === "Pages") {
    // Internal node - recurse into Kids
    var kids = dict.Kids;
    if (kids && kids.type === "array") {
      for (var ki = 0; ki < kids.items.length; ki++) {
        collectPages(kids.items[ki], objMap, pageRefs, warnings, visited);
      }
    }
  } else if (typeName === "Page") {
    // Leaf node - push the reference
    pageRefs.push(pagesRef);
  } else {
    // Unknown type - try kids anyway
    var kids = dict.Kids;
    if (kids && kids.type === "array") {
      for (var ki = 0; ki < kids.items.length; ki++) {
        collectPages(kids.items[ki], objMap, pageRefs, warnings, visited);
      }
    } else {
      // Assume it's a page
      pageRefs.push(pagesRef);
    }
  }
}

// ── Main entry point ───────────────────────────────────────────────────────

/**
 * Parse a PDF document's bytes and extract text content.
 * @param {{name: string, mimeType: string, bytes: Uint8Array}} input
 * @returns {{format: string, text: string, warnings: string[]}}
 */
function parseDocument(input) {
  var warnings = [];

  if (!input || !input.bytes || input.bytes.length === 0) {
    return result("pdf", "", ["Empty input: no bytes provided"]);
  }

  try {
    var text = parsePdf(input.bytes, warnings);

    // Handle empty result
    if (!text || text.trim().length === 0) {
      return result("pdf", "", warnings.length > 0 ? warnings : ["No extractable text found in PDF"]);
    }

    return result("pdf", text, warnings);
  } catch (e) {
    return result("pdf", "", ["Failed to parse PDF: " + e.message]);
  }
}

// Register the parser
registerParser("pdf", parseDocument);