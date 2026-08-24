package com.company.notesender

import android.telephony.SmsManager

/** إرسال رسالة SMS، مع دعم الرسائل الطويلة عبر تقسيمها تلقائياً (multipart). */
object SmsSender {

    @Throws(Exception::class)
    fun send(phoneNumber: String, message: String) {
        val smsManager = SmsManager.getDefault()
        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
    }
}
