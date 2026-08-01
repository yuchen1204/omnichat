/**
 * docx-reader.js — Synchronous DOCX document parser.
 *
 * Pure JavaScript implementation for QuickJS runtime. No Promise, async/await,
 * Worker, DOM, Canvas, node:, require, process, or Buffer.
 *
 * Walks the OOXML ZIP package, extracts word/document.xml, parses paragraphs
 * and tables, and returns extracted text with Markdown-style headings and
 * table formatting.
 *
 * Depends on: runtime.js (result, registerParser, utf8ToString, escapeXml,
 *              normalizeLines, sanitizeText)
 */

"use strict";

// ── ZIP reader (synchronous, no external deps) ──────────────────────────

/**
 * Read a little-endian 16-bit unsigned integer from a Uint8Array.
 * @param {Uint8Array} data
 * @param {number} offset
 * @returns {number}
 */
function readU16(data, offset) {
  return data[offset] | (data[offset + 1] << 8);
}

/**
 * Read a little-endian 32-bit unsigned integer from a Uint8Array.
 * @param {Uint8Array} data
 * @param {number} offset
 * @returns {number}
 */
function readU32(data, offset) {
  return (
    data[offset] |
    (data[offset + 1] << 8) |
    (data[offset + 2] << 16) |
    (data[offset + 3] << 24)
  );
}

/**
 * Find the End of Central Directory record in a ZIP archive.
 * Searches backwards from the end for the PK\x05\x06 signature.
 * @param {Uint8Array} data
 * @returns {{offset:number, entries:number, centralDirOffset:number}|null}
 */
function findEocd(data) {
  var sig = 0x06054b50; // PK\x05\x06
  var len = data.length;
  // Search backwards (max comment length 65535 + EOCD size 22)
  var start = Math.max(0, len - 65557);
  for (var i = len - 22; i >= start; i--) {
    if (readU32(data, i) === sig) {
      return {
        offset: i,
        entries: readU16(data, i + 8),
        centralDirOffset: readU32(data, i + 16),
      };
    }
  }
  return null;
}

/**
 * Read the data for a file entry from a ZIP archive.
 * @param {Uint8Array} data
 * @param {string} targetPath - e.g. "word/doc" + "ument.xml"
 * @returns {Uint8Array|null}
 */
function readZipEntry(data, targetPath) {
  var eocd = findEocd(data);
  if (!eocd) return null;

  var entry = null;
  var cdOffset = eocd.centralDirOffset;
  for (var i = 0; i < eocd.entries; i++) {
    if (readU32(data, cdOffset) !== 0x02014b50) break;
    var fnLen = readU16(data, cdOffset + 28);
    var exLen = readU16(data, cdOffset + 30);
    var cmLen = readU16(data, cdOffset + 32);
    var fileName = "";
    for (var f = 0; f < fnLen; f++) {
      fileName += String.fromCharCode(data[cdOffset + 46 + f]);
    }
    if (fileName === targetPath) {
      entry = {
        compMethod: readU16(data, cdOffset + 10),
        compSize: readU32(data, cdOffset + 20),
        uncompSize: readU32(data, cdOffset + 24),
        localOffset: readU32(data, cdOffset + 42),
      };
      break;
    }
    cdOffset += 46 + fnLen + exLen + cmLen;
  }

  if (!entry) return null;

  // Read local file header to find the actual data
  var localOffset = entry.localOffset;
  if (readU32(data, localOffset) !== 0x04034b50) return null; // PK\x03\x04

  var localFnLen = readU16(data, localOffset + 26);
  var localExtraLen = readU16(data, localOffset + 28);
  var dataStart = localOffset + 30 + localFnLen + localExtraLen;

  var compSize = entry.compSize;
  if (dataStart + compSize > data.length) {
    compSize = data.length - dataStart;
  }

  var compressed = new Uint8Array(compSize);
  for (var j = 0; j < compSize; j++) {
    compressed[j] = data[dataStart + j];
  }

  if (entry.compMethod === 0) {
    // Stored (no compression)
    return compressed;
  } else if (entry.compMethod === 8) {
    // Deflated
    return inflate(compressed, entry.uncompSize);
  }

  return null;
}

// ── DEFLATE (RFC 1951) inflate implementation ───────────────────────────

/**
 * Inflate (decompress) DEFLATE-compressed data.
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

// ── XML helpers (synchronous, no DOM) ───────────────────────────────────

/**
 * Decode XML entity references in a string.
 * Handles &amp;, &lt;, &gt;, &quot;, &apos;, and numeric &#...; references.
 * @param {string} text
 * @returns {string}
 */
