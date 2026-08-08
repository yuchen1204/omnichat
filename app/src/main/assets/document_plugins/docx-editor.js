/** Synchronous DOCX OOXML editor. Requires runtime.js ZIP helpers. */
"use strict";

function extractTagContent(xml, tagName) {
  var openIdx = xml.indexOf("<" + tagName);
  if (openIdx < 0) return null;
  var openEnd = xml.indexOf(">", openIdx);
  if (openEnd < 0) return null;
  var contentStart = openEnd + 1;
  var closeTag = "</" + tagName + ">";
  var closeIdx = xml.indexOf(closeTag, contentStart);
  if (closeIdx < 0) return null;
  return {content: xml.substring(contentStart, closeIdx), endPos: closeIdx + closeTag.length};
}

function editorParagraph(text) {
  var parts = normalizeLines(text).split("\n"), xml = "";
  for (var i = 0; i < parts.length; i++) {
    if (i > 0) xml += "<w:p><w:r><w:br/></w:r></w:p>";
    xml += "<w:p><w:r><w:t xml:space=\"preserve\">" + escapeXml(parts[i]) + "</w:t></w:r></w:p>";
  }
  return xml;
}

function decodeXmlText(text) {
  return text
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, "\"").replace(/&apos;/g, "'")
    .replace(/&#x([0-9a-fA-F]+);/g, function(_, value) { return String.fromCharCode(parseInt(value, 16)); })
    .replace(/&#([0-9]+);/g, function(_, value) { return String.fromCharCode(parseInt(value, 10)); })
    .replace(/&amp;/g, "&");
}

function textNodes(paragraphXml) {
  var nodes = [], match, re = /<w:t(\s[^>]*)?>([\s\S]*?)<\/w:t>/g;
  while ((match = re.exec(paragraphXml)) !== null) {
    nodes.push({start:match.index, end:re.lastIndex, open:match[0].substring(0, match[0].indexOf(">") + 1), text:decodeXmlText(match[2]), raw:match[0]});
  }
  return nodes;
}

function replaceInParagraph(paragraphXml, oldText, newText) {
  var nodes = textNodes(paragraphXml), combined = "", i;
  for (i = 0; i < nodes.length; i++) combined += nodes[i].text;
  var matchStart = combined.indexOf(oldText);
  if (matchStart < 0) return null;
  var matchEnd = matchStart + oldText.length, first = -1, last = -1, offset = 0;
  for (i = 0; i < nodes.length; i++) {
    var nodeEnd = offset + nodes[i].text.length;
    if (first < 0 && matchStart < nodeEnd) first = i;
    if (matchEnd <= nodeEnd) { last = i; break; }
    offset = nodeEnd;
  }
  if (first < 0 || last < 0) return null;
  var firstOffset = 0, lastOffset = 0;
  for (i = 0; i < first; i++) firstOffset += nodes[i].text.length;
  for (i = 0; i <= last; i++) lastOffset += nodes[i].text.length;
  var result = "", cursor = 0;
  for (i = 0; i < nodes.length; i++) {
    result += paragraphXml.substring(cursor, nodes[i].start);
    if (i === first) {
      result += nodes[i].open + escapeXml(nodes[i].text.substring(0, matchStart - firstOffset)) + escapeXml(newText);
      if (first === last) result += escapeXml(nodes[i].text.substring(matchEnd - firstOffset));
      result += "</w:t>";
    } else if (i > first && i < last) {
      result += nodes[i].open + "</w:t>";
    } else if (i === last) {
      result += nodes[i].open + escapeXml(nodes[i].text.substring(matchEnd - (lastOffset - nodes[i].text.length))) + "</w:t>";
    } else {
      result += nodes[i].raw;
    }
    cursor = nodes[i].end;
  }
  return result + paragraphXml.substring(cursor);
}

function appendStyledParagraphs(bodyXml, text) {
  var sect = bodyXml.lastIndexOf("<w:sectPr");
  if (sect < 0) throw new Error("DOCX section properties are missing");
  var beforeSect = bodyXml.substring(0, sect), templateStart = beforeSect.lastIndexOf("<w:p");
  while (templateStart >= 0 && beforeSect.charAt(templateStart + 4) !== ">" && beforeSect.charAt(templateStart + 4) !== " ") templateStart = beforeSect.lastIndexOf("<w:p", templateStart - 1);
  var templateEnd = beforeSect.indexOf("</w:p>", templateStart);
  var parts = normalizeLines(text).split("\n"), appended = "";
  if (templateStart >= 0 && templateEnd >= 0) {
    var template = beforeSect.substring(templateStart, templateEnd + 6), nodes = textNodes(template), templateText = "";
    for (var n = 0; n < nodes.length; n++) templateText += nodes[n].text;
    for (var i = 0; i < parts.length; i++) {
      var styled = template;
      if (nodes.length > 0) styled = replaceInParagraph(template, templateText, parts[i]);
      appended += styled;
    }
  } else {
    appended = editorParagraph(text);
  }
  return beforeSect + appended + bodyXml.substring(sect);
}

function editDocument(input, edit) {
  if (!input || !input.bytes || input.bytes.length === 0) throw new Error("DOCX input is empty");
  if (!edit || (edit.operation !== "append" && edit.operation !== "replace")) throw new Error("Unsupported DOCX edit operation");
  if (typeof edit.content !== "string" || edit.content.length === 0) throw new Error("DOCX edit content is empty");
  var entries = readZipEntries(input.bytes), documentEntry = null;
  for (var i = 0; i < entries.length; i++) if (entries[i].name === "word/document.xml") documentEntry = entries[i];
  if (!documentEntry) throw new Error("DOCX document.xml is missing");
  var xml = utf8ToString(documentEntry.bytes), body = extractTagContent(xml, "w:body");
  if (!body) throw new Error("DOCX w:body is missing");
  var bodyXml = body.content;
  if (edit.operation === "append") {
    bodyXml = appendStyledParagraphs(bodyXml, edit.content);
  } else {
    if (typeof edit.oldText !== "string" || edit.oldText.length === 0) throw new Error("DOCX oldText is empty");
    var paragraphs = [], paragraphRe = /<w:p(?:\s[^>]*)?>[\s\S]*?<\/w:p>/g, paragraphMatch, replaced = false, cursor = 0;
    while ((paragraphMatch = paragraphRe.exec(bodyXml)) !== null) {
      var paragraph = paragraphMatch[0], updated = replaceInParagraph(paragraph, edit.oldText, edit.content);
      paragraphs.push(bodyXml.substring(cursor, paragraphMatch.index));
      paragraphs.push(updated === null ? paragraph : updated);
      cursor = paragraphRe.lastIndex;
      if (updated !== null) replaced = true;
    }
    if (!replaced) throw new Error("oldText not found in DOCX");
    bodyXml = paragraphs.join("") + bodyXml.substring(cursor);
  }
  var bodyStart = xml.indexOf(">", xml.indexOf("<w:body")) + 1, bodyEnd = xml.indexOf("</w:body>", bodyStart);
  documentEntry.bytes = utf8Bytes(xml.substring(0, bodyStart) + bodyXml + xml.substring(bodyEnd));
  var resultBytes = writeZipEntries(entries);
  var encoded = encodeBase64(resultBytes);
  return {format:"docx", bytesBase64:encoded, warnings:[]};
}

function utf8Bytes(text) {
  var out=[]; for(var i=0;i<text.length;i++){var c=text.charCodeAt(i);if(c<128)out.push(c);else if(c<2048)out.push(192|(c>>6),128|(c&63));else if(c>=55296&&c<=56319&&i+1<text.length){var d=text.charCodeAt(++i),p=65536+((c-55296)<<10)+(d-56320);out.push(240|(p>>18),128|((p>>12)&63),128|((p>>6)&63),128|(p&63));}else out.push(224|(c>>12),128|((c>>6)&63),128|(c&63));} return new Uint8Array(out);
}
function encodeBase64(bytes) { var a="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/", out=""; for(var i=0;i<bytes.length;i+=3){var n=(bytes[i]<<16)|((i+1<bytes.length?bytes[i+1]:0)<<8)|(i+2<bytes.length?bytes[i+2]:0);out+=a[(n>>18)&63]+a[(n>>12)&63]+(i+1<bytes.length?a[(n>>6)&63]:"=")+(i+2<bytes.length?a[n&63]:"=");} return out; }
