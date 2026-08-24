package com.company.notesender

import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * إرسال بريد إلكتروني مباشرة عبر SMTP دون الحاجة لتطبيق بريد أو تدخل يدوي،
 * تماماً كما كان برنامج الويندوز يرسل تلقائياً عبر Outlook.
 *
 * ملاحظة أمنية: استخدم "كلمة مرور تطبيق" (App Password) من مزوّد البريد
 * (Gmail / Outlook / ...) وليس كلمة مرور الحساب الرئيسية، حتى لو تسرّبت
 * كلمة المرور المخزّنة في الجهاز، لا تُستخدم للوصول الكامل للحساب.
 */
class SmtpMailer(
    private val host: String,
    private val port: String,
    private val senderEmail: String,
    private val senderPassword: String
) {
    private val session: Session by lazy {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", host)
            put("mail.smtp.port", port)
            put("mail.smtp.ssl.trust", host)
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
        }
        Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(senderEmail, senderPassword)
            }
        })
    }

    @Throws(Exception::class)
    fun send(toEmail: String, subject: String, bodyText: String) {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(senderEmail))
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
        message.setSubject(subject, "UTF-8")

        // dir='rtl' يضمن عرض النص العربي بمحاذاة واتجاه صحيحين في عملاء
        // البريد التي تدعم HTML (Outlook، Gmail، ...)
        val htmlBody = "<div dir='rtl' style='font-family:Arial,sans-serif;font-size:14px;" +
                "line-height:1.6;color:#222;'>" + bodyText.replace("\n", "<br>") + "</div>"

        message.setContent(htmlBody, "text/html; charset=UTF-8")
        Transport.send(message)
    }
}