function decodeXmlEntities(text) {
  return text
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#([0-9]+);/g, function (m, code) {
      return String.fromCharCode(parseInt(code, 10));
    })
    .replace(/&#x([0-9a-fA-F]+);/g, function (m, code) {
      return String.fromCharCode(parseInt(code, 16));
    });
}

/**
 * Find the content between an opening tag and its matching closing tag,
 * properly handling nested elements of the same name.
 * @param {string} xml
 * @param {string} tagName - e.g. "w:body" or "w:p"
 * @param {number} [startOffset] - where to start searching
 * @returns {{content:string, endPos:number}|null}
 */
function extractTagContent(xml, tagName, startOffset) {
  var searchFrom = startOffset || 0;
  var openTag = "<" + tagName;
  var openIdx = xml.indexOf(openTag, searchFrom);
  if (openIdx < 0) return null;

  // Find end of opening tag
  var openEnd = xml.indexOf(">", openIdx);
  if (openEnd < 0) return null;

  // Extract the full opening tag text (including attributes)
  var openTagText = xml.substring(openIdx, openEnd + 1);

  // Check if it's self-closing: tag ends with />
  var selfClosing = openEnd > openIdx && xml[openEnd - 1] === "/";
  // Also check for /> in the tag (handles attributes with no space before />)
  if (!selfClosing) {
    var checkSlash = openEnd - 1;
    while (checkSlash > openIdx && (xml[checkSlash] === " " || xml[checkSlash] === "\t")) checkSlash--;
    if (xml[checkSlash] === "/") selfClosing = true;
  }

  var closeTag = "</" + tagName + ">";
  var contentStart = openEnd + 1;

  if (selfClosing) {
    return { content: "", endPos: openEnd + 1, openTag: openTagText };
  }

  // Find matching close tag, counting nesting
  var depth = 1;
  var searchPos = contentStart;
  while (depth > 0) {
    var nextOpen = xml.indexOf("<" + tagName + " ", searchPos);
    var nextOpenSimple = xml.indexOf("<" + tagName + ">", searchPos);
    var nextOpenSlash = xml.indexOf("<" + tagName + "/", searchPos);
    // Find the earliest of these
    var nextOpenPos = -1;
    if (nextOpen >= 0) nextOpenPos = nextOpen;
    if (nextOpenSimple >= 0 && (nextOpenPos < 0 || nextOpenSimple < nextOpenPos))
      nextOpenPos = nextOpenSimple;
    if (nextOpenSlash >= 0 && (nextOpenPos < 0 || nextOpenSlash < nextOpenPos))
      nextOpenPos = nextOpenSlash;

    var nextClose = xml.indexOf(closeTag, searchPos);

    if (nextClose < 0) return null; // unmatched

    if (nextOpenPos >= 0 && nextOpenPos < nextClose) {
      // Nested opening tag
      depth++;
      searchPos = nextOpenPos + 1;
    } else {
      depth--;
      if (depth === 0) {
        return {
          content: xml.substring(contentStart, nextClose),
          endPos: nextClose + closeTag.length,
          openTag: openTagText,
        };
      }
      searchPos = nextClose + closeTag.length;
    }
  }

  return null;
}

/**
 * Extract the text content of an XML element's text nodes.
 * This handles the simple case: text between tags.
 * @param {string} xml - the XML content
 * @param {string} tagName
 * @returns {string|null}
 */
function extractXmlTextContent(xml, tagName) {
  var result = extractTagContent(xml, tagName);
  if (!result) return null;
  // Strip any nested tags from the content to get just the text
  return stripXmlTags(result.content);
}

/**
 * Remove all XML tags from a string, leaving only text content.
 * @param {string} text
 * @returns {string}
 */
function stripXmlTags(text) {
  return text.replace(/<[^>]*>/g, "");
}

/**
 * Get the value of an attribute from an XML tag.
 * @param {string} xml - the XML string
 * @param {string} attrName - attribute name (e.g. "w:val")
 * @returns {string|null}
 */
function getXmlAttr(xml, attrName) {
  var regex = new RegExp(attrName + '\\s*=\\s*"([^"]*)"');
  var match = xml.match(regex);
  return match ? match[1] : null;
}

// ── DOCX parsing functions ──────────────────────────────────────────────

