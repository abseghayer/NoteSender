package com.company.notesender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.company.notesender.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private enum class IntentChoice {
        SEND, SKIP, CANCEL
    }

    private var resumeCallback: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        resumeCallback?.invoke()
        resumeCallback = null
    }

    private suspend fun waitForResume() = suspendCancellableCoroutine<Unit> { continuation ->
        resumeCallback = {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
    }

    private fun updateEmailUiSettings() {
        val isEmailChecked = binding.cbEmail.isChecked
        binding.layoutEmailOptions.visibility = if (isEmailChecked) View.VISIBLE else View.GONE
        
        if (isEmailChecked && binding.rbEmailSmtp.isChecked) {
            binding.cardSmtpSettings.visibility = View.VISIBLE
        } else {
            binding.cardSmtpSettings.visibility = View.GONE
        }
    }

    private suspend fun showEmailIntentDialog(name: String, email: String, subject: String, body: String): IntentChoice = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(this)
            .setTitle("إرسال بريد إلكتروني (Outlook)")
            .setMessage("من فضلك اضغط على زر الإرسال لفتح تطبيق البريد الإلكتروني وإرسال الرسالة إلى:\n\nالاسم: ${name.ifBlank { "بدون اسم" }}\nالبريد: $email\n\nبعد إرسال الرسالة أو إغلاق تطبيق البريد، يرجى العودة إلى هذا التطبيق للمتابعة تلقائياً.")
            .setPositiveButton("فتح تطبيق البريد ✉️") { dialogInterface, _ ->
                dialogInterface.dismiss()
                if (continuation.isActive) continuation.resume(IntentChoice.SEND)
            }
            .setNeutralButton("تخطي") { dialogInterface, _ ->
                dialogInterface.dismiss()
                if (continuation.isActive) continuation.resume(IntentChoice.SKIP)
            }
            .setNegativeButton("إلغاء العملية") { dialogInterface, _ ->
                dialogInterface.dismiss()
                if (continuation.isActive) continuation.resume(IntentChoice.CANCEL)
            }
            .setCancelable(false)
            .create()

        dialog.show()

        continuation.invokeOnCancellation {
            dialog.dismiss()
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: SecureStorage

    private var excelUri: Uri? = null
    private var isSending = false

    private val pickExcelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // بعض مزودي الملفات لا يدعمون صلاحية دائمة، لا مشكلة، سيعمل الملف لهذه الجلسة
                }
                excelUri = uri
                storage.excelUri = uri.toString()
                binding.tvExcelPath.text = "✓ " + (queryFileName(uri) ?: uri.lastPathSegment)
                log("تم اختيار ملف: ${queryFileName(uri) ?: uri}")
            }
        }

    private val requestSmsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginSending()
            } else {
                Toast.makeText(this, "لا يمكن إرسال SMS بدون منح الصلاحية", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = SecureStorage(this)

        // استرجاع الإعدادات المحفوظة من آخر استخدام
        binding.etSmtpHost.setText(storage.smtpHost)
        binding.etSmtpPort.setText(storage.smtpPort)
        binding.etSenderEmail.setText(storage.senderEmail)
        binding.etSenderPassword.setText(storage.senderPassword)

        if (storage.emailMode == "smtp") {
            binding.rbEmailSmtp.isChecked = true
        } else {
            binding.rbEmailIntent.isChecked = true
        }
        updateEmailUiSettings()

        binding.cbEmail.setOnCheckedChangeListener { _, _ -> updateEmailUiSettings() }
        binding.rgEmailMode.setOnCheckedChangeListener { _, _ -> updateEmailUiSettings() }

        // التأكد من أن ملف الإكسل المحفوظ من آخر مرة ما زال موجوداً ويمكن فتحه فعلياً
        restoreLastExcelFileIfAvailable()

        binding.btnPickExcel.setOnClickListener {
            pickExcelLauncher.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "*/*"
                )
            )
        }

        binding.btnStart.setOnClickListener { onStartClicked() }
    }

    private fun restoreLastExcelFileIfAvailable() {
        val savedUriStr = storage.excelUri ?: return
        val uri = Uri.parse(savedUriStr)
        try {
            contentResolver.openInputStream(uri)?.close()
            excelUri = uri
            binding.tvExcelPath.text = "✓ " + (queryFileName(uri) ?: uri.lastPathSegment)
        } catch (_: Exception) {
            // الملف لم يعد موجوداً في مكانه أو فقدنا صلاحية الوصول إليه
            storage.excelUri = null
            binding.tvExcelPath.text = "الملف المحفوظ سابقاً لم يعد متاحاً، يرجى اختيار ملف جديد"
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun onStartClicked() {
        if (isSending) return

        val uri = excelUri
        if (uri == null) {
            Toast.makeText(this, "يرجى تحديد ملف الإكسل أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        val sendEmail = binding.cbEmail.isChecked
        val sendSms = binding.cbSms.isChecked
        if (!sendEmail && !sendSms) {
            Toast.makeText(this, "يرجى اختيار طريقة إرسال واحدة على الأقل", Toast.LENGTH_SHORT).show()
            return
        }

        val useSmtp = sendEmail && binding.rbEmailSmtp.isChecked
        if (useSmtp) {
            if (binding.etSenderEmail.text.isBlank() || binding.etSenderPassword.text.isBlank() ||
                binding.etSmtpHost.text.isBlank() || binding.etSmtpPort.text.isBlank()
            ) {
                Toast.makeText(this, "يرجى إكمال إعدادات SMTP أولاً", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // حفظ الإعدادات للاستخدام القادم
        storage.smtpHost = binding.etSmtpHost.text.toString().trim()
        storage.smtpPort = binding.etSmtpPort.text.toString().trim()
        storage.senderEmail = binding.etSenderEmail.text.toString().trim()
        storage.senderPassword = binding.etSenderPassword.text.toString()
        storage.emailMode = if (binding.rbEmailSmtp.isChecked) "smtp" else "intent"

        AlertDialog.Builder(this)
            .setTitle("تأكيد الإرسال")
            .setMessage("سيتم إرسال الملاحظة لكل شخص موجود في الملف. هل تريد المتابعة؟")
            .setPositiveButton("متابعة") { _, _ ->
                if (sendSms && ContextCompat.checkSelfPermission(
                        this, Manifest.permission.SEND_SMS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                } else {
                    beginSending()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun beginSending() {
        val uri = excelUri ?: return
        val sendEmail = binding.cbEmail.isChecked
        val sendSms = binding.cbSms.isChecked
        val subject = binding.etSubject.text.toString().ifBlank { "ملاحظة" }
        val messageTemplate = binding.etMessage.text.toString()

        val useSmtp = sendEmail && binding.rbEmailSmtp.isChecked
        val mailer = if (useSmtp) {
            SmtpMailer(
                host = binding.etSmtpHost.text.toString().trim(),
                port = binding.etSmtpPort.text.toString().trim(),
                senderEmail = binding.etSenderEmail.text.toString().trim(),
                senderPassword = binding.etSenderPassword.text.toString()
            )
        } else null

        isSending = true
        binding.btnStart.isEnabled = false
        binding.progressBar.progress = 0
        binding.tvLog.text = ""
        binding.cardLogs.visibility = View.VISIBLE

        lifecycleScope.launch {
            var sentCount = 0
            var skippedCount = 0
            var total = 0

            try {
                log("جاري قراءة ملف الإكسل...")
                val rows = withContext(Dispatchers.IO) { XlsxReader.readFirstSheet(this@MainActivity, uri) }
                val recipients = ContactParser.parse(rows)
                total = recipients.size

                if (total == 0) {
                    log("لم يتم العثور على بيانات صالحة في الملف.")
                    Toast.makeText(this@MainActivity, "الملف فارغ أو غير صالح", Toast.LENGTH_LONG).show()
                    return@launch
                }

                log("تم العثور على $total شخص. جاري الإرسال...")

                for ((index, person) in recipients.withIndex()) {
                    try {
                        val personalMessage = messageTemplate.replace("{name}", person.name.ifBlank { "" })
                        var didSomething = false

                        if (sendEmail) {
                            if (person.email.contains("@")) {
                                if (useSmtp) {
                                    withContext(Dispatchers.IO) {
                                        mailer?.send(person.email, subject, personalMessage)
                                    }
                                    didSomething = true
                                } else {
                                    // Intent Mode (Outlook)
                                    val choice = showEmailIntentDialog(person.name, person.email, subject, personalMessage)
                                    when (choice) {
                                        IntentChoice.SEND -> {
                                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                                data = Uri.parse("mailto:")
                                                putExtra(Intent.EXTRA_EMAIL, arrayOf(person.email))
                                                putExtra(Intent.EXTRA_SUBJECT, subject)
                                                putExtra(Intent.EXTRA_TEXT, personalMessage)
                                            }
                                            try {
                                                startActivity(Intent.createChooser(intent, "اختر تطبيق البريد (Outlook)"))
                                                log("تم فتح تطبيق البريد لـ: ${person.name.ifBlank { "بدون اسم" }} (${person.email})")
                                                didSomething = true

                                                // ننتظر عودة المستخدم للتطبيق
                                                waitForResume()
                                            } catch (e: Exception) {
                                                log("خطأ في فتح تطبيق البريد لـ ${person.name.ifBlank { "بدون اسم" }}: ${e.message}")
                                            }
                                        }
                                        IntentChoice.SKIP -> {
                                            log("تم تخطي إرسال البريد لـ: ${person.name.ifBlank { "بدون اسم" }}")
                                        }
                                        IntentChoice.CANCEL -> {
                                            log("تم إلغاء عملية الإرسال بطلب من المستخدم.")
                                            break
                                        }
                                    }
                                }
                            } else {
                                log("تخطي بريد ${person.name.ifBlank { "بدون اسم" }}: لا يوجد إيميل صالح")
                            }
                        }

                        if (sendSms) {
                            if (person.phone.isNotEmpty()) {
                                withContext(Dispatchers.IO) {
                                    SmsSender.send(person.phone, personalMessage)
                                }
                                didSomething = true
                            } else {
                                log("تخطي SMS لـ ${person.name.ifBlank { "بدون اسم" }}: لا يوجد رقم جوال")
                            }
                        }

                        if (didSomething) {
                            sentCount++
                            if (useSmtp || !sendEmail) {
                                log("تم الإرسال إلى: ${person.name.ifBlank { "بدون اسم" }}")
                            }
                        } else {
                            skippedCount++
                        }
                    } catch (rowError: Exception) {
                        skippedCount++
                        log("خطأ أثناء إرسال ملاحظة ${person.name.ifBlank { "بدون اسم" }}: ${rowError.message}")
                    } finally {
                        binding.progressBar.progress = ((index + 1) * 100) / total
                    }
                }

                log("اكتملت العملية! تم التعامل مع $sentCount من أصل $total (تم تجاوز $skippedCount).")
                Toast.makeText(this@MainActivity, "تم إرسال $sentCount ملاحظة بنجاح", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                log("حدث خطأ عام أثناء الإرسال: ${e.message}")
                Toast.makeText(this@MainActivity, "حدث خطأ: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isSending = false
                binding.btnStart.isEnabled = true
            }
        }
    }

    private fun log(message: String) {
        binding.tvLog.append("• $message\n")
        binding.mainScrollView.post {
            binding.mainScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
