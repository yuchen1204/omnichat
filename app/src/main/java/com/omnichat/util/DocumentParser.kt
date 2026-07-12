package com.omnichat.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object DocumentParser {

    data class ParseResult(
        val text: String,
        val imagePaths: List<String>
    )

    fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        if (name.isEmpty()) {
            name = uri.lastPathSegment ?: "unknown_file"
        }
        return name
    }

    fun parse(
        context: Context,
        uri: Uri,
        extractImages: Boolean
    ): ParseResult {
        val fileName = getFileName(context, uri).lowercase()
        return when {
            fileName.endsWith(".txt") -> parseTxt(context, uri)
            fileName.endsWith(".pdf") -> parsePdf(context, uri, extractImages)
            fileName.endsWith(".ppt") || fileName.endsWith(".pptx") -> parsePpt(context, uri, extractImages)
            else -> {
                // Try parsing as text by default if extension unknown
                parseTxt(context, uri)
            }
        }
    }

    private fun parseTxt(context: Context, uri: Uri): ParseResult {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: ""
            ParseResult(text, emptyList())
        } catch (e: Exception) {
            e.printStackTrace()
            ParseResult("", emptyList())
        }
    }

    private fun parsePdf(context: Context, uri: Uri, extractImages: Boolean): ParseResult {
        val textBuilder = StringBuilder()
        val imagePaths = mutableListOf<String>()
        var document: com.tom_roush.pdfbox.pdmodel.PDDocument? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(input)
                val doc = document ?: return ParseResult("", emptyList())

                // 1. Extract text
                val pdfStripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                textBuilder.append(pdfStripper.getText(doc))

                // 2. Extract images if requested
                if (extractImages) {
                    val cacheDir = File(context.cacheDir, "extracted_images").apply { mkdirs() }
                    for (pageIndex in 0 until doc.numberOfPages) {
                        val page = doc.getPage(pageIndex)
                        val resources = page.resources ?: continue
                        for (xObjectName in resources.xObjectNames) {
                            try {
                                if (resources.isImageXObject(xObjectName)) {
                                    val xObject = resources.getXObject(xObjectName)
                                    if (xObject is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                                        val bitmap = xObject.image
                                        if (bitmap != null) {
                                            val file = File(
                                                cacheDir,
                                                "pdf_${System.currentTimeMillis()}_page${pageIndex}_${xObjectName.name}.jpg"
                                            )
                                            FileOutputStream(file).use { out ->
                                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                            }
                                            imagePaths.add(file.absolutePath)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                document?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return ParseResult(textBuilder.toString(), imagePaths)
    }

    private fun parsePpt(context: Context, uri: Uri, extractImages: Boolean): ParseResult {
        val textBuilder = StringBuilder()
        val imagePaths = mutableListOf<String>()
        var slideShow: org.apache.poi.sl.usermodel.SlideShow<*, *>? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                slideShow = org.apache.poi.sl.usermodel.SlideShowFactory.create(input)
                val ss = slideShow ?: return ParseResult("", emptyList())

                // 1. Extract text from shapes
                for ((slideIndex, slide) in ss.slides.withIndex()) {
                    textBuilder.append("--- Slide ${slideIndex + 1} ---\n")
                    for (shape in slide.shapes) {
                        if (shape is org.apache.poi.sl.usermodel.TextShape<*, *>) {
                            val txt = shape.text
                            if (!txt.isNullOrBlank()) {
                                textBuilder.append(txt).append("\n")
                            }
                        }
                    }
                    textBuilder.append("\n")
                }

                // 2. Extract images if requested
                if (extractImages) {
                    val cacheDir = File(context.cacheDir, "extracted_images").apply { mkdirs() }
                    val pictures = ss.pictureData
                    for ((index, pictureData) in pictures.withIndex()) {
                        try {
                            val bytes = pictureData.data
                            if (bytes != null && bytes.isNotEmpty()) {
                                val contentType = pictureData.contentType ?: ""
                                val ext = when {
                                    contentType.contains("png") -> "png"
                                    contentType.contains("gif") -> "gif"
                                    else -> "jpg"
                                }
                                val file = File(cacheDir, "ppt_${System.currentTimeMillis()}_$index.$ext")
                                FileOutputStream(file).use { out ->
                                    out.write(bytes)
                                }
                                imagePaths.add(file.absolutePath)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                slideShow?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return ParseResult(textBuilder.toString(), imagePaths)
    }
}
