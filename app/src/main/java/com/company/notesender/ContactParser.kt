package com.company.notesender

/**
 * يحوّل صفوف الإكسل الخام إلى قائمة أشخاص، عبر اكتشاف أعمدة الاسم والإيميل
 * والجوال تلقائياً من الصف الأول (رأس الجدول)، بنفس فكرة اكتشاف الأعمدة
 * المستخدمة في نسخة الويندوز، لكن مبسّطة أكثر.
 */
object ContactParser {

    private val nameKeywords = listOf("الاسم", "اسم", "NAME")
    private val emailKeywords = listOf("الإيميل", "الايميل", "البريد", "EMAIL")
    private val phoneKeywords = listOf("الجوال", "الهاتف", "رقم الجوال", "الموبايل", "PHONE", "MOBILE")

    fun parse(rows: List<List<String>>): List<Recipient> {
        if (rows.size < 2) return emptyList()

        val header = rows.first().map { it.trim() }
        val nameCol = findColumn(header, nameKeywords) ?: 0
        val emailCol = findColumn(header, emailKeywords)
        val phoneCol = findColumn(header, phoneKeywords)

        val result = mutableListOf<Recipient>()
        for (row in rows.drop(1)) {
            val name = row.getOrNull(nameCol)?.trim().orEmpty()
            val email = emailCol?.let { row.getOrNull(it)?.trim().orEmpty() }.orEmpty()
            val phoneRaw = phoneCol?.let { row.getOrNull(it)?.trim().orEmpty() }.orEmpty()
            val phone = normalizePhone(phoneRaw)

            if (name.isNotEmpty() || email.isNotEmpty() || phone.isNotEmpty()) {
                result.add(Recipient(name, email, phone))
            }
        }
        return result
    }

    private fun findColumn(header: List<String>, keywords: List<String>): Int? {
        header.forEachIndexed { index, title ->
            val upper = title.uppercase()
            if (keywords.any { upper.contains(it.uppercase()) }) return index
        }
        return null
    }

    /** ينظّف رقم الجوال من الصيغة العلمية أو الفاصلة العشرية التي قد يخزّنها
     * Excel تلقائياً للأرقام الطويلة إذا كان العمود منسّقاً كرقم لا كنص. */
    private fun normalizePhone(raw: String): String {
        if (raw.isEmpty()) return raw
        val looksNumericFloat = raw.contains("E") || raw.contains("e") || raw.contains(".")
        val asDouble = raw.toDoubleOrNull()
        return if (looksNumericFloat && asDouble != null) {
            asDouble.toLong().toString()
        } else {
            raw.filter { it.isDigit() || it == '+' }
        }
    }
}