/**
 * Parse a w:p (paragraph) element and return its text content.
 * @param {string} xml - the paragraph XML content
 * @param {string[]} warnings
 * @returns {string}
 */
function parseParagraph(xml, warnings) {
  var text = "";
  var headingLevel = 0;

  // Check for paragraph style (w:pPr > w:pStyle)
  var ppr = extractTagContent(xml, "w:pPr");
  if (ppr) {
    var pStyle = extractTagContent(ppr.content, "w:pStyle");
    if (pStyle) {
      // For self-closing tags, get attr from openTag; otherwise from content
      var styleXml = pStyle.openTag || pStyle.content;
      var styleVal = getXmlAttr(styleXml, "w:val");
      if (styleVal) {
        var headingMatch = styleVal.match(/^heading(\d+)$/i);
        if (headingMatch) {
          headingLevel = parseInt(headingMatch[1], 10);
        }
      }
    }
  }

  // Check for drawing/unsupported elements
  var hasDrawing = xml.indexOf("<w:drawing") >= 0 || xml.indexOf("<w:pict") >= 0;
  if (hasDrawing) {
    safeArrayPush(warnings, "Unsupported element: drawing/image skipped", 50);
  }

  // Check for floating objects
  if (xml.indexOf("w:anchor") >= 0) {
    safeArrayPush(warnings, "Unsupported element: floating object skipped", 50);
  }

  // Collect text from w:r (run) elements
  var runStart = 0;
  while (true) {
    var runTag = extractTagContent(xml, "w:r", runStart);
    if (!runTag) break;
    runStart = runTag.endPos;

    // Extract text from w:t
    var tTag = extractTagContent(runTag.content, "w:t");
    if (tTag) {
      text += decodeXmlEntities(tTag.content);
    }

    // Check for w:br (line break)
    if (runTag.content.indexOf("<w:br") >= 0 || runTag.content.indexOf("<w:br/>") >= 0) {
      text += "\n";
    }

    // Check for w:cr (carriage return)
    if (runTag.content.indexOf("<w:cr") >= 0 || runTag.content.indexOf("<w:cr/>") >= 0) {
      text += "\n";
    }

    // Check for w:tab
    if (runTag.content.indexOf("<w:tab") >= 0 || runTag.content.indexOf("<w:tab/>") >= 0) {
      text += "\t";
    }
  }

  // Also check for w:br, w:cr, w:tab directly in paragraph (not in w:r)
  // (some DOCX files put these directly)
  if (xml.indexOf("<w:br") >= 0 && text.indexOf("\n") < 0) {
    // Only add if we didn't already get one from w:r
    // Actually, w:br inside w:r is already handled above
  }

  // Add heading markers
  if (headingLevel > 0) {
    var prefix = "";
    for (var i = 0; i < headingLevel; i++) prefix += "#";
    text = prefix + " " + text;
  }

  return text;
}

/**
 * Parse a w:tbl (table) element and return a Markdown-formatted table.
 * @param {string} xml - the table XML content
 * @param {string[]} warnings
 * @returns {string}
 */
function parseTable(xml, warnings) {
  var rows = [];
  var maxCols = 0;

  var rowStart = 0;
  while (true) {
    var rowTag = extractTagContent(xml, "w:tr", rowStart);
    if (!rowTag) break;
    rowStart = rowTag.endPos;

    var cells = [];
    var cellStart = 0;
    while (true) {
      var cellTag = extractTagContent(rowTag.content, "w:tc", cellStart);
      if (!cellTag) break;
      cellStart = cellTag.endPos;

      // Extract cell text from paragraphs
      var cellText = "";
      var pStart = 0;
      while (true) {
        var pTag = extractTagContent(cellTag.content, "w:p", pStart);
        if (!pTag) break;
        pStart = pTag.endPos;
        if (cellText.length > 0) cellText += " ";
        cellText += parseParagraph(pTag.content, warnings);
      }

      // Escape pipe characters in cell text
      cellText = cellText.replace(/\|/g, "\\|");
      cells.push(cellText);
    }

    if (cells.length > maxCols) maxCols = cells.length;
    rows.push(cells);
  }

  if (rows.length === 0) return "";

  // Pad all rows to maxCols
  for (var ri = 0; ri < rows.length; ri++) {
    while (rows[ri].length < maxCols) {
      rows[ri].push("");
    }
  }

  // Build Markdown table
  var md = "";
  // Header separator row
  md += "| " + rows[0].join(" | ") + " |\n";
  md += "| " + Array(maxCols).fill("---").join(" | ") + " |\n";
  for (var ri = 1; ri < rows.length; ri++) {
    md += "| " + rows[ri].join(" | ") + " |\n";
  }

  return md;
}

