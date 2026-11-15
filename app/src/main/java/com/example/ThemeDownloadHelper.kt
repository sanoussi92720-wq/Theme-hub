package com.example

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class ThemeDownloadHelper(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "theme_hub_storage_prefs"
        private const val KEY_SAF_TREE_PREFIX = "saf_tree_uri_"
        private const val KEY_SAVED_URI_PREFIX = "saved_uri_"
        private const val KEY_SAVED_PATH_PREFIX = "saved_path_"
        private const val KEY_COPIED_PATH_PREFIX = "copied_path_"
        private const val KEY_DOWNLOADED_CATALOG = "downloaded_files_catalog_json"
        private const val CHANNEL_ID = "themehub_downloads"
        private const val BUFFER_SIZE = 32 * 1024
        private const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pausedKeys = ConcurrentHashMap.newKeySet<String>()

    fun registerJob(key: String, job: Job) {
        activeJobs[key]?.cancel()
        pausedKeys.remove(key)
        activeJobs[key] = job
    }

    fun pauseDownload(key: String) {
        pausedKeys.add(key)
        activeJobs[key]?.cancel()
        activeJobs.remove(key)
    }

    fun isPaused(key: String): Boolean = pausedKeys.contains(key)

    fun clearJob(key: String) {
        activeJobs.remove(key)
    }

    fun getSavedTreeUri(brand: String): Uri? {
        val norm = brand.trim().lowercase()
        val raw = prefs.getString("$KEY_SAF_TREE_PREFIX$norm", null)
            ?: prefs.getString("${KEY_SAF_TREE_PREFIX}default", null)
            ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        return if (hasPermission) uri else null
    }

    fun saveTreeUri(brand: String, uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
        }
        val norm = brand.trim().lowercase().ifEmpty { "default" }
        prefs.edit()
            .putString("$KEY_SAF_TREE_PREFIX$norm", uri.toString())
            .putString("${KEY_SAF_TREE_PREFIX}default", uri.toString())
            .apply()
    }

    fun getLastSavedInfo(key: String): Pair<Uri?, String?> {
        val uriStr = prefs.getString("$KEY_SAVED_URI_PREFIX$key", null)
        val path = prefs.getString("$KEY_SAVED_PATH_PREFIX$key", null)
        val uri = uriStr?.let { runCatching { Uri.parse(it) }.getOrNull() }
        return uri to path
    }

    private fun saveDownloadedRecord(key: String, uri: Uri, actualPath: String) {
        prefs.edit()
            .putString("$KEY_SAVED_URI_PREFIX$key", uri.toString())
            .putString("$KEY_SAVED_PATH_PREFIX$key", actualPath)
            .apply()
    }

    private fun saveCopiedPath(key: String, copiedPath: String) {
        prefs.edit().putString("$KEY_COPIED_PATH_PREFIX$key", copiedPath).apply()
    }

    fun getThemeEnvironmentInfo(brand: String, key: String): JSONObject {
        val b = brand.trim().lowercase()
        val savedInfo = getLastSavedInfo(key)
        val copiedPath = prefs.getString("$KEY_COPIED_PATH_PREFIX$key", null)
        val treeUri = getSavedTreeUri(b)
        val pendingFile = getPendingFile(key)
        val pendingBytes = if (pendingFile.exists()) pendingFile.length() else 0L

        val honorFolderPath = detectHonorThemesFolder()
        val huaweiFolderPath = detectHuaweiThemesFolder()
        val packageInstalled = findThemeManagerPackage(b)

        return JSONObject().apply {
            put("brand", brand)
            put("savedPath", savedInfo.second ?: "")
            put("hasSavedFile", savedInfo.first != null)
            put("copiedPath", copiedPath ?: "")
            put("hasSafFolder", treeUri != null)
            put("safFolderLabel", treeUri?.let { extractReadableTreePath(it) } ?: "")
            put("honorThemesFolder", honorFolderPath ?: "")
            put("huaweiThemesFolder", huaweiFolderPath ?: "")
            put("themeManagerPackage", packageInstalled ?: "")
            put("pendingBytes", pendingBytes)
        }
    }

    private fun detectHonorThemesFolder(): String? {
        val candidates = listOf(
            File("/storage/emulated/0/Honor/Themes"),
            File("/storage/emulated/0/Huawei/Themes"),
            File("/storage/emulated/0/Themes")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }?.absolutePath
    }

    private fun detectHuaweiThemesFolder(): String? {
        val candidates = listOf(
            File("/storage/emulated/0/Huawei/Themes"),
            File("/storage/emulated/0/Honor/Themes")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }?.absolutePath
    }

    fun findThemeManagerPackage(brand: String): String? {
        val pm = context.packageManager
        val candidates = when (brand.trim().lowercase()) {
            "honor" -> listOf("com.hihonor.android.thememanager", "com.hihonor.thememanager", "com.huawei.android.thememanager")
            "huawei" -> listOf("com.huawei.android.thememanager", "com.hihonor.android.thememanager")
            "xiaomi", "redmi", "poco" -> listOf("com.android.thememanager")
            "samsung" -> listOf("com.samsung.android.themestore")
            "oppo", "realme" -> listOf("com.heytap.themestore", "com.nearme.themespace")
            "vivo" -> listOf("com.bbk.theme")
            else -> listOf(
                "com.hihonor.android.thememanager",
                "com.huawei.android.thememanager",
                "com.android.thememanager",
                "com.samsung.android.themestore"
            )
        }
        return candidates.firstOrNull { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }
    }

    fun buildInitialTreeHintUri(brand: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val subPath = when (brand.trim().lowercase()) {
            "huawei" -> "Huawei/Themes"
            "xiaomi" -> "MIUI/theme"
            else -> "Honor/Themes"
        }
        return runCatching {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$subPath")
        }.getOrNull()
    }

    private fun getPendingFile(key: String): File {
        val dir = File(context.filesDir, "themehub_pending")
        if (!dir.exists()) dir.mkdirs()
        val safeKey = key.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "$safeKey.part")
    }

    suspend fun downloadThemeResumable(
        key: String,
        url: String,
        filename: String,
        themeTitle: String,
        brand: String,
        onProgress: (JSONObject) -> Unit
    ) = withContext(Dispatchers.IO) {
        val cleanFilename = sanitizeFilename(filename)
        val partFile = getPendingFile(key)
        val notifId = key.hashCode()
        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        try {
            var connection = openRedirectedConnection(url, existingBytes)
            var responseCode = connection.responseCode

            // If server ignored Range or file size changed, restart from 0
            if (responseCode == HttpURLConnection.HTTP_OK && existingBytes > 0L) {
                existingBytes = 0L
                partFile.delete()
            } else if (responseCode == 416) {
                // Range Not Satisfiable — check if already complete or restart
                connection.disconnect()
                existingBytes = 0L
                partFile.delete()
                connection = openRedirectedConnection(url, 0L)
                responseCode = connection.responseCode
            }

            if (responseCode !in 200..299) {
                val errMsg = "فشل التنزيل (HTTP $responseCode): تعذر الوصول إلى ملف الثيم"
                connection.disconnect()
                postNotification(notifId, themeTitle, errMsg, -1, false)
                onProgress(buildProgressJson(key, "error", 0, 0L, 0L, "", errMsg, false))
                return@withContext
            }

            val contentLength = connection.contentLengthLongCompat()
            val totalBytes = if (responseCode == HttpURLConnection.HTTP_PARTIAL && contentLength > 0) {
                existingBytes + contentLength
            } else {
                contentLength
            }

            onProgress(buildProgressJson(key, "downloading", if (totalBytes > 0) ((existingBytes * 100) / totalBytes).toInt() else 0, existingBytes, totalBytes, "", "", false))
            postNotification(notifId, themeTitle, "جاري التنزيل...", 0, true)

            connection.inputStream.use { input ->
                FileOutputStream(partFile, existingBytes > 0L).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = existingBytes
                    var lastEmitTime = 0L
                    while (true) {
                        if (isPaused(key)) {
                            val pct = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0
                            postNotification(notifId, themeTitle, "تم إيقاف التنزيل مؤقتاً ($pct%)", pct, false)
                            onProgress(buildProgressJson(key, "paused", pct, downloaded, totalBytes, "", "", false))
                            return@withContext
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 220) {
                            lastEmitTime = now
                            val pct = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99) else 0
                            postNotification(notifId, themeTitle, "جاري التنزيل ($pct%)", pct, true)
                            onProgress(buildProgressJson(key, "downloading", pct, downloaded, totalBytes, "", "", false))
                        }
                    }
                    output.flush()
                }
            }
            connection.disconnect()

            // Save completed file into Download/ThemeHub/ via MediaStore (API 29+)
            val savedFileSize = partFile.length()
            val (savedUri, actualPath) = commitToDownloadsThemeHub(partFile, cleanFilename)
            partFile.delete()
            saveDownloadedRecord(key, savedUri, actualPath)

            // Auto-copy to SAF folder if user already picked one before (only for theme packages, never APK updates)
            var copiedPath = ""
            if (!brand.equals("app_update", ignoreCase = true)) {
                val existingTreeUri = getSavedTreeUri(brand)
                if (existingTreeUri != null) {
                    val copyResult = copyUriToTreeFolder(savedUri, existingTreeUri, cleanFilename)
                    if (copyResult.isSuccess) {
                        copiedPath = copyResult.getOrNull().orEmpty()
                        if (copiedPath.isNotEmpty()) {
                            saveCopiedPath(key, copiedPath)
                        }
                    }
                }
                upsertCatalogEntry(
                    key = key,
                    title = themeTitle,
                    brand = brand,
                    filename = cleanFilename,
                    savedPath = actualPath,
                    copiedPath = copiedPath,
                    sizeBytes = savedFileSize.coerceAtLeast(totalBytes),
                    isImported = false
                )
            }

            val finalDisplayPath = copiedPath.ifEmpty { actualPath }
            postNotification(notifId, themeTitle, "اكتمل الحفظ في $finalDisplayPath", 100, false)
            onProgress(
                buildProgressJson(
                    key = key,
                    status = "completed",
                    percent = 100,
                    downloaded = totalBytes.coerceAtLeast(1L),
                    total = totalBytes.coerceAtLeast(1L),
                    actualPath = finalDisplayPath,
                    error = "",
                    copiedToThemes = copiedPath.isNotEmpty()
                ).apply {
                    put("downloadsPath", actualPath)
                    put("copiedPath", copiedPath)
                }
            )
        } catch (ce: CancellationException) {
            if (isPaused(key)) {
                val cur = if (partFile.exists()) partFile.length() else 0L
                postNotification(notifId, themeTitle, "تم إيقاف التنزيل مؤقتاً", 0, false)
                onProgress(buildProgressJson(key, "paused", 0, cur, 0L, "", "", false))
            }
            throw ce
        } catch (e: Exception) {
            val cur = if (partFile.exists()) partFile.length() else 0L
            val errMsg = "فشل التنزيل [${e.javaClass.simpleName}]: ${e.localizedMessage ?: "خطأ في الشبكة"}"
            postNotification(notifId, themeTitle, errMsg, -1, false)
            onProgress(buildProgressJson(key, "error", 0, cur, 0L, "", errMsg, false))
        }
    }

    private fun openRedirectedConnection(urlStr: String, rangeStart: Long): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ThemeHub-Android/2.4")
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) return conn
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
                continue
            }
            return conn
        }
        return (URL(currentUrl).openConnection() as HttpURLConnection)
    }

    private fun HttpURLConnection.contentLengthLongCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            contentLengthLong
        } else {
            contentLength.toLong()
        }
    }

    private fun commitToDownloadsThemeHub(sourceFile: File, filename: String): Pair<Uri, String> {
        val mime = guessMimeType(filename)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relPath = "${Environment.DIRECTORY_DOWNLOADS}/ThemeHub"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("تعذر إنشاء ملف في MediaStore Download/ThemeHub")
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out, BUFFER_SIZE)
                }
            } ?: throw IllegalStateException("تعذر فتح مسار الحفظ في MediaStore")

            val doneValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, doneValues, null, null)
            return uri to "/storage/emulated/0/Download/ThemeHub/$filename"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ThemeHub")
            if (!dir.exists()) dir.mkdirs()
            val destFile = File(dir, filename)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { out ->
                    input.copyTo(out, BUFFER_SIZE)
                }
            }
            return Uri.fromFile(destFile) to destFile.absolutePath
        }
    }

    suspend fun copyDownloadedToThemesTree(
        key: String,
        treeUri: Uri,
        filename: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val (savedUri, savedPath) = getLastSavedInfo(key)
        if (savedUri == null) {
            return@withContext Result.failure(IllegalStateException("يجب تنزيل ملف الثيم أولاً قبل نسخه إلى مجلد الثيمات (المسار المتوقع: ${savedPath ?: "Download/ThemeHub"})"))
        }
        val cleanName = sanitizeFilename(filename)
        val result = copyUriToTreeFolder(savedUri, treeUri, cleanName)
        result.onSuccess { copiedPath ->
            saveCopiedPath(key, copiedPath)
            updateCatalogCopiedPath(key, copiedPath)
        }
        result
    }

    // =========================================================================
    // NEW FEATURES: DOWNLOADED FILES CATALOG, LOCAL IMPORT, WALLPAPER & DEVICE INFO
    // =========================================================================

    private fun readCatalogMap(): MutableMap<String, JSONObject> {
        val raw = prefs.getString(KEY_DOWNLOADED_CATALOG, null).orEmpty()
        val map = linkedMapOf<String, JSONObject>()
        if (raw.isBlank()) return map
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val k = obj.optString("key")
                if (k.isNotBlank()) map[k] = obj
            }
        }
        return map
    }

    private fun writeCatalogMap(map: Map<String, JSONObject>) {
        val arr = JSONArray()
        map.values.sortedByDescending { it.optLong("downloadedAt", 0L) }.forEach { arr.put(it) }
        prefs.edit().putString(KEY_DOWNLOADED_CATALOG, arr.toString()).apply()
    }

    private fun upsertCatalogEntry(
        key: String,
        title: String,
        brand: String,
        filename: String,
        savedPath: String,
        copiedPath: String,
        sizeBytes: Long,
        isImported: Boolean
    ) {
        val map = readCatalogMap()
        val existing = map[key]
        val entry = JSONObject().apply {
            put("key", key)
            put("title", title.ifBlank { filename })
            put("brand", brand)
            put("filename", filename)
            put("savedPath", savedPath)
            put("copiedPath", copiedPath.ifBlank { existing?.optString("copiedPath").orEmpty() })
            put("sizeBytes", sizeBytes.coerceAtLeast(0L))
            put("downloadedAt", System.currentTimeMillis())
            put("isImported", isImported)
        }
        map[key] = entry
        writeCatalogMap(map)
    }

    private fun updateCatalogCopiedPath(key: String, copiedPath: String) {
        val map = readCatalogMap()
        val obj = map[key] ?: return
        obj.put("copiedPath", copiedPath)
        writeCatalogMap(map)
    }

    fun listDownloadedFiles(): JSONArray {
        val map = readCatalogMap()
        val result = JSONArray()
        val staleKeys = mutableListOf<String>()

        map.values.sortedByDescending { it.optLong("downloadedAt", 0L) }.forEach { obj ->
            val key = obj.optString("key")
            val (savedUri, _) = getLastSavedInfo(key)
            val exists = if (savedUri != null) {
                isUriAccessible(savedUri)
            } else {
                false
            }
            if (!exists && savedUri != null) {
                staleKeys.add(key)
            } else {
                val copied = prefs.getString("$KEY_COPIED_PATH_PREFIX$key", null).orEmpty()
                val copyObj = JSONObject(obj.toString()).apply {
                    if (copied.isNotBlank()) put("copiedPath", copied)
                    put("exists", exists)
                }
                result.put(copyObj)
            }
        }

        if (staleKeys.isNotEmpty()) {
            staleKeys.forEach { k ->
                map.remove(k)
                prefs.edit()
                    .remove("$KEY_SAVED_URI_PREFIX$k")
                    .remove("$KEY_SAVED_PATH_PREFIX$k")
                    .remove("$KEY_COPIED_PATH_PREFIX$k")
                    .apply()
            }
            writeCatalogMap(map)
        }
        return result
    }

    private fun isUriAccessible(uri: Uri): Boolean {
        return runCatching {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: "")
                f.exists() && f.length() > 0L
            } else {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }
        }.getOrDefault(false)
    }

    fun getShareableFileUri(key: String): Uri? {
        val (savedUri, _) = getLastSavedInfo(key)
        if (savedUri == null) return null
        return if (savedUri.scheme == "file") {
            val f = File(savedUri.path ?: "")
            if (!f.exists()) null
            else runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            }.getOrNull()
        } else {
            savedUri
        }
    }

    fun deleteDownloadedFile(key: String): Boolean {
        val (savedUri, _) = getLastSavedInfo(key)
        if (savedUri != null) {
            runCatching {
                if (savedUri.scheme == "file") {
                    File(savedUri.path ?: "").delete()
                } else {
                    context.contentResolver.delete(savedUri, null, null)
                }
            }
        }
        prefs.edit()
            .remove("$KEY_SAVED_URI_PREFIX$key")
            .remove("$KEY_SAVED_PATH_PREFIX$key")
            .remove("$KEY_COPIED_PATH_PREFIX$key")
            .apply()

        val map = readCatalogMap()
        val removed = map.remove(key) != null
        writeCatalogMap(map)
        return removed || savedUri != null
    }

    suspend fun importLocalThemeUri(sourceUri: Uri): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var displayName = ""
            var fileSize = 0L
            resolver.query(
                sourceUri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: ""
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) fileSize = cursor.getLong(sizeIdx)
                }
            }

            val cleanName = sanitizeFilename(displayName.ifBlank { "imported_theme_${System.currentTimeMillis()}.hnt" })
            val ext = cleanName.substringAfterLast('.', "").lowercase()
            val detectedBrand = when (ext) {
                "hnt" -> "Honor"
                "hwt" -> "Huawei"
                "mtz" -> "Xiaomi"
                "apk" -> "Samsung"
                else -> detectDeviceBrandNormalized()
            }

            val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}_$cleanName")
            try {
                resolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(tempFile).use { out ->
                        input.copyTo(out, BUFFER_SIZE)
                    }
                } ?: throw IllegalStateException("تعذر قراءة الملف المختار")

                val actualSize = if (tempFile.length() > 0L) tempFile.length() else fileSize
                if (actualSize <= 0L) {
                    throw IllegalStateException("الملف المختار فارغ")
                }

                val (savedUri, actualPath) = commitToDownloadsThemeHub(tempFile, cleanName)
                val key = "local_${System.currentTimeMillis()}"
                saveDownloadedRecord(key, savedUri, actualPath)

                var copiedPath = ""
                val existingTreeUri = getSavedTreeUri(detectedBrand)
                if (existingTreeUri != null) {
                    val copyResult = copyUriToTreeFolder(savedUri, existingTreeUri, cleanName)
                    if (copyResult.isSuccess) {
                        copiedPath = copyResult.getOrNull().orEmpty()
                        if (copiedPath.isNotEmpty()) saveCopiedPath(key, copiedPath)
                    }
                }

                val title = cleanName.substringBeforeLast('.').replace('_', ' ').ifBlank { cleanName }
                upsertCatalogEntry(
                    key = key,
                    title = title,
                    brand = detectedBrand,
                    filename = cleanName,
                    savedPath = actualPath,
                    copiedPath = copiedPath,
                    sizeBytes = actualSize,
                    isImported = true
                )

                JSONObject().apply {
                    put("success", true)
                    put("key", key)
                    put("title", title)
                    put("brand", detectedBrand)
                    put("format", ext.ifBlank { "zip" })
                    put("filename", cleanName)
                    put("savedPath", actualPath)
                    put("copiedPath", copiedPath)
                    put("sizeBytes", actualSize)
                }
            } finally {
                runCatching { tempFile.delete() }
            }
        }
    }

    suspend fun setWallpaperFromUrl(imageUrl: String, target: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val tempImg = downloadImageToCache(imageUrl)
            try {
                val bitmap = decodeSampledBitmap(tempImg, 2400, 2400)
                    ?: throw IllegalStateException("تعذر فك تشفير صورة الخلفية")
                try {
                    val wm = WallpaperManager.getInstance(context)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val flags = when (target.lowercase()) {
                            "lock" -> WallpaperManager.FLAG_LOCK
                            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                            else -> WallpaperManager.FLAG_SYSTEM
                        }
                        wm.setBitmap(bitmap, null, true, flags)
                    } else {
                        wm.setBitmap(bitmap)
                    }
                } finally {
                    bitmap.recycle()
                }
                when (target.lowercase()) {
                    "lock" -> "تم تعيين الخلفية لشاشة القفل بنجاح!"
                    "both" -> "تم تعيين الخلفية للشاشة الرئيسية وشاشة القفل!"
                    else -> "تم تعيين الخلفية للشاشة الرئيسية بنجاح!"
                }
            } finally {
                runCatching { tempImg.delete() }
            }
        }
    }

    suspend fun saveWallpaperFromUrl(imageUrl: String, filename: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val tempImg = downloadImageToCache(imageUrl)
            try {
                val cleanName = sanitizeFilename(
                    filename.ifBlank { "ThemeHub_Wallpaper_${System.currentTimeMillis()}.jpg" }
                )
                val mime = if (cleanName.endsWith(".png", true)) "image/png"
                else if (cleanName.endsWith(".webp", true)) "image/webp"
                else "image/jpeg"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ThemeHub")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("تعذر حفظ الخلفية في المعرض")
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(tempImg).use { input -> input.copyTo(out, BUFFER_SIZE) }
                    } ?: throw IllegalStateException("تعذر فتح مسار الصور")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    "/storage/emulated/0/Pictures/ThemeHub/$cleanName"
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ThemeHub")
                    if (!dir.exists()) dir.mkdirs()
                    val dest = File(dir, cleanName)
                    FileInputStream(tempImg).use { input ->
                        FileOutputStream(dest).use { out -> input.copyTo(out, BUFFER_SIZE) }
                    }
                    dest.absolutePath
                }
            } finally {
                runCatching { tempImg.delete() }
            }
        }
    }

    private fun downloadImageToCache(imageUrl: String): File {
        val trimmed = imageUrl.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            throw IllegalArgumentException("رابط الصورة غير صالح")
        }
        val conn = openRedirectedConnection(trimmed, 0L)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("تعذر تحميل الصورة (HTTP $code)")
            }
            val tempFile = File(context.cacheDir, "wp_${System.currentTimeMillis()}.img")
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) {
                            throw IllegalStateException("حجم الصورة كبير جداً")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (tempFile.length() <= 0L) {
                throw IllegalStateException("ملف الصورة فارغ")
            }
            return tempFile
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        var inSampleSize = 1
        val height = boundsOpts.outHeight
        val width = boundsOpts.outWidth
        if (height > reqHeight || width > reqWidth) {
            val halfH = height / 2
            val halfW = width / 2
            while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    }

    fun detectDeviceBrandNormalized(): String {
        val m = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return when {
            m.contains("honor") -> "Honor"
            m.contains("huawei") -> "Huawei"
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "Xiaomi"
            m.contains("samsung") -> "Samsung"
            m.contains("oppo") -> "OPPO"
            m.contains("vivo") || m.contains("iqoo") -> "Vivo"
            m.contains("realme") -> "Realme"
            else -> "Universal"
        }
    }

    fun getDeviceDiagnostics(): JSONObject {
        val detectedBrand = detectDeviceBrandNormalized()
        val pkg = findThemeManagerPackage(detectedBrand)
        val treeUri = getSavedTreeUri(detectedBrand)
        val managerLabel = when (pkg) {
            "com.hihonor.android.thememanager", "com.hihonor.thememanager" -> "Honor Themes"
            "com.huawei.android.thememanager" -> "Huawei Themes"
            "com.android.thememanager" -> "Xiaomi Themes (MIUI/HyperOS)"
            "com.samsung.android.themestore" -> "Samsung Galaxy Themes"
            "com.heytap.themestore", "com.nearme.themespace" -> "OPPO / Realme Theme Store"
            "com.bbk.theme" -> "Vivo i Theme"
            else -> ""
        }
        val recommendedFormat = when (detectedBrand) {
            "Honor" -> "HNT / HWT"
            "Huawei" -> "HWT"
            "Xiaomi" -> "MTZ"
            "Samsung" -> "APK / ZIP"
            else -> "ZIP / APK"
        }
        val dm = context.resources.displayMetrics
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("brand", Build.BRAND ?: "")
            put("model", Build.MODEL ?: "")
            put("androidVersion", Build.VERSION.RELEASE ?: "")
            put("sdkInt", Build.VERSION.SDK_INT)
            put("screenResolution", "${dm.widthPixels}x${dm.heightPixels}")
            put("detectedBrand", detectedBrand)
            put("recommendedFormat", recommendedFormat)
            put("installedThemeManagerPkg", pkg ?: "")
            put("installedThemeManagerLabel", managerLabel)
            put("hasSavedSafFolder", treeUri != null)
            put("safFolderLabel", treeUri?.let { extractReadableTreePath(it) } ?: "")
            put("honorThemesFolder", detectHonorThemesFolder() ?: "")
            put("huaweiThemesFolder", detectHuaweiThemesFolder() ?: "")
        }
    }

    private fun copyUriToTreeFolder(sourceUri: Uri, treeUri: Uri, filename: String): Result<String> {
        return runCatching {
            val resolver = context.contentResolver
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

            // Delete existing child with same name if present so re-copy never creates duplicates like (1)
            findChildDocumentUri(treeUri, treeDocId, filename)?.let { existingUri ->
                runCatching { DocumentsContract.deleteDocument(resolver, existingUri) }
            }

            val destUri = DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                guessMimeType(filename),
                filename
            ) ?: throw IllegalStateException("تعذر إنشاء الملف داخل مجلد الثيمات المحدد")

            val inputStream: InputStream = if (sourceUri.scheme == "file") {
                FileInputStream(File(sourceUri.path ?: ""))
            } else {
                resolver.openInputStream(sourceUri)
                    ?: throw IllegalStateException("تعذر قراءة الملف المنزّل من Download/ThemeHub")
            }

            inputStream.use { input ->
                resolver.openOutputStream(destUri)?.use { out ->
                    input.copyTo(out, BUFFER_SIZE)
                } ?: throw IllegalStateException("تعذر الكتابة في مجلد الثيمات المحدد")
            }

            val folderReadable = extractReadableTreePath(treeUri)
            "$folderReadable/$filename"
        }
    }

    private fun findChildDocumentUri(treeUri: Uri, parentDocId: String, displayName: String): Uri? {
        return runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == displayName) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIdx))
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun extractReadableTreePath(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        return when {
            docId.startsWith("primary:") -> "/storage/emulated/0/" + docId.removePrefix("primary:")
            docId.isNotEmpty() -> docId
            else -> treeUri.lastPathSegment ?: "Themes"
        }
    }

    private fun sanitizeFilename(name: String): String {
        val trimmed = name.trim().ifEmpty { "theme_${System.currentTimeMillis()}.zip" }
        return trimmed.replace(Regex("[/\\\\?%*:|\"<>]"), "_")
    }

    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun buildProgressJson(
        key: String,
        status: String,
        percent: Int,
        downloaded: Long,
        total: Long,
        actualPath: String,
        error: String,
        copiedToThemes: Boolean
    ): JSONObject {
        return JSONObject().apply {
            put("key", key)
            put("status", status)
            put("percent", percent)
            put("downloadedBytes", downloaded)
            put("totalBytes", total)
            put("actualPath", actualPath)
            put("error", error)
            put("copiedToThemes", copiedToThemes)
        }
    }

    private fun postNotification(
        notifId: Int,
        title: String,
        text: String,
        progress: Int,
        ongoing: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "تنزيلات الثيمات", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Theme Hub • $title")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
        if (progress in 0..99 && ongoing) {
            builder.setProgress(100, progress, progress == 0)
        } else {
            builder.setProgress(0, 0, false)
        }
        if (!ongoing) {
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
        }
        runCatching { nm.notify(notifId, builder.build()) }
    }
}
