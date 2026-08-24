package com.company.notesender

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * قارئ بسيط لملفات Excel (.xlsx) دون الحاجة لمكتبات ثقيلة مثل Apache POI،
 * والتي تُعرف بمشاكل توافق شائعة على أندرويد (تعتمد على java.awt وأشياء غير
 * متوفرة في بيئة أندرويد). يكفي هذا القارئ لقراءة الورقة الأولى من ملف بسيط
 * (اسم / إيميل / جوال)، وهذا كل ما يحتاجه هذا التطبيق.
 */
object XlsxReader {

    /** يعيد قائمة صفوف؛ كل صف عبارة عن قائمة نصوص بحسب ترتيب الأعمدة (A, B, C ...) */
    fun readFirstSheet(context: Context, uri: Uri): List<List<String>> {
        val zipBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return emptyList()

        var sharedStrings: List<String> = emptyList()
        var sheetBytes: ByteArray? = null

        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                when {
                    entry.name == "xl/sharedStrings.xml" -> {
                        sharedStrings = parseSharedStrings(zis)
                    }
                    entry.name == "xl/worksheets/sheet1.xml" -> {
                        sheetBytes = zis.readBytes()
                    }
                }
                entry = zis.nextEntry
            }
        }

        // بعض الملفات المُصدَّرة من برامج أخرى قد تسمي الورقة الأولى باسم مختلف
        if (sheetBytes == null) {
            ZipInputStream(zipBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml")) {
                        sheetBytes = zis.readBytes()
                        break
                    }
                    entry = zis.nextEntry
                }
            }
        }

        val bytes = sheetBytes ?: return emptyList()
        return parseSheet(bytes, sharedStrings)
    }

    private fun parseSharedStrings(input: InputStream): List<String> {
        val result = mutableListOf<String>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")

        var event = parser.eventType
        var insideSi = false
        val currentText = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "si") {
                        insideSi = true
                        currentText.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideSi) currentText.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") {
                        result.add(currentText.toString())
                        insideSi = false
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseSheet(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        var event = parser.eventType
        var currentRow = mutableListOf<String>()
        var currentColIndex = -1
        var cellType: String? = null
        var insideValue = false
        val valueBuffer = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> currentRow = mutableListOf()
                        "c" -> {
                            cellType = parser.getAttributeValue(null, "t")
                            currentColIndex = columnLetterToIndex(parser.getAttributeValue(null, "r"))
                        }
                        "v", "t" -> {
                            insideValue = true
                            valueBuffer.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideValue) valueBuffer.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "v", "t" -> insideValue = false
                        "c" -> {
                            val raw = valueBuffer.toString()
                            val text = if (cellType == "s") {
                                raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                            } else {
                                raw
                            }
                            if (currentColIndex >= 0) {
                                while (currentRow.size <= currentColIndex) currentRow.add("")
                                currentRow[currentColIndex] = text
                            }
                            cellType = null
                        }
                        "row" -> rows.add(currentRow)
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    /** يحوّل مرجع خلية مثل "C5" إلى فهرس عمود يبدأ من صفر (C = 2) */
    private fun columnLetterToIndex(cellRef: String?): Int {
        if (cellRef.isNullOrEmpty()) return 0
        var index = 0
        for (ch in cellRef) {
            if (ch.isLetter()) {
                index = index * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else {
                break
            }
        }
        return index - 1
    }
}