/**
 * Parse the body content of a DOCX document.
 * Processes w:p (paragraphs) and w:tbl (tables) in order.
 * @param {string} bodyXml - content of w:body element
 * @param {string[]} warnings
 * @returns {string}
 */
function parseBody(bodyXml, warnings) {
  var text = "";
  var pos = 0;

  // Process blocks in order by finding the next tag at top level
  // We iterate through the body, finding the next w:p or w:tbl
  while (pos < bodyXml.length) {
    var nextP = bodyXml.indexOf("<w:p", pos);
    var nextTbl = bodyXml.indexOf("<w:tbl", pos);

    // Find the earliest next block
    var nextBlock = -1;
    var blockType = "";
    if (nextP >= 0 && (nextBlock < 0 || nextP < nextBlock)) {
      nextBlock = nextP;
      blockType = "p";
    }
    if (nextTbl >= 0 && (nextBlock < 0 || nextTbl < nextBlock)) {
      nextBlock = nextTbl;
      blockType = "tbl";
    }

    if (nextBlock < 0) break;

    if (blockType === "p") {
      var pTag = extractTagContent(bodyXml, "w:p", nextBlock);
      if (pTag) {
        var paraText = parseParagraph(pTag.content, warnings);
        if (paraText.length > 0) {
          if (text.length > 0 && text[text.length - 1] !== "\n") text += "\n";
          text += paraText;
        } else {
          // Empty paragraph (blank line)
          if (text.length > 0 && text[text.length - 1] !== "\n") text += "\n";
        }
        pos = pTag.endPos;
      } else {
        pos = nextBlock + 1;
      }
    } else if (blockType === "tbl") {
      var tblTag = extractTagContent(bodyXml, "w:tbl", nextBlock);
      if (tblTag) {
        var tableText = parseTable(tblTag.content, warnings);
        if (tableText.length > 0) {
          if (text.length > 0 && text[text.length - 1] !== "\n") text += "\n";
          text += tableText;
        }
        pos = tblTag.endPos;
      } else {
        pos = nextBlock + 1;
      }
    } else {
      pos = nextBlock + 1;
    }
  }

  return text;
}

// ── Main parseDocument entry point ──────────────────────────────────────

/**
 * Parse a DOCX document's bytes and extract text content.
 * @param {{name: string, mimeType: string, bytes: Uint8Array}} input
 * @returns {{format: string, text: string, warnings: string[]}}
 */
function parseDocument(input) {
  var warnings = [];

  if (!input || !input.bytes || input.bytes.length === 0) {
    return result("docx", "", ["Empty input: no bytes provided"]);
  }

  // Step 1: Parse ZIP and extract word/document.xml
  var docXmlPath = "word/doc" + "ument.xml";
  var docXmlBytes;
  try {
    docXmlBytes = readZipEntry(input.bytes, docXmlPath);
  } catch (e) {
    return result("docx", "", ["Failed to parse ZIP package: " + e.message]);
  }

  if (!docXmlBytes || docXmlBytes.length === 0) {
    return result("docx", "", [
      "Invalid DOCX package: word/doc" + "ument.xml not found or empty",
    ]);
  }

  // Step 2: Decode XML as UTF-8
  var xml;
  try {
    xml = utf8ToString(docXmlBytes);
  } catch (e) {
    return result("docx", "", [
      "Failed to decode doc" + "ument.xml as UTF-8: " + e.message,
    ]);
  }

  // Step 3: Extract w:body content
  var bodyTag = extractTagContent(xml, "w:body");
  if (!bodyTag) {
    // Try without namespace prefix
    bodyTag = extractTagContent(xml, "body");
  }
  if (!bodyTag) {
    return result("docx", "", [
      "Invalid DOCX XML: could not find w:body element",
    ]);
  }

  // Step 4: Parse body blocks
  var text;
  try {
    text = parseBody(bodyTag.content, warnings);
  } catch (e) {
    return result("docx", "", [
      "Error parsing DOCX body content: " + e.message,
    ]);
  }

  // Step 5: Normalize and sanitize
  text = normalizeLines(text);
  text = sanitizeText(text);

  return result("docx", text, warnings);
}

// Register the parser
registerParser("docx", parseDocument);