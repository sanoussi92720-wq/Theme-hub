package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import java.util.Locale
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private var inMemoryGithubToken: String? = null
    private var webViewReference: WebView? = null
    private var pendingAuthJs: String? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var isInteractiveSignInRunning = false
    private var lastVerifiedUid: String? = null
    private var currentCommunityProfile: UserProfileData? = null
    private var inboxListener: ListenerRegistration? = null
    private var activeChatListener: ListenerRegistration? = null
    internal var pendingDeepLinkThemeId: String? = null

    private var pendingSafKey: String = ""
    private var pendingSafBrand: String = "Honor"
    private var pendingSafFilename: String = ""

    internal val downloadHelper: ThemeDownloadHelper by lazy { ThemeDownloadHelper(this) }
    internal val communityRepo: FirebaseCommunityRepository by lazy {
        FirebaseCommunityRepository(Firebase.firestore, Firebase.auth)
    }

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        if (isInteractiveSignInRunning) return@AuthStateListener
        if (user == null) {
            clearSessionListeners()
            lastVerifiedUid = null
            currentCommunityProfile = null
            inMemoryGithubToken = null
            sendAuthIdleToJs()
            sendCommunityAuthIdleToJs()
        } else if (lastVerifiedUid != user.uid) {
            verifyAndRestoreUserSession(user)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Download continues cleanly with in-app progress even if denied */ }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@registerForActivityResult

        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = result.data!!
            val clipData = intent.clipData
            when {
                clipData != null && clipData.itemCount > 0 ->
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                intent.data != null -> arrayOf(intent.data!!)
                else -> null
            }
        } else {
            null
        }
        callback.onReceiveValue(uris)
    }

    private val safFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) {
            sendSafCopyResultToJs(
                success = false,
                actualPath = "",
                message = "تم إلغاء اختيار مجلد الثيمات"
            )
            return@registerForActivityResult
        }
        downloadHelper.saveTreeUri(pendingSafBrand, treeUri)
        if (pendingSafKey.isNotBlank() && pendingSafFilename.isNotBlank()) {
            performCopyToSafTree(pendingSafKey, treeUri, pendingSafFilename)
        } else {
            sendSafCopyResultToJs(
                success = true,
                actualPath = treeUri.toString(),
                message = "تم حفظ مجلد الثيمات بنجاح"
            )
        }
    }

    private val localThemePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val res = downloadHelper.importLocalThemeUri(uri)
            res.onSuccess { info ->
                evaluateJs("window.onLocalThemeImported?.($info);")
                sendToastToJs("تم استيراد ${info.optString("filename")} بنجاح!")
            }.onFailure { err ->
                val payload = JSONObject().apply {
                    put("success", false)
                    put("error", err.localizedMessage ?: err.message ?: "تعذر استيراد الملف")
                }.toString()
                evaluateJs("window.onLocalThemeImported?.($payload);")
                sendToastToJs("فشل الاستيراد: ${err.localizedMessage ?: err.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        (application as? ThemeHubApplication)?.ensureFirebaseInitialized()
        extractDeepLinkThemeId(intent)?.let { pendingDeepLinkThemeId = it }
        setContent {
            ThemeHubApp(
                onWebViewReady = { wv ->
                    webViewReference = wv
                    flushPendingAuthJs(wv)
                },
                onExitApp = { finish() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val deepLinkId = extractDeepLinkThemeId(intent)
        if (!deepLinkId.isNullOrBlank()) {
            pendingDeepLinkThemeId = deepLinkId
            dispatchPendingDeepLinkToJs()
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { Firebase.auth.addAuthStateListener(authStateListener) }
    }

    override fun onPause() {
        super.onPause()
        evaluateJs("window.pauseAllPreviewVideos?.();")
    }

    override fun onStop() {
        super.onStop()
        runCatching { Firebase.auth.removeAuthStateListener(authStateListener) }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearSessionListeners()
        inMemoryGithubToken = null
    }

    private fun clearSessionListeners() {
        runCatching { inboxListener?.remove() }
        inboxListener = null
        runCatching { activeChatListener?.remove() }
        activeChatListener = null
    }

    internal fun extractDeepLinkThemeId(intent: Intent?): String? {
        val data = intent?.data ?: return null
        val fromQuery = data.getQueryParameter("theme")?.trim()
        if (!fromQuery.isNullOrEmpty()) return fromQuery
        if (data.scheme == "themehub" && data.host == "theme") {
            val seg = data.lastPathSegment?.trim()
            if (!seg.isNullOrEmpty()) return seg
        }
        return null
    }

    private fun dispatchPendingDeepLinkToJs() {
        val id = pendingDeepLinkThemeId ?: return
        val quoted = JSONObject.quote(id)
        evaluateJs("window.openThemeByDeepLink?.($quoted);")
    }

    internal fun onWebViewLoaded(wv: WebView?) {
        if (wv != null) {
            webViewReference = wv
            flushPendingAuthJs(wv)
            restoreAdminSessionOnStartup()
            fetchAndSendAllThemeRatings()
            dispatchPendingDeepLinkToJs()
        }
    }

    private fun flushPendingAuthJs(wv: WebView) {
        val pending = pendingAuthJs ?: return
        pendingAuthJs = null
        wv.post {
            wv.evaluateJavascript(pending, null)
        }
    }

    private fun evaluateJs(script: String) {
        runOnUiThread {
            val wv = webViewReference
            if (wv != null) {
                wv.post { wv.evaluateJavascript(script, null) }
            } else {
                pendingAuthJs = script
            }
        }
    }

    internal fun buildFileChooserIntent(
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Pair<Intent, String> {
        val rawTokens = fileChooserParams?.acceptTypes
            ?.flatMap { it.split(",") }
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val hasImage = rawTokens.any {
            it.contains("image") || it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp")
        }
        val hasVideo = rawTokens.any {
            it.contains("video") || it.endsWith(".mp4") || it.endsWith(".webm")
        }

        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            when {
                hasImage && hasVideo -> {
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("image/png", "image/jpeg", "image/webp", "video/mp4", "video/webm")
                    )
                }
                hasVideo -> {
                    type = "video/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/mp4", "video/webm"))
                }
                hasImage -> {
                    type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp"))
                }
                else -> {
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf(
                            "application/octet-stream",
                            "application/zip",
                            "application/vnd.android.package-archive",
                            "application/x-zip-compressed",
                            "*/*"
                        )
                    )
                }
            }
            if (fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        val title = when {
            hasImage && hasVideo -> "اختر وسائط المعاينة (صور وفيديو)"
            hasVideo -> "اختر فيديو المعاينة"
            hasImage -> "اختر صور المعاينة"
            else -> "اختر ملف الثيم"
        }
        return intent to title
    }

    fun openFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = filePathCallback

        return try {
            val (intent, title) = buildFileChooserIntent(fileChooserParams)
            fileChooserLauncher.launch(Intent.createChooser(intent, title))
            true
        } catch (e: Exception) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            false
        }
    }

    fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    internal fun resolveWebClientId(): String {
        val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
        val fromRes = if (resId != 0) runCatching { getString(resId).trim() }.getOrDefault("") else ""
        if (fromRes.isNotEmpty()) return fromRes
        return BuildConfig.DEFAULT_WEB_CLIENT_ID.trim()
    }

    fun restoreAdminSessionOnStartup() {
        val currentUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (currentUser == null) {
            clearSessionListeners()
            lastVerifiedUid = null
            currentCommunityProfile = null
            inMemoryGithubToken = null
            sendAuthIdleToJs()
            sendCommunityAuthIdleToJs()
            return
        }
        verifyAndRestoreUserSession(currentUser)
    }

    private fun verifyAndRestoreUserSession(user: FirebaseUser) {
        val email = user.email?.trim()?.lowercase().orEmpty()
        sendAuthRestoringToJs(email)

        lifecycleScope.launch {
            try {
                // Force-refresh ID token if online, fallback to cached token if offline
                runCatching { user.getIdToken(isOnline()).await() }
                verifySessionAndProfile(user, isAdminGateAttempt = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("ThemeHubAuth", "Restore session failed", e)
                sendCommunityAuthErrorToJs(AuthErrorFormatter.format(e))
                sendAuthIdleToJs()
            }
        }
    }

    fun startGoogleSignInFlow(isAdminGateAttempt: Boolean = true) {
        if (!isOnline()) {
            val msg = "خطأ اتصال [network-unavailable]: لا يوجد اتصال بالإنترنت، يرجى التحقق من الشبكة."
            if (isAdminGateAttempt) sendAuthErrorToJs(msg) else sendCommunityAuthErrorToJs(msg)
            return
        }

        isInteractiveSignInRunning = true
        lifecycleScope.launch {
            var authCallbackDispatched = false
            try {
                if (FirebaseApp.getApps(this@MainActivity).isEmpty()) {
                    (application as? ThemeHubApplication)?.ensureFirebaseInitialized()
                        ?: FirebaseApp.initializeApp(this@MainActivity)
                }

                val serverClientId = resolveWebClientId()
                if (serverClientId.isEmpty()) {
                    authCallbackDispatched = true
                    val msg = "خطأ في الإعداد [missing-web-client-id]: لم يتم العثور على default_web_client_id من google-services.json"
                    if (isAdminGateAttempt) sendAuthErrorToJs(msg) else sendCommunityAuthErrorToJs(msg)
                    return@launch
                }

                val credentialManager = CredentialManager.create(this@MainActivity)
                val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = serverClientId).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

                val result = credentialManager.getCredential(this@MainActivity, request)
                val credential = result.credential

                if (credential !is CustomCredential || credential.type != TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    authCallbackDispatched = true
                    val msg = "فشل تسجيل الدخول [unsupported-credential]: نوع الاعتماد غير مدعوم (${credential.type})"
                    if (isAdminGateAttempt) sendAuthErrorToJs(msg) else sendCommunityAuthErrorToJs(msg)
                    return@launch
                }

                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)

                val authResult = Firebase.auth.signInWithCredential(authCredential).await()
                val user = authResult.user
                if (user == null) {
                    authCallbackDispatched = true
                    val msg = "فشل تسجيل الدخول [auth/null-user]: اكتمل التحقق دون إرجاع حساب مستخدم."
                    if (isAdminGateAttempt) sendAuthErrorToJs(msg) else sendCommunityAuthErrorToJs(msg)
                    return@launch
                }

                runCatching { user.getIdToken(true).await() }
                authCallbackDispatched = true
                verifySessionAndProfile(user, isAdminGateAttempt = isAdminGateAttempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("ThemeHubAuth", "Google Sign-In failed", e)
                authCallbackDispatched = true
                val formatted = AuthErrorFormatter.format(e)
                if (isAdminGateAttempt) sendAuthErrorToJs(formatted) else sendCommunityAuthErrorToJs(formatted)
            } finally {
                isInteractiveSignInRunning = false
                if (!authCallbackDispatched) {
                    val msg = "انتهت عملية تسجيل الدخول بشكل غير متوقع دون استجابة (unknown-auth-termination)"
                    if (isAdminGateAttempt) sendAuthErrorToJs(msg) else sendCommunityAuthErrorToJs(msg)
                }
            }
        }
    }

    private suspend fun verifySessionAndProfile(
        user: FirebaseUser,
        isAdminGateAttempt: Boolean
    ) {
        val profile = communityRepo.ensureUserProfile(user)
        currentCommunityProfile = profile
        lastVerifiedUid = user.uid
        sendCommunityAuthSuccessToJs(profile)

        val isServerRoleAdmin = profile.role == "admin"
        startInboxListener(user.uid, isServerRoleAdmin)

        val rawEmail = user.email?.trim() ?: ""
        val userEmail = rawEmail.lowercase()
        val firestore = Firebase.firestore

        val isPublisherApproved = if (isServerRoleAdmin) {
            true
        } else if (userEmail.isNotEmpty() && user.isEmailVerified) {
            val lowerExists = runCatching {
                firestore.collection("admins").document(userEmail).get().await().exists()
            }.getOrDefault(false)
            val rawExists = if (!lowerExists && rawEmail != userEmail) {
                runCatching {
                    firestore.collection("admins").document(rawEmail).get().await().exists()
                }.getOrDefault(false)
            } else {
                false
            }
            lowerExists || rawExists
        } else {
            false
        }

        if (!isPublisherApproved) {
            inMemoryGithubToken = null
            if (isAdminGateAttempt) {
                sendAuthErrorToJs(
                    errorMessage = "هذا الحساب مسجل كمستخدم عادي. صلاحية المشرف تتحقق من role = admin في الخادم (Firestore).",
                    errorCode = "not-approved-admin"
                )
            } else {
                sendAuthIdleToJs()
            }
            return
        }

        val configDoc = firestore.collection("config").document("github").get().await()
        val githubToken = if (configDoc.exists()) (configDoc.getString("token")?.trim() ?: "") else ""
        inMemoryGithubToken = githubToken
        sendAuthSuccessToJs(
            token = githubToken,
            name = profile.displayName,
            email = userEmail,
            photo = profile.photoURL,
            isOwner = isServerRoleAdmin,
            hasToken = githubToken.isNotBlank()
        )
    }

    private fun startInboxListener(uid: String, isAdmin: Boolean) {
        runCatching { inboxListener?.remove() }
        inboxListener = communityRepo.observeInboxAndUnread(uid, isAdmin) { payload ->
            evaluateJs("window.onInboxAndUnreadUpdated?.($payload);")
        }
    }

    private suspend fun verifyCurrentUserServerAdminRole(): Boolean {
        val currentUser = Firebase.auth.currentUser ?: return false
        val snap = runCatching {
            Firebase.firestore.collection("users").document(currentUser.uid).get().await()
        }.getOrNull() ?: return false
        return snap.getString("role") == "admin"
    }

    fun saveGithubTokenToFirestore(token: String) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) {
            sendToastToJs("يرجى إدخال رمز توكن صالح")
            return
        }
        val user = Firebase.auth.currentUser
        if (user == null) {
            sendToastToJs("يرجى تسجيل الدخول أولاً")
            return
        }

        lifecycleScope.launch {
            if (!verifyCurrentUserServerAdminRole()) {
                sendToastToJs("غير مصرح: حفظ التوكن متاح للمشرفين (role = admin) فقط")
                return@launch
            }

            val data = hashMapOf<String, Any>(
                "token" to cleanToken,
                "updatedAt" to System.currentTimeMillis(),
                "updatedBy" to (user.uid)
            )

            Firebase.firestore.collection("config").document("github")
                .set(data)
                .addOnSuccessListener {
                    inMemoryGithubToken = cleanToken
                    sendToastToJs("تم حفظ توكن GitHub بنجاح في Firebase")
                    val jsToken = JSONObject.quote(cleanToken)
                    evaluateJs("window.onGithubTokenSaved?.(true, $jsToken);")
                }
                .addOnFailureListener { e ->
                    val detailed = AuthErrorFormatter.format(e)
                    sendToastToJs("فشل حفظ التوكن: $detailed")
                    val jsErr = JSONObject.quote(detailed)
                    evaluateJs("window.onGithubTokenSaved?.(false, $jsErr);")
                }
        }
    }

    fun fetchApprovedAdminsList() {
        lifecycleScope.launch {
            if (!verifyCurrentUserServerAdminRole()) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("error", "غير مصرح [admin-role-only]: إدارة المشرفين تتطلب role = admin في الخادم")
                }.toString()
                evaluateJs("window.onAdminsListLoaded?.($errJson);")
                return@launch
            }
            try {
                val snap = Firebase.firestore.collection("admins").get().await()
                val arr = JSONArray()
                snap.documents
                    .sortedBy { it.id.lowercase() }
                    .forEach { doc ->
                        val docEmail = (doc.getString("email") ?: doc.id).trim().lowercase()
                        arr.put(JSONObject().apply {
                            put("docId", doc.id)
                            put("email", docEmail)
                            put("addedBy", doc.getString("addedBy") ?: "")
                            put("addedAt", doc.getLong("addedAt") ?: 0L)
                        })
                    }
                val okJson = JSONObject().apply {
                    put("ok", true)
                    put("admins", arr)
                }.toString()
                evaluateJs("window.onAdminsListLoaded?.($okJson);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                evaluateJs("window.onAdminsListLoaded?.($errJson);")
            }
        }
    }

    fun addApprovedAdmin(rawEmail: String) {
        val cleanEmail = rawEmail.replace("\\s+".toRegex(), "").lowercase()
        if (cleanEmail.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            val errJson = JSONObject().apply {
                put("ok", false)
                put("action", "add")
                put("error", "صيغة البريد الإلكتروني غير صالحة [invalid-email]: يرجى إدخال بريد إلكتروني صحيح")
            }.toString()
            evaluateJs("window.onAdminMemberMutation?.($errJson);")
            return
        }

        lifecycleScope.launch {
            if (!verifyCurrentUserServerAdminRole()) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("action", "add")
                    put("error", "غير مصرح [admin-role-only]: إضافة المشرفين تتطلب role = admin")
                }.toString()
                evaluateJs("window.onAdminMemberMutation?.($errJson);")
                return@launch
            }
            try {
                val adminUid = Firebase.auth.currentUser?.uid ?: ""
                val payload = hashMapOf<String, Any>(
                    "email" to cleanEmail,
                    "addedBy" to adminUid,
                    "addedAt" to System.currentTimeMillis()
                )
                Firebase.firestore.collection("admins").document(cleanEmail).set(payload).await()
                val okJson = JSONObject().apply {
                    put("ok", true)
                    put("action", "add")
                    put("email", cleanEmail)
                }.toString()
                evaluateJs("window.onAdminMemberMutation?.($okJson);")
                fetchApprovedAdminsList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("action", "add")
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                evaluateJs("window.onAdminMemberMutation?.($errJson);")
            }
        }
    }

    fun removeApprovedAdmin(rawEmail: String) {
        val cleanEmail = rawEmail.replace("\\s+".toRegex(), "").lowercase()
        val currentEmail = Firebase.auth.currentUser?.email?.trim()?.lowercase() ?: ""
        if (cleanEmail == currentEmail && currentEmail.isNotEmpty()) {
            val errJson = JSONObject().apply {
                put("ok", false)
                put("action", "remove")
                put("error", "لا يمكنك حذف حسابك الشخصي من القائمة")
            }.toString()
            evaluateJs("window.onAdminMemberMutation?.($errJson);")
            return
        }

        lifecycleScope.launch {
            if (!verifyCurrentUserServerAdminRole()) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("action", "remove")
                    put("error", "غير مصرح [admin-role-only]: حذف المشرفين يتطلب role = admin")
                }.toString()
                evaluateJs("window.onAdminMemberMutation?.($errJson);")
                return@launch
            }
            try {
                Firebase.firestore.collection("admins").document(cleanEmail).delete().await()
                val okJson = JSONObject().apply {
                    put("ok", true)
                    put("action", "remove")
                    put("email", cleanEmail)
                }.toString()
                evaluateJs("window.onAdminMemberMutation?.($okJson);")
                fetchApprovedAdminsList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("action", "remove")
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                evaluateJs("window.onAdminMemberMutation?.($errJson);")
            }
        }
    }

    // ONLY place where Firebase.auth.signOut() is ever called
    fun signOutAdmin() {
        clearSessionListeners()
        inMemoryGithubToken = null
        lastVerifiedUid = null
        currentCommunityProfile = null
        lifecycleScope.launch(NonCancellable) {
            try {
                val credentialManager = CredentialManager.create(this@MainActivity)
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) {
            }
            Firebase.auth.signOut()
            sendAuthIdleToJs()
            sendCommunityAuthIdleToJs()
        }
    }

    // -------------------------------------------------------------------------
    // Community Features (Profiles, Ratings, Comments, Reports, Messaging)
    // -------------------------------------------------------------------------
    private fun sendCommunityAuthIdleToJs() {
        val payload = JSONObject().apply {
            put("signedIn", false)
            put("user", JSONObject.NULL)
        }.toString()
        evaluateJs("window.onCommunityAuthChanged?.($payload);")
    }

    private fun sendCommunityAuthSuccessToJs(profile: UserProfileData) {
        val payload = JSONObject().apply {
            put("signedIn", true)
            put("user", profile.toJson())
        }.toString()
        evaluateJs("window.onCommunityAuthChanged?.($payload);")
    }

    private fun sendCommunityAuthErrorToJs(errorMessage: String) {
        val payload = JSONObject().apply {
            put("signedIn", false)
            put("error", errorMessage)
        }.toString()
        evaluateJs("window.onCommunityAuthChanged?.($payload);")
        sendToastToJs(errorMessage.lineSequence().firstOrNull() ?: "تعذر تسجيل الدخول")
    }

    fun fetchUserProfileForJs(uid: String) {
        lifecycleScope.launch {
            val res = communityRepo.getUserProfile(uid)
            val json = JSONObject().apply {
                res.onSuccess { p ->
                    put("ok", true)
                    put("profile", p.toJson())
                }.onFailure { err ->
                    put("ok", false)
                    put("uid", uid)
                    put("error", AuthErrorFormatter.format(err))
                }
            }.toString()
            evaluateJs("window.onCommunityProfileLoaded?.($json);")
        }
    }

    fun fetchPrimaryAdminProfileForJs() {
        lifecycleScope.launch {
            val res = communityRepo.getPrimaryAdminProfile()
            val json = JSONObject().apply {
                res.onSuccess { p ->
                    if (p != null) {
                        put("ok", true)
                        put("profile", p.toJson())
                    } else {
                        put("ok", false)
                        put("error", "لم يتم تعيين حساب مشرف في قاعدة البيانات بعد")
                    }
                }.onFailure { err ->
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(err))
                }
            }.toString()
            evaluateJs("window.onAdminProfileLoaded?.($json);")
        }
    }

    fun updateOwnProfileForJs(displayName: String, bio: String) {
        lifecycleScope.launch {
            val res = communityRepo.updateOwnProfile(displayName, bio)
            val json = JSONObject().apply {
                res.onSuccess { p ->
                    currentCommunityProfile = p
                    put("ok", true)
                    put("profile", p.toJson())
                    sendCommunityAuthSuccessToJs(p)
                }.onFailure { err ->
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(err))
                }
            }.toString()
            evaluateJs("window.onOwnProfileUpdated?.($json);")
        }
    }

    fun setUserBlockedForJs(targetUid: String, blocked: Boolean) {
        lifecycleScope.launch {
            val res = communityRepo.setUserBlocked(targetUid, blocked)
            val json = JSONObject().apply {
                put("targetUid", targetUid)
                put("blocked", blocked)
                res.onSuccess { put("ok", true) }
                    .onFailure { err ->
                        put("ok", false)
                        put("error", AuthErrorFormatter.format(err))
                    }
            }.toString()
            evaluateJs("window.onUserBlockedChanged?.($json);")
        }
    }

    fun fetchAndSendAllThemeRatings() {
        lifecycleScope.launch {
            val res = communityRepo.fetchAllThemeStats()
            val json = JSONObject().apply {
                res.onSuccess { map ->
                    put("ok", true)
                    val statsObj = JSONObject()
                    map.forEach { (themeId, stats) -> statsObj.put(themeId, stats.toJson()) }
                    put("stats", statsObj)
                }.onFailure { err ->
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(err))
                }
            }.toString()
            evaluateJs("window.onAllThemeRatingsLoaded?.($json);")
        }
    }

    fun fetchThemeRatingDetailForJs(themeId: String) {
        lifecycleScope.launch {
            val res = communityRepo.fetchThemeRatingState(themeId)
            val json = JSONObject().apply {
                put("themeId", themeId)
                res.onSuccess { stats ->
                    put("ok", true)
                    put("stats", stats.toJson())
                }.onFailure { err ->
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(err))
                }
            }.toString()
            evaluateJs("window.onThemeRatingDetailLoaded?.($json);")
        }
    }

    fun submitThemeRatingForJs(themeId: String, stars: Int) {
        lifecycleScope.launch {
            val res = communityRepo.submitThemeRating(themeId, stars)
            val json = JSONObject().apply {
                put("themeId", themeId)
                res.onSuccess { stats ->
                    put("ok", true)
                    put("stats", stats.toJson())
                }.onFailure { err ->
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(err))
                }
            }.toString()
            evaluateJs("window.onThemeRatingSubmitted?.($json);")
        }
    }

    fun fetchThemeCommentsForJs(themeId: String, cursorId: String) {
        lifecycleScope.launch {
            val res = communityRepo.fetchCommentsPage(themeId, 20, cursorId.ifBlank { null })
            val json = JSONObject().apply {
                put("themeId", themeId)
                put("isLoadMore", cursorId.isNotBlank())
                res.onSuccess { page ->
                    put("ok", true)
                    put("comments", page.toJson().getJSONArray("comments"))
                    put("hasMore", page.hasMore)
                    put("nextCursorId", page.nextCursorId ?: "")
                }.onFailure { err ->
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(err))
                }
            }.toString()
            evaluateJs("window.onThemeCommentsLoaded?.($json);")
        }
    }

    fun addThemeCommentForJs(themeId: String, text: String) {
        lifecycleScope.launch {
            val res = communityRepo.addComment(themeId, text)
            val json = JSONObject().apply {
                put("themeId", themeId)
                res.onSuccess { c ->
                    put("ok", true)
                    put("comment", c.toJson())
                }.onFailure { err ->
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(err))
                }
            }.toString()
            evaluateJs("window.onThemeCommentAdded?.($json);")
        }
    }

    fun toggleCommentLikeForJs(themeId: String, commentId: String) {
        lifecycleScope.launch {
            val res = communityRepo.toggleCommentLike(themeId, commentId)
            val json = JSONObject().apply {
                put("themeId", themeId)
                put("commentId", commentId)
                res.onSuccess { (likes, likedByMe) ->
                    put("ok", true)
                    put("likes", likes)
                    put("likedByMe", likedByMe)
                }.onFailure { err ->
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(err))
                }
            }.toString()
            evaluateJs("window.onCommentLikeToggled?.($json);")
        }
    }

    fun deleteThemeCommentForJs(themeId: String, commentId: String) {
        lifecycleScope.launch {
            val res = communityRepo.deleteComment(themeId, commentId)
            val json = JSONObject().apply {
                put("themeId", themeId)
                put("commentId", commentId)
                res.onSuccess { put("ok", true) }
                    .onFailure { err ->
                        put("ok", false)
                        put("error", AuthErrorFormatter.format(err))
                    }
            }.toString()
            evaluateJs("window.onThemeCommentDeleted?.($json);")
        }
    }

    fun reportCommunityContentForJs(
        targetType: String,
        targetId: String,
        reportedUid: String,
        reason: String,
        textSnippet: String,
        themeId: String,
        conversationUid: String
    ) {
        lifecycleScope.launch {
            val res = communityRepo.reportContent(
                targetType = targetType,
                targetId = targetId,
                reportedUid = reportedUid,
                reason = reason,
                textSnippet = textSnippet,
                themeId = themeId,
                conversationUid = conversationUid
            )
            val json = JSONObject().apply {
                res.onSuccess { put("ok", true) }
                    .onFailure { err ->
                        put("ok", false)
                        put("error", AuthErrorFormatter.format(err))
                    }
            }.toString()
            evaluateJs("window.onCommunityReportSubmitted?.($json);")
        }
    }

    fun openConversationStreamForJs(conversationUid: String) {
        val currentUser = Firebase.auth.currentUser
        if (currentUser == null) {
            val errJson = JSONObject().apply {
                put("ok", false)
                put("error", "سجّل الدخول أولاً لعرض الرسائل")
            }.toString()
            evaluateJs("window.onConversationMessagesUpdated?.($errJson);")
            return
        }
        val viewerIsAdmin = currentCommunityProfile?.role == "admin"
        val targetUid = if (viewerIsAdmin && conversationUid.isNotBlank()) conversationUid else currentUser.uid

        runCatching { activeChatListener?.remove() }
        activeChatListener = communityRepo.observeConversationMessages(targetUid, viewerIsAdmin) { payload ->
            evaluateJs("window.onConversationMessagesUpdated?.($payload);")
        }
        lifecycleScope.launch {
            communityRepo.markConversationRead(targetUid, viewerIsAdmin)
        }
    }

    fun closeConversationStreamForJs() {
        runCatching { activeChatListener?.remove() }
        activeChatListener = null
    }

    fun sendPrivateMessageForJs(conversationUid: String, text: String) {
        val currentUser = Firebase.auth.currentUser
        if (currentUser == null) {
            val errJson = JSONObject().apply {
                put("ok", false)
                put("error", "سجّل الدخول أولاً لإرسال رسالة")
            }.toString()
            evaluateJs("window.onPrivateMessageSent?.($errJson);")
            return
        }
        lifecycleScope.launch {
            val senderIsAdmin = verifyCurrentUserServerAdminRole()
            val targetUid = if (senderIsAdmin && conversationUid.isNotBlank()) conversationUid else currentUser.uid
            val res = communityRepo.sendMessage(targetUid, senderIsAdmin, text)
            val json = JSONObject().apply {
                put("conversationUid", targetUid)
                res.onSuccess { put("ok", true) }
                    .onFailure { err ->
                        put("ok", false)
                        put("error", AuthErrorFormatter.format(err))
                    }
            }.toString()
            evaluateJs("window.onPrivateMessageSent?.($json);")
        }
    }

    fun startResumableDownload(
        key: String,
        url: String,
        filename: String,
        themeTitle: String,
        brand: String
    ) {
        if (url.isBlank()) {
            sendToastToJs("ملف الثيم غير متوفر حالياً")
            return
        }
        if (!isOnline()) {
            val offlineJson = JSONObject().apply {
                put("key", key)
                put("status", "error")
                put("error", "لا يوجد اتصال بالإنترنت، يرجى التحقق من الشبكة")
            }.toString()
            evaluateJs("window.onThemeDownloadProgress?.($offlineJson);")
            if (key == "app_update_apk") {
                evaluateJs("window.onAppUpdateDownloadProgress?.($offlineJson);")
            }
            sendToastToJs("لا يوجد اتصال بالإنترنت، يرجى التحقق من الشبكة")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val job = lifecycleScope.launch {
            try {
                downloadHelper.downloadThemeResumable(
                    key = key,
                    url = url,
                    filename = filename,
                    themeTitle = themeTitle,
                    brand = brand
                ) { progressJson ->
                    evaluateJs("window.onThemeDownloadProgress?.($progressJson);")
                    if (key == "app_update_apk") {
                        evaluateJs("window.onAppUpdateDownloadProgress?.($progressJson);")
                    }
                }
            } finally {
                downloadHelper.clearJob(key)
            }
        }
        downloadHelper.registerJob(key, job)
    }

    fun openDownloadsFolder() {
        val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(downloadsIntent) }
            .onFailure {
                val filesIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(Intent.createChooser(filesIntent, "مجلد التنزيلات")) }
                    .onFailure {
                        sendToastToJs("افتح تطبيق الملفات ← التنزيلات ← ThemeHub لتثبيت ملف التحديث")
                    }
            }
    }

    fun openExternalBrowserUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            sendToastToJs("رابط غير صالح")
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            sendToastToJs("تعذر فتح المتصفح على هذا الجهاز")
        }
    }

    fun requestCopyToThemesFolder(key: String, brand: String, filename: String, forcePickFolder: Boolean) {
        val (savedUri, _) = downloadHelper.getLastSavedInfo(key)
        if (savedUri == null) {
            sendSafCopyResultToJs(
                success = false,
                actualPath = "",
                message = "يرجى تحميل الثيم إلى Download/ThemeHub أولاً قبل النسخ"
            )
            return
        }

        val existingTree = if (forcePickFolder) null else downloadHelper.getSavedTreeUri(brand)
        if (existingTree == null) {
            pendingSafKey = key
            pendingSafBrand = brand
            pendingSafFilename = filename
            safFolderPickerLauncher.launch(downloadHelper.buildInitialTreeHintUri(brand))
            return
        }

        performCopyToSafTree(key, existingTree, filename)
    }

    private fun performCopyToSafTree(key: String, treeUri: Uri, filename: String) {
        lifecycleScope.launch {
            val res = downloadHelper.copyDownloadedToThemesTree(key, treeUri, filename)
            res.onSuccess { actualPath ->
                sendSafCopyResultToJs(
                    success = true,
                    actualPath = actualPath,
                    message = "تم نسخ الملف بنجاح إلى المسار الفعلي: $actualPath"
                )
            }.onFailure { err ->
                sendSafCopyResultToJs(
                    success = false,
                    actualPath = "",
                    message = "فشل النسخ إلى مجلد الثيمات: ${err.localizedMessage ?: err.message}"
                )
            }
        }
    }

    fun openBrandThemeManager(brand: String): Boolean {
        val pkg = downloadHelper.findThemeManagerPackage(brand)
        if (pkg != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                startActivity(launchIntent)
                return true
            }
        }
        val brandTitle = when (brand.lowercase()) {
            "honor" -> "تطبيق الثيمات (Honor Themes)"
            "huawei" -> "Huawei Themes"
            "xiaomi" -> "تطبيق الثيمات (MIUI/HyperOS)"
            else -> "مدير الثيمات"
        }
        sendToastToJs("تطبيق $brandTitle غير مثبت أو يتعذر فتحه تلقائياً على هذا الجهاز")
        return false
    }

    private fun sendAuthRestoringToJs(email: String) {
        val payload = JSONObject().apply {
            put("status", "restoring")
            put("authenticated", false)
            put("email", email)
        }.toString()
        evaluateJs("window.onAndroidAuthResult?.($payload);")
    }

    private fun sendAuthIdleToJs() {
        val payload = JSONObject().apply {
            put("status", "idle")
            put("authenticated", false)
        }.toString()
        evaluateJs("window.onAndroidAuthResult?.($payload);")
    }

    fun sendAuthSuccessToJs(
        token: String,
        name: String,
        email: String,
        photo: String,
        isOwner: Boolean = false,
        hasToken: Boolean = true
    ) {
        val payload = JSONObject().apply {
            put("status", "success")
            put("authenticated", true)
            put("step", "verified")
            put("token", token)
            put("name", name)
            put("email", email)
            put("photo", photo)
            put("isOwner", isOwner)
            put("hasToken", hasToken)
        }.toString()
        evaluateJs("window.onAndroidAuthResult?.($payload);")
    }

    fun sendAuthErrorToJs(errorMessage: String, errorCode: String = "") {
        val payload = JSONObject().apply {
            put("status", "error")
            put("authenticated", false)
            put("errorCode", errorCode)
            put("errorMessage", errorMessage)
        }.toString()
        evaluateJs("window.onAndroidAuthResult?.($payload);")
    }

    private fun sendSafCopyResultToJs(success: Boolean, actualPath: String, message: String) {
        val payload = JSONObject().apply {
            put("success", success)
            put("actualPath", actualPath)
            put("message", message)
        }.toString()
        evaluateJs("window.onSafFolderCopyResult?.($payload);")
        if (message.isNotBlank()) sendToastToJs(message)
    }

    fun sendToastToJs(message: String) {
        val jsMsg = JSONObject.quote(message)
        webViewReference?.post {
            webViewReference?.evaluateJavascript("window.showToast?.($jsMsg);", null)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun startLocalThemeImport() {
        runCatching {
            localThemePickerLauncher.launch(arrayOf("*/*"))
        }.onFailure {
            sendToastToJs("تعذر فتح مستعرض الملفات على هذا الجهاز")
        }
    }

    fun applyWallpaperFromUrl(imageUrl: String, target: String) {
        if (!isOnline()) {
            val payload = JSONObject().apply {
                put("success", false)
                put("action", "set")
                put("message", "لا يوجد اتصال بالإنترنت لتنزيل الخلفية")
            }.toString()
            evaluateJs("window.onWallpaperResult?.($payload);")
            sendToastToJs("لا يوجد اتصال بالإنترنت")
            return
        }
        lifecycleScope.launch {
            val res = downloadHelper.setWallpaperFromUrl(imageUrl, target)
            res.onSuccess { msg ->
                val payload = JSONObject().apply {
                    put("success", true)
                    put("action", "set")
                    put("target", target)
                    put("message", msg)
                }.toString()
                evaluateJs("window.onWallpaperResult?.($payload);")
                sendToastToJs(msg)
            }.onFailure { err ->
                val msg = "تعذر تعيين الخلفية: ${err.localizedMessage ?: err.message ?: "خطأ"}"
                val payload = JSONObject().apply {
                    put("success", false)
                    put("action", "set")
                    put("message", msg)
                }.toString()
                evaluateJs("window.onWallpaperResult?.($payload);")
                sendToastToJs(msg)
            }
        }
    }

    fun saveWallpaperImageFromUrl(imageUrl: String, filename: String) {
        if (!isOnline()) {
            val payload = JSONObject().apply {
                put("success", false)
                put("action", "save")
                put("message", "لا يوجد اتصال بالإنترنت")
            }.toString()
            evaluateJs("window.onWallpaperResult?.($payload);")
            return
        }
        lifecycleScope.launch {
            val res = downloadHelper.saveWallpaperFromUrl(imageUrl, filename)
            res.onSuccess { savedPath ->
                val msg = "تم حفظ الخلفية في: $savedPath"
                val payload = JSONObject().apply {
                    put("success", true)
                    put("action", "save")
                    put("savedPath", savedPath)
                    put("message", msg)
                }.toString()
                evaluateJs("window.onWallpaperResult?.($payload);")
                sendToastToJs("تم حفظ الخلفية في المعرض (Pictures/ThemeHub)")
            }.onFailure { err ->
                val msg = "فشل حفظ الخلفية: ${err.localizedMessage ?: err.message ?: "خطأ"}"
                val payload = JSONObject().apply {
                    put("success", false)
                    put("action", "save")
                    put("message", msg)
                }.toString()
                evaluateJs("window.onWallpaperResult?.($payload);")
                sendToastToJs(msg)
            }
        }
    }

    fun shareDownloadedThemeFile(key: String, title: String) {
        val uri = downloadHelper.getShareableFileUri(key)
        if (uri == null) {
            sendToastToJs("لم يتم العثور على الملف المحمل للمشاركة")
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "مشاركة ملف الثيم: $title"))
        }.onFailure {
            sendToastToJs("تعذر فتح نافذة المشاركة")
        }
    }

    fun sharePlainText(title: String, text: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "مشاركة $title"))
        }.onFailure {
            sendToastToJs("تعذر فتح نافذة المشاركة")
        }
    }
}

