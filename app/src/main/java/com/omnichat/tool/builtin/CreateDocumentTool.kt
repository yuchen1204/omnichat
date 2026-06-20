package com.omnichat.tool.builtin

import android.content.Context
import com.omnichat.R
import com.omnichat.data.FileAccessType
import com.omnichat.mcp.ToolSchemaDsl.schema
import com.omnichat.tool.BuiltinTool
import org.json.JSONObject
import java.io.File

object CreateDocumentTool : BuiltinTool(
    name = "create_document",
    description = """Create a professionally formatted document (PDF, Word, Excel, or PowerPoint). Supports: section-based content with headings, paragraphs, tables, images, and page breaks. Style presets (business, modern, classic) and custom theme colors. Output formats: pdf, xlsx, docx, pptx. Relative paths resolve under OmniChat/files/ (e.g. "reports/analysis.pdf"). Use this to generate reports, data sheets, slide decks, or formatted text documents.""",
    group = "documents",
    isReadOnly = false,
    isDestructive = true,
    isConcurrencySafe = false,
    searchHint = "create a document or report"
) {

    override val inputSchema = schema {
        prop("path", "string", "Relative file path inside OmniChat/files/, e.g. \"reports/analysis.pdf\".")
        prop("format", "string", "Document format.") { enum("pdf", "xlsx", "docx", "pptx") }
        prop("title", "string", "Main document title.")
        prop("style", "object", "Document style options.") {
            properties {
                prop("themeColor", "string", "Hex color code.")
                prop("preset", "string", "Style preset.") { enum("business", "modern", "classic") }
            }
        }
        prop("sections", "array", "List of document sections in order.") {
            items {
                properties {
                    prop("type", "string", "Section type.") { enum("heading", "text", "table", "image", "page_break") }
                    prop("content", "string", "Text content for heading/text, or image path.")
                    prop("level", "integer", "For heading: 1 (main), 2 (sub), 3 (minor).")
                    prop("markdown", "boolean", "Apply markdown formatting to text.")
                    prop("table", "object", "Table data for table sections.") {
                        properties {
                            prop("headers", "array", "Column headers.") { items { } }
                            prop("rows", "array", "Table rows.") { items { } }
                        }
                    }
                }
            }
        }
        prop("paragraphs", "array", "Legacy: use sections instead.") { items { } }
        prop("table", "object", "Legacy: use sections instead.")
        prop("slides", "array", "Legacy: use sections instead.") { items { } }
        required("path", "format")
    }

    override fun validateInput(arguments: JSONObject): String? {
        val path = arguments.optString("path").trim()
        if (path.isEmpty()) return "Path is required"
        if (path.contains("..")) return "Path traversal not allowed"

        val format = arguments.optString("format", "").trim().lowercase()
        if (format !in listOf("pdf", "xlsx", "docx", "pptx")) {
            return "Unsupported format: $format. Supported: pdf, xlsx, docx, pptx"
        }

        return null
    }

    override suspend fun checkPermissions(context: Context, arguments: JSONObject): String? {
        val path = arguments.optString("path")
        FileToolUtils.resolvePath(context, path, FileAccessType.WRITE)
            ?: return "Permission denied or invalid path: $path"
        return null
    }

    override suspend fun doExecute(context: Context, arguments: JSONObject, sessionId: Long?): JSONObject {
        val relativePath = arguments.optString("path").trim()
        val format = arguments.optString("format").trim().lowercase()
        val title = arguments.optString("title", "").trim()

        val styleObj = arguments.optJSONObject("style")
        val themeColor = styleObj?.optString("themeColor", "#4682B4") ?: "#4682B4"
        val preset = styleObj?.optString("preset", "business") ?: "business"

        val sections = mutableListOf<DocSection>()
        val sectionsArray = arguments.optJSONArray("sections")
        if (sectionsArray != null && sectionsArray.length() > 0) {
            for (i in 0 until sectionsArray.length()) {
                val obj = sectionsArray.optJSONObject(i) ?: continue
                sections.add(parseSection(obj))
            }
        } else {
            val paragraphsArray = arguments.optJSONArray("paragraphs")
            if (paragraphsArray != null) {
                for (i in 0 until paragraphsArray.length()) {
                    paragraphsArray.optString(i).takeIf { it.isNotEmpty() }?.let {
                        sections.add(DocSection(type = "text", content = it))
                    }
                }
            }
            val tableObj = arguments.optJSONObject("table")
            if (tableObj != null) {
                sections.add(parseSection(JSONObject().apply { put("type", "table"); put("table", tableObj) }))
            }
            val slidesArray = arguments.optJSONArray("slides")
            if (slidesArray != null && format == "pptx") {
                for (i in 0 until slidesArray.length()) {
                    val slideObj = slidesArray.optJSONObject(i) ?: continue
                    sections.add(DocSection(type = "page_break"))
                    sections.add(DocSection(type = "heading", content = slideObj.optString("title", ""), level = 1))
                    val contentArr = slideObj.optJSONArray("content")
                    if (contentArr != null) {
                        for (j in 0 until contentArr.length()) {
                            sections.add(DocSection(type = "text", content = contentArr.optString(j)))
                        }
                    }
                }
            }
        }

        if (sections.isEmpty()) return errorResponse("No content provided. Add 'sections' with heading, text, table, or image entries.")

        val file = FileToolUtils.resolvePath(context, relativePath, FileAccessType.WRITE)
            ?: return errorResponse("Permission denied or invalid path: $relativePath")

        file.parentFile?.mkdirs()

        return try {
            when (format) {
                "pdf" -> createPdfDocument(file, title, sections, themeColor, preset, context)
                "xlsx" -> createXlsxDocument(file, title, sections, themeColor)
                "docx" -> createDocxDocument(file, title, sections, themeColor, preset, context)
                "pptx" -> createPptxDocument(file, title, sections, themeColor, preset, context)
            }
            successResponse("Document created: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Throwable) {
            errorResponse("Failed to create document: ${e.localizedMessage}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 内部数据结构
    // ══════════════════════════════════════════════════════════════

    private data class DocSection(
        val type: String,
        val content: String = "",
        val level: Int = 1,
        val markdown: Boolean = false,
        val tableHeaders: List<String> = emptyList(),
        val tableRows: List<List<String>> = emptyList()
    )

    private fun parseSection(obj: JSONObject): DocSection {
        val type = obj.optString("type", "text")
        val content = obj.optString("content", "")
        val level = obj.optInt("level", 1)
        val markdown = obj.optBoolean("markdown", false)

        val tableHeaders = mutableListOf<String>()
        val tableRows = mutableListOf<List<String>>()
        val tableObj = obj.optJSONObject("table")
        if (tableObj != null) {
            val headersArr = tableObj.optJSONArray("headers")
            if (headersArr != null) {
                for (i in 0 until headersArr.length()) tableHeaders.add(headersArr.optString(i))
            }
            val rowsArr = tableObj.optJSONArray("rows")
            if (rowsArr != null) {
                for (i in 0 until rowsArr.length()) {
                    val rowArr = rowsArr.optJSONArray(i) ?: continue
                    val row = mutableListOf<String>()
                    for (j in 0 until rowArr.length()) row.add(rowArr.optString(j))
                    tableRows.add(row)
                }
            }
        }

        return DocSection(type, content, level, markdown, tableHeaders, tableRows)
    }

    private fun hexToColor(hex: String, default: Int): Int {
        return try {
            android.graphics.Color.parseColor(hex)
        } catch (_: Exception) {
            default
        }
    }

    // ══════════════════════════════════════════════════════════════
    // PDF 创建 (Android 原生 PdfDocument API)
    // ══════════════════════════════════════════════════════════════

    private suspend fun createPdfDocument(
        file: File,
        title: String,
        sections: List<DocSection>,
        themeHex: String,
        preset: String,
        context: Context
    ) {
        val doc = android.graphics.pdf.PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 50f
        val themeColor = hexToColor(themeHex, android.graphics.Color.parseColor("#4682B4"))
        var y = margin

        val textPaint = android.text.TextPaint().apply {
            textSize = 12f
            color = android.graphics.Color.BLACK
            isAntiAlias = true
        }

        fun newPage(): android.graphics.pdf.PdfDocument.Page {
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, doc.pages.size + 1).create()
            val page = doc.startPage(pageInfo)
            if (doc.pages.size == 0 && title.isNotEmpty()) {
                val coverPaint = android.graphics.Paint().apply { color = themeColor }
                page.canvas.drawRect(0f, 0f, pageWidth.toFloat(), 200f, coverPaint)
            }
            val footerPaint = android.graphics.Paint().apply {
                textSize = 10f
                color = android.graphics.Color.GRAY
            }
            val footerText = "Generated by OmniChat \u2022 Page ${doc.pages.size + 1}"
            page.canvas.drawText(footerText, margin, pageHeight - 20f, footerPaint)
            return page
        }

        var page = newPage()
        var canvas = page.canvas

        fun checkNewPage(neededHeight: Float) {
            if (y + neededHeight > pageHeight - margin - 30f) {
                doc.finishPage(page)
                page = newPage()
                canvas = page.canvas
                y = margin + 20f
            }
        }

        if (title.isNotEmpty()) {
            val titlePaint = android.text.TextPaint().apply {
                textSize = 32f
                isFakeBoldText = true
                color = android.graphics.Color.WHITE
                isAntiAlias = true
            }
            val titleLayout = android.text.StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, (pageWidth - margin * 2).toInt())
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .build()
            y = 80f
            canvas.save()
            canvas.translate(margin, y)
            titleLayout.draw(canvas)
            canvas.restore()
            y = 230f
        }

        for (section in sections) {
            when (section.type) {
                "heading" -> {
                    val headingPaint = android.text.TextPaint(textPaint).apply {
                        textSize = if (section.level == 1) 20f else if (section.level == 2) 16f else 14f
                        isFakeBoldText = true
                        color = if (section.level == 1) themeColor else android.graphics.Color.DKGRAY
                    }
                    val layout = android.text.StaticLayout.Builder.obtain(section.content, 0, section.content.length, headingPaint, (pageWidth - margin * 2).toInt()).build()
                    checkNewPage(layout.height + 20f)
                    y += 15f
                    canvas.save()
                    canvas.translate(margin, y)
                    layout.draw(canvas)
                    canvas.restore()
                    y += layout.height + 10f
                }
                "text" -> {
                    val layout = android.text.StaticLayout.Builder.obtain(section.content, 0, section.content.length, textPaint, (pageWidth - margin * 2).toInt()).build()
                    checkNewPage(layout.height.toFloat())
                    canvas.save()
                    canvas.translate(margin, y)
                    layout.draw(canvas)
                    canvas.restore()
                    y += layout.height + 8f
                }
                "image" -> {
                    val imgFile = FileToolUtils.resolvePath(context, section.content, FileAccessType.READ)
                    if (imgFile?.exists() == true) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(imgFile.absolutePath)
                        if (bitmap != null) {
                            val maxW = pageWidth - margin * 2
                            val scale = maxW / bitmap.width
                            val drawW = bitmap.width * scale
                            val drawH = bitmap.height * scale
                            checkNewPage(drawH + 10f)
                            canvas.drawBitmap(bitmap, null, android.graphics.RectF(margin, y, margin + drawW, y + drawH), null)
                            y += drawH + 10f
                        }
                    }
                }
                "table" -> {
                    val allRows = mutableListOf<List<String>>()
                    if (section.tableHeaders.isNotEmpty()) allRows.add(section.tableHeaders)
                    allRows.addAll(section.tableRows)
                    if (allRows.isNotEmpty()) {
                        val colCount = allRows.maxOf { it.size }
                        val colWidth = (pageWidth - margin * 2) / colCount
                        val tablePaint = android.graphics.Paint(textPaint).apply { textSize = 10f }

                        for ((rowIdx, row) in allRows.withIndex()) {
                            val isHeader = rowIdx == 0 && section.tableHeaders.isNotEmpty()
                            val rowHeight = 25f
                            checkNewPage(rowHeight)

                            if (isHeader) {
                                tablePaint.color = themeColor
                                canvas.drawRect(margin, y, pageWidth - margin, y + rowHeight, tablePaint)
                                tablePaint.color = android.graphics.Color.WHITE
                                tablePaint.isFakeBoldText = true
                            } else {
                                if (rowIdx % 2 == 0) {
                                    tablePaint.color = android.graphics.Color.rgb(245, 245, 245)
                                    canvas.drawRect(margin, y, pageWidth - margin, y + rowHeight, tablePaint)
                                }
                                tablePaint.color = android.graphics.Color.BLACK
                                tablePaint.isFakeBoldText = false
                            }

                            for ((colIdx, cell) in row.withIndex()) {
                                val x = margin + colIdx * colWidth + 5f
                                val truncated = tablePaint.breakText(cell, true, colWidth - 10f, null)
                                val text = if (truncated < cell.length) cell.substring(0, truncated.toInt()) + "\u2026" else cell
                                canvas.drawText(text, x, y + rowHeight * 0.7f, tablePaint)
                            }

                            tablePaint.style = android.graphics.Paint.Style.STROKE
                            tablePaint.color = android.graphics.Color.LTGRAY
                            canvas.drawRect(margin, y, pageWidth - margin, y + rowHeight, tablePaint)
                            tablePaint.style = android.graphics.Paint.Style.FILL
                            y += rowHeight
                        }
                        y += 10f
                    }
                }
                "page_break" -> {
                    doc.finishPage(page)
                    page = newPage()
                    canvas = page.canvas
                    y = margin
                }
            }
        }

        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    // ══════════════════════════════════════════════════════════════
    // Excel 创建 (Apache POI XSSF)
    // ══════════════════════════════════════════════════════════════

    private suspend fun createXlsxDocument(
        file: File,
        title: String,
        sections: List<DocSection>,
        themeHex: String
    ) {
        val workbook = org.apache.poi.xssf.usermodel.XSSFWorkbook()
        val sheetName = title.take(31).ifEmpty { "Sheet1" }.replace(Regex("[/\\\\?*\\[\\]]"), " ")
        val sheet = workbook.createSheet(sheetName)
        val themeColorInt = hexToColor(themeHex, android.graphics.Color.parseColor("#4682B4"))
        val themeRgb = bytearrayOf(
            (android.graphics.Color.red(themeColorInt)).toByte(),
            (android.graphics.Color.green(themeColorInt)).toByte(),
            (android.graphics.Color.blue(themeColorInt)).toByte()
        )
        val xssfColor = org.apache.poi.xssf.usermodel.XSSFColor(themeRgb, null)

        var rowIdx = 0

        if (title.isNotEmpty()) {
            val titleStyle = workbook.createCellStyle().apply {
                alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
                val font = workbook.createFont().apply {
                    bold = true
                    fontHeightInPoints = 18
                }
                setFont(font)
            }
            val row = sheet.createRow(rowIdx++)
            val cell = row.createCell(0)
            cell.setCellValue(title)
            cell.cellStyle = titleStyle
            sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 5))
            rowIdx++
        }

        for (section in sections) {
            when (section.type) {
                "heading" -> {
                    val hRow = sheet.createRow(rowIdx++)
                    val cell = hRow.createCell(0)
                    cell.setCellValue(section.content)
                    val font = workbook.createFont().apply { bold = true; fontHeightInPoints = 14 }
                    val style = workbook.createCellStyle().apply { setFont(font) }
                    cell.cellStyle = style
                }
                "text" -> {
                    val tRow = sheet.createRow(rowIdx++)
                    val cell = tRow.createCell(0)
                    cell.setCellValue(section.content)
                }
                "table" -> {
                    val headerStyle = workbook.createCellStyle().apply {
                        (this as org.apache.poi.xssf.usermodel.XSSFCellStyle).setFillForegroundColor(xssfColor)
                        fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
                        val font = workbook.createFont().apply { bold = true; color = org.apache.poi.ss.usermodel.IndexedColors.WHITE.index }
                        setFont(font)
                    }
                    if (section.tableHeaders.isNotEmpty()) {
                        val hRow = sheet.createRow(rowIdx++)
                        section.tableHeaders.forEachIndexed { col, h ->
                            val cell = hRow.createCell(col)
                            cell.setCellValue(h)
                            cell.cellStyle = headerStyle
                        }
                    }
                    section.tableRows.forEach { rData ->
                        val r = sheet.createRow(rowIdx++)
                        rData.forEachIndexed { col, v ->
                            val cell = r.createCell(col)
                            val dVal = v.toDoubleOrNull()
                            if (dVal != null) cell.setCellValue(dVal) else cell.setCellValue(v)
                        }
                    }
                    rowIdx++
                }
            }
        }

        for (i in 0 until 10) sheet.autoSizeColumn(i)
        file.outputStream().use { workbook.write(it) }
        workbook.close()
    }

    private fun bytearrayOf(vararg bytes: Byte) = bytes

    // ══════════════════════════════════════════════════════════════
    // Word 创建 (Apache POI XWPF)
    // ══════════════════════════════════════════════════════════════

    private suspend fun createDocxDocument(
        file: File,
        title: String,
        sections: List<DocSection>,
        themeHex: String,
        preset: String,
        context: Context
    ) {
        val doc = org.apache.poi.xwpf.usermodel.XWPFDocument()
        val themeColor = themeHex.replace("#", "")

        if (title.isNotEmpty()) {
            val p = doc.createParagraph()
            p.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
            val r = p.createRun()
            r.setText(title)
            r.isBold = true
            r.fontSize = 28
            r.color = themeColor
        }

        for (section in sections) {
            when (section.type) {
                "heading" -> {
                    val p = doc.createParagraph()
                    p.spacingBefore = 200
                    val r = p.createRun()
                    r.setText(section.content)
                    r.isBold = true
                    r.fontSize = if (section.level == 1) 18 else 14
                    if (section.level == 1) r.color = themeColor
                }
                "text" -> {
                    val p = doc.createParagraph()
                    p.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH
                    val r = p.createRun()
                    r.setText(section.content)
                    r.fontSize = 11
                }
                "image" -> {
                    val imgFile = FileToolUtils.resolvePath(context, section.content, FileAccessType.READ)
                    if (imgFile?.exists() == true) {
                        imgFile.inputStream().use { stream ->
                            val p = doc.createParagraph()
                            p.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
                            val r = p.createRun()
                            val format = if (section.content.endsWith(".png", true)) org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG else org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG
                            r.addPicture(stream, format, section.content, org.apache.poi.util.Units.toEMU(400.0), org.apache.poi.util.Units.toEMU(300.0))
                        }
                    }
                }
                "table" -> {
                    val totalRows = (if (section.tableHeaders.isNotEmpty()) 1 else 0) + section.tableRows.size
                    val colCount = maxOf(section.tableHeaders.size, section.tableRows.maxOfOrNull { it.size } ?: 0)
                    if (totalRows > 0 && colCount > 0) {
                        val table = doc.createTable(totalRows, colCount)
                        table.setWidth("100%")
                        var rIdx = 0
                        if (section.tableHeaders.isNotEmpty()) {
                            val hRow = table.getRow(rIdx++)
                            section.tableHeaders.forEachIndexed { c, h ->
                                val cell = hRow.getCell(c) ?: hRow.addNewTableCell()
                                cell.setColor(themeColor)
                                val p = cell.addParagraph()
                                p.createRun().apply { setText(h); isBold = true; color = "FFFFFF" }
                                if (cell.paragraphs.size > 1) cell.removeParagraph(0)
                            }
                        }
                        section.tableRows.forEach { rData ->
                            val row = table.getRow(rIdx++) ?: table.createRow()
                            rData.forEachIndexed { c, v ->
                                val cell = row.getCell(c) ?: row.addNewTableCell()
                                val p = cell.addParagraph()
                                p.createRun().setText(v)
                                if (cell.paragraphs.size > 1) cell.removeParagraph(0)
                            }
                        }
                    }
                }
                "page_break" -> {
                    doc.createParagraph().createRun().addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE)
                }
            }
        }

        file.outputStream().use { doc.write(it) }
        doc.close()
    }

    // ══════════════════════════════════════════════════════════════
    // PowerPoint 创建 (Apache POI XSLF)
    // ══════════════════════════════════════════════════════════════

    private suspend fun createPptxDocument(
        file: File,
        title: String,
        sections: List<DocSection>,
        themeHex: String,
        preset: String,
        context: Context
    ) {
        val ppt = org.apache.poi.xslf.usermodel.XMLSlideShow()
        val themeColor = hexToColor(themeHex, android.graphics.Color.parseColor("#4682B4"))

        val titleLayout = ppt.slideMasters[0].getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE)
        val titleSlide = ppt.createSlide(titleLayout)
        titleSlide.placeholders[0].text = title

        var currentSlide: org.apache.poi.xslf.usermodel.XSLFSlide? = null
        val contentLayout = ppt.slideMasters[0].getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE_AND_CONTENT)

        for (section in sections) {
            when (section.type) {
                "page_break" -> {
                    currentSlide = ppt.createSlide(contentLayout)
                }
                "heading" -> {
                    if (currentSlide == null) currentSlide = ppt.createSlide(contentLayout)
                    currentSlide?.placeholders?.getOrNull(0)?.text = section.content
                }
                "text" -> {
                    if (currentSlide == null) currentSlide = ppt.createSlide(contentLayout)
                    val ph = currentSlide?.placeholders?.getOrNull(1)
                    if (ph != null) {
                        val p = ph.addNewTextParagraph()
                        p.isBullet = true
                        p.addNewTextRun().setText(section.content)
                    }
                }
                "image" -> {
                    if (currentSlide == null) currentSlide = ppt.createSlide(contentLayout)
                    val imgFile = FileToolUtils.resolvePath(context, section.content, FileAccessType.READ)
                    if (imgFile?.exists() == true) {
                        val data = imgFile.readBytes()
                        val format = if (section.content.endsWith(".png", true)) org.apache.poi.sl.usermodel.PictureData.PictureType.PNG else org.apache.poi.sl.usermodel.PictureData.PictureType.JPEG
                        val pic = ppt.addPicture(data, format)
                        currentSlide?.createPicture(pic)
                    }
                }
                "table" -> {
                    if (currentSlide == null) currentSlide = ppt.createSlide(contentLayout)
                    try {
                        val table = currentSlide?.createTable()
                        if (table != null) {
                            if (section.tableHeaders.isNotEmpty()) {
                                val hRow = table.addRow()
                                section.tableHeaders.forEach { h ->
                                    val cell = hRow.addCell()
                                    cell.text = h
                                }
                            }
                            section.tableRows.forEach { rData ->
                                val row = table.addRow()
                                rData.forEach { v -> row.addCell().text = v }
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("CreateDocumentTool", "Failed to create table in PPTX due to missing AWT on Android, falling back to text representation", t)
                        val ph = currentSlide?.placeholders?.getOrNull(1)
                        if (ph != null) {
                            val p = ph.addNewTextParagraph()
                            p.addNewTextRun().setText(context.getString(R.string.tool_doc_pptx_table_fallback_android))
                            if (section.tableHeaders.isNotEmpty()) {
                                val hParagraph = ph.addNewTextParagraph()
                                hParagraph.addNewTextRun().setText(section.tableHeaders.joinToString(" | "))
                            }
                            section.tableRows.forEach { rData ->
                                val rParagraph = ph.addNewTextParagraph()
                                rParagraph.addNewTextRun().setText(rData.joinToString(" | "))
                            }
                        }
                    }
                }
            }
        }

        file.outputStream().use { ppt.write(it) }
        ppt.close()
    }
}
