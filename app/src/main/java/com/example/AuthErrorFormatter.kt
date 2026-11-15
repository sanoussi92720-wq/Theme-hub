package com.example

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException

object AuthErrorFormatter {
    fun format(throwable: Throwable): String {
        val chain = generateSequence(throwable) { it.cause }.toList()

        val firestoreEx = chain.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        if (firestoreEx != null) {
            val kebabCode = firestoreEx.code.name.lowercase().replace('_', '-')
            val detail = firestoreEx.localizedMessage ?: firestoreEx.message ?: "Firestore error"
            val hint = if (firestoreEx.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                "\nتلميح: تأكد من لصق ونشر قواعد firestore.rules في لوحة تحكم Firebase (Firestore Database -> Rules -> Publish) لمشروع theme-hub-baad8."
            } else {
                ""
            }
            return "فشل التحقق في Firestore [$kebabCode]: $detail$hint"
        }

        val authEx = chain.filterIsInstance<FirebaseAuthException>().firstOrNull()
        if (authEx != null) {
            val rawCode = authEx.errorCode
            val webStyleCode = "auth/${rawCode.removePrefix("ERROR_").lowercase().replace('_', '-')}"
            val detail = authEx.localizedMessage ?: authEx.message ?: "Firebase Auth error"
            return "فشل مصادقة Firebase [$webStyleCode | $rawCode]: $detail"
        }

        val apiEx = chain.filterIsInstance<ApiException>().firstOrNull()
        if (apiEx != null) {
            val hint = when (apiEx.statusCode) {
                10 -> " — Developer Error: بصمة SHA-1 أو معرف Package Name غير مطابق في Firebase Console / google-services.json"
                12500 -> " — Sign-In Failed: تأكد من تفعيل Google Sign-In وإعداد OAuth Consent Screen في Firebase/Google Cloud"
                7 -> " — خطأ في الشبكة"
                16 -> " — تم إلغاء أو حظر الطلب"
                else -> ""
            }
            return "فشل Google Sign-In [ApiException ${apiEx.statusCode}]$hint\n${apiEx.localizedMessage ?: apiEx.message ?: ""}".trim()
        }

        if (throwable is GetCredentialCancellationException) {
            return "تم إلغاء تسجيل الدخول من قِبل المستخدم [GetCredentialCancellationException]"
        }
        if (throwable is NoCredentialException) {
            return "لا توجد حسابات Google متاحة على هذا الجهاز [NoCredentialException]: ${throwable.errorMessage ?: "أضف حساب Google في إعدادات الهاتف"}"
        }

        val credEx = chain.filterIsInstance<GetCredentialException>().firstOrNull()
        if (credEx != null) {
            val rawMsg = credEx.errorMessage?.toString() ?: credEx.localizedMessage ?: credEx.toString()
            val detectedStatus = Regex("""\[(\d+)]|statusCode=(\d+)|ApiException:\s*(\d+)""")
                .find(rawMsg)
                ?.groupValues
                ?.drop(1)
                ?.firstOrNull { it.isNotEmpty() }
            val statusLabel = if (detectedStatus != null) "ApiException $detectedStatus | " else ""
            val configHint = if (detectedStatus == "10" || detectedStatus == "28444" || rawMsg.contains("Developer console", ignoreCase = true)) {
                "\nتلميح: تأكد من إضافة SHA-1 و SHA-256 للمفتاح الدائم في إعدادات مشروع Firebase."
            } else if (detectedStatus == "12500") {
                "\nتلميح: تأكد من إعداد OAuth Consent Screen وربط Web Client ID الصحيح."
            } else {
                ""
            }
            return "فشل Credential Manager [$statusLabel${credEx.javaClass.simpleName} / ${credEx.type}]: $rawMsg$configHint"
        }

        if (chain.any { it is FirebaseNetworkException }) {
            return "خطأ شبكة Firebase [auth/network-request-failed]: ${throwable.localizedMessage ?: "تعذر الاتصال بخوادم Google"}"
        }

        val root = chain.lastOrNull() ?: throwable
        val rootInfo = if (root !== throwable) " -> ${root.javaClass.simpleName}: ${root.localizedMessage ?: ""}" else ""
        return "خطأ في تسجيل الدخول [${throwable.javaClass.simpleName}]: ${throwable.localizedMessage ?: throwable.toString()}$rootInfo"
    }
}