class AndroidWebAppInterface(private val activity: MainActivity) {

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("ThemeHub", text))
    }

    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun openThemeManager(brand: String): Boolean {
        val pkg = activity.downloadHelper.findThemeManagerPackage(brand)
        activity.runOnUiThread {
            activity.openBrandThemeManager(brand)
        }
        return pkg != null
    }

    @JavascriptInterface
    fun launchThemeManager(brand: String) {
        openThemeManager(brand)
    }

    @JavascriptInterface
    fun restoreAdminSession() {
        activity.runOnUiThread { activity.restoreAdminSessionOnStartup() }
    }

    @JavascriptInterface
    fun startGoogleSignIn() {
        activity.runOnUiThread { activity.startGoogleSignInFlow() }
    }

    @JavascriptInterface
    fun adminSignOut() {
        activity.runOnUiThread { activity.signOutAdmin() }
    }

    @JavascriptInterface
    fun saveGithubToken(token: String) {
        activity.runOnUiThread { activity.saveGithubTokenToFirestore(token) }
    }

    @JavascriptInterface
    fun fetchApprovedAdmins() {
        activity.runOnUiThread { activity.fetchApprovedAdminsList() }
    }

    @JavascriptInterface
    fun addApprovedAdmin(email: String) {
        activity.runOnUiThread { activity.addApprovedAdmin(email) }
    }

    @JavascriptInterface
    fun removeApprovedAdmin(email: String) {
        activity.runOnUiThread { activity.removeApprovedAdmin(email) }
    }

    @JavascriptInterface
    fun downloadThemeFile(key: String, url: String, filename: String, themeTitle: String, brand: String) {
        activity.runOnUiThread {
            activity.startResumableDownload(key, url, filename, themeTitle, brand)
        }
    }

    @JavascriptInterface
    fun pauseThemeDownload(key: String) {
        activity.downloadHelper.pauseDownload(key)
    }

    @JavascriptInterface
    fun copyThemeToFolder(key: String, brand: String, filename: String, forcePickFolder: Boolean) {
        activity.runOnUiThread {
            activity.requestCopyToThemesFolder(key, brand, filename, forcePickFolder)
        }
    }

    @JavascriptInterface
    fun getThemeEnvInfo(brand: String, key: String): String {
        return activity.downloadHelper.getThemeEnvironmentInfo(brand, key).toString()
    }

    @JavascriptInterface
    fun getAppVersionInfo(): String {
        return JSONObject().apply {
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("applicationId", BuildConfig.APPLICATION_ID)
        }.toString()
    }

    @JavascriptInterface
    fun getDeviceLocaleInfo(): String {
        val locale = ConfigurationCompat.getLocales(activity.resources.configuration)[0]
            ?: Locale.getDefault()
        return JSONObject().apply {
            put("languageTag", locale.toLanguageTag())
            put("language", locale.language)
            put("country", locale.country)
        }.toString()
    }

    @JavascriptInterface
    fun downloadAppUpdateApk(url: String, filename: String, releaseTitle: String) {
        activity.runOnUiThread {
            activity.startResumableDownload(
                key = "app_update_apk",
                url = url,
                filename = filename,
                themeTitle = releaseTitle,
                brand = "app_update"
            )
        }
    }

    @JavascriptInterface
    fun openDownloadsFolder() {
        activity.runOnUiThread { activity.openDownloadsFolder() }
    }

    @JavascriptInterface
    fun openExternalUrl(url: String) {
        activity.runOnUiThread { activity.openExternalBrowserUrl(url) }
    }

    @JavascriptInterface
    fun setWallpaperFromUrl(imageUrl: String, target: String) {
        activity.runOnUiThread { activity.applyWallpaperFromUrl(imageUrl, target) }
    }

    @JavascriptInterface
    fun saveWallpaperFromUrl(imageUrl: String, filename: String) {
        activity.runOnUiThread { activity.saveWallpaperImageFromUrl(imageUrl, filename) }
    }

    @JavascriptInterface
    fun listDownloadedFiles(): String {
        return activity.downloadHelper.listDownloadedFiles().toString()
    }

    @JavascriptInterface
    fun deleteDownloadedFile(key: String): Boolean {
        return activity.downloadHelper.deleteDownloadedFile(key)
    }

    @JavascriptInterface
    fun shareDownloadedFile(key: String, title: String) {
        activity.runOnUiThread { activity.shareDownloadedThemeFile(key, title) }
    }

    @JavascriptInterface
    fun importLocalThemeFile() {
        activity.runOnUiThread { activity.startLocalThemeImport() }
    }

    @JavascriptInterface
    fun getDeviceDiagnostics(): String {
        return activity.downloadHelper.getDeviceDiagnostics().toString()
    }

    @JavascriptInterface
    fun shareText(title: String, text: String) {
        activity.runOnUiThread { activity.sharePlainText(title, text) }
    }

    @JavascriptInterface
    fun consumePendingDeepLinkThemeId(): String {
        val id = activity.pendingDeepLinkThemeId ?: ""
        activity.pendingDeepLinkThemeId = null
        return id
    }

    @JavascriptInterface
    fun startCommunityGoogleSignIn() {
        activity.runOnUiThread { activity.startGoogleSignInFlow(isAdminGateAttempt = false) }
    }

    @JavascriptInterface
    fun signOutCommunity() {
        activity.runOnUiThread { activity.signOutAdmin() }
    }

    @JavascriptInterface
    fun fetchUserProfile(uid: String) {
        activity.runOnUiThread { activity.fetchUserProfileForJs(uid) }
    }

    @JavascriptInterface
    fun fetchAdminProfile() {
        activity.runOnUiThread { activity.fetchPrimaryAdminProfileForJs() }
    }

    @JavascriptInterface
    fun updateOwnProfile(displayName: String, bio: String) {
        activity.runOnUiThread { activity.updateOwnProfileForJs(displayName, bio) }
    }

    @JavascriptInterface
    fun setUserBlocked(targetUid: String, blocked: Boolean) {
        activity.runOnUiThread { activity.setUserBlockedForJs(targetUid, blocked) }
    }

    @JavascriptInterface
    fun fetchAllThemeRatings() {
        activity.runOnUiThread { activity.fetchAndSendAllThemeRatings() }
    }

    @JavascriptInterface
    fun fetchThemeRatingDetail(themeId: String) {
        activity.runOnUiThread { activity.fetchThemeRatingDetailForJs(themeId) }
    }

    @JavascriptInterface
    fun submitThemeRating(themeId: String, stars: Int) {
        activity.runOnUiThread { activity.submitThemeRatingForJs(themeId, stars) }
    }

    @JavascriptInterface
    fun fetchThemeComments(themeId: String, cursorId: String) {
        activity.runOnUiThread { activity.fetchThemeCommentsForJs(themeId, cursorId) }
    }

    @JavascriptInterface
    fun addThemeComment(themeId: String, text: String) {
        activity.runOnUiThread { activity.addThemeCommentForJs(themeId, text) }
    }

    @JavascriptInterface
    fun toggleCommentLike(themeId: String, commentId: String) {
        activity.runOnUiThread { activity.toggleCommentLikeForJs(themeId, commentId) }
    }

    @JavascriptInterface
    fun deleteThemeComment(themeId: String, commentId: String) {
        activity.runOnUiThread { activity.deleteThemeCommentForJs(themeId, commentId) }
    }

    @JavascriptInterface
    fun reportCommunityContent(
        targetType: String,
        targetId: String,
        reportedUid: String,
        reason: String,
        textSnippet: String,
        themeId: String,
        conversationUid: String
    ) {
        activity.runOnUiThread {
            activity.reportCommunityContentForJs(
                targetType = targetType,
                targetId = targetId,
                reportedUid = reportedUid,
                reason = reason,
                textSnippet = textSnippet,
                themeId = themeId,
                conversationUid = conversationUid
            )
        }
    }

    @JavascriptInterface
    fun openConversationStream(conversationUid: String) {
        activity.runOnUiThread { activity.openConversationStreamForJs(conversationUid) }
    }

    @JavascriptInterface
    fun closeConversationStream() {
        activity.runOnUiThread { activity.closeConversationStreamForJs() }
    }

    @JavascriptInterface
    fun sendPrivateMessage(conversationUid: String, text: String) {
        activity.runOnUiThread { activity.sendPrivateMessageForJs(conversationUid, text) }
    }
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ThemeHubApp(
    onWebViewReady: (WebView) -> Unit,
    onExitApp: () -> Unit = {}
) {
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        val wv = webViewInstance
        if (wv == null) {
            onExitApp()
            return@BackHandler
        }
        wv.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : 'exit'") { rawResult ->
            val result = rawResult?.trim('"')
            if (result == "exit") {
                if (wv.canGoBack() && canGoBack) {
                    wv.goBack()
                } else {
                    onExitApp()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F14))
            .systemBarsPadding()
            .testTag("theme_hub_container")
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag("theme_hub_webview"),
            factory = { ctx ->
                val activity = ctx.findMainActivity()
                    ?: error("ThemeHubApp must be hosted inside MainActivity")
                if (BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                WebView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setBackgroundColor(0xFF0F0F14.toInt())

                    val currentWebView = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(currentWebView, true)
                    }

                    @Suppress("DEPRECATION")
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = false
                        displayZoomControls = false
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    addJavascriptInterface(AndroidWebAppInterface(activity), "AndroidBridge")

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            view: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            return activity.openFileChooser(filePathCallback, fileChooserParams)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            canGoBack = view?.canGoBack() == true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            canGoBack = view?.canGoBack() == true
                            activity.onWebViewLoaded(view)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                Log.e("ThemeHubWebView", "Main frame load error: ${error?.description}")
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            Log.e("ThemeHubWebView", "WebView renderer gone; reloading asset UI")
                            view?.loadUrl("file:///android_asset/index.html")
                            return true
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            return when {
                                url.startsWith("intent:") -> {
                                    runCatching { ctx.startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)) }
                                        .onFailure { Toast.makeText(ctx, "التطبيق المطلوب غير مثبت على الجهاز", Toast.LENGTH_SHORT).show() }
                                    true
                                }
                                url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("market:") -> {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    true
                                }
                                else -> false
                            }
                        }
                    }

                    loadUrl("file:///android_asset/index.html")
                    onWebViewReady(this)
                }
            },
            update = { wv ->
                if (webViewInstance !== wv) {
                    webViewInstance = wv
                }
                onWebViewReady(wv)
            }
        )
    }
}
