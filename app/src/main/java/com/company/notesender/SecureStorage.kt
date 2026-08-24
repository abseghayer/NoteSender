package com.company.notesender

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * تخزين إعدادات SMTP وآخر ملف تم اختياره بشكل مُشفَّر على الجهاز، بدل
 * تخزينها كنص عادي، خصوصاً أن كلمة مرور البريد تُحفظ لتُستخدم تلقائياً
 * بدون تدخل المستخدم في كل مرة.
 */
class SecureStorage(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var excelUri: String?
        get() = prefs.getString("excel_uri", null)
        set(value) = prefs.edit().putString("excel_uri", value).apply()

    var smtpHost: String
        get() = prefs.getString("smtp_host", "smtp.gmail.com") ?: "smtp.gmail.com"
        set(value) = prefs.edit().putString("smtp_host", value).apply()

    var smtpPort: String
        get() = prefs.getString("smtp_port", "587") ?: "587"
        set(value) = prefs.edit().putString("smtp_port", value).apply()

    var senderEmail: String
        get() = prefs.getString("sender_email", "") ?: ""
        set(value) = prefs.edit().putString("sender_email", value).apply()

    var senderPassword: String
        get() = prefs.getString("sender_password", "") ?: ""
        set(value) = prefs.edit().putString("sender_password", value).apply()
}
