package com.example

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class UserProfileData(
    val uid: String,
    val displayName: String,
    val photoURL: String,
    val bio: String,
    val createdAtMs: Long,
    val role: String,
    val blocked: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("displayName", displayName)
        put("photoURL", photoURL)
        put("bio", bio)
        put("createdAt", createdAtMs)
        put("role", role)
        put("blocked", blocked)
    }
}

data class ThemeStatsData(
    val themeId: String,
    val ratingSum: Double,
    val ratingCount: Long,
    val ratingAvg: Double,
    val userStars: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("themeId", themeId)
        put("ratingSum", ratingSum)
        put("ratingCount", ratingCount)
        put("ratingAvg", ratingAvg)
        put("userStars", userStars)
    }
}

data class CommentData(
    val id: String,
    val uid: String,
    val name: String,
    val photo: String,
    val text: String,
    val createdAtMs: Long,
    val likes: Long,
    val likedByMe: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uid", uid)
        put("name", name)
        put("photo", photo)
        put("text", text)
        put("createdAt", createdAtMs)
        put("likes", likes)
        put("likedByMe", likedByMe)
    }
}

data class CommentsPageData(
    val comments: List<CommentData>,
    val hasMore: Boolean,
    val nextCursorId: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("comments", JSONArray().apply { comments.forEach { put(it.toJson()) } })
        put("hasMore", hasMore)
        put("nextCursorId", nextCursorId ?: "")
    }
}

class FirebaseCommunityRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val commentCursorCache = mutableMapOf<String, DocumentSnapshot>()
    private var lastNonAdminSentAtMs: Long = 0L

    private fun cleanText(raw: String, maxLen: Int): String {
        val cleaned = raw.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "").trim()
        return if (cleaned.length > maxLen) cleaned.substring(0, maxLen) else cleaned
    }

    private fun isValidDocId(id: String): Boolean {
        return id.isNotEmpty() && id.length <= 128 && ID_REGEX.matches(id) && id != "." && id != ".."
    }

    private fun parseUserSnapshot(snap: DocumentSnapshot, fallbackUid: String = ""): UserProfileData {
        val uid = snap.getString("uid") ?: snap.id.ifEmpty { fallbackUid }
        val name = (snap.getString("displayName") ?: "مستخدم").ifBlank { "مستخدم" }
        val photo = snap.getString("photoURL") ?: ""
        val bio = snap.getString("bio") ?: ""
        val createdTs = snap.getTimestamp("createdAt")
        val createdMs = createdTs?.toDate()?.time ?: 0L
        val role = if (snap.getString("role") == "admin") "admin" else "user"
        val blocked = snap.getBoolean("blocked") == true
        return UserProfileData(uid, name, photo, bio, createdMs, role, blocked)
    }

    suspend fun ensureUserProfile(user: FirebaseUser): UserProfileData {
        val uid = user.uid
        val ref = firestore.collection("users").document(uid)
        val snap = ref.get().await()
        if (snap.exists()) {
            return parseUserSnapshot(snap, uid)
        }

        val defaultName = cleanText(
            user.displayName?.takeIf { it.isNotBlank() }
                ?: user.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
                ?: "مستخدم Theme Hub",
            60
        ).ifBlank { "مستخدم" }
        val photo = cleanText(user.photoUrl?.toString() ?: "", 500)

        val newDoc = hashMapOf<String, Any>(
            "uid" to uid,
            "displayName" to defaultName,
            "photoURL" to photo,
            "bio" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "role" to "user",
            "blocked" to false
        )
        ref.set(newDoc).await()
        val createdSnap = ref.get().await()
        return if (createdSnap.exists()) {
            parseUserSnapshot(createdSnap, uid)
        } else {
            UserProfileData(uid, defaultName, photo, "", System.currentTimeMillis(), "user", false)
        }
    }

    suspend fun getUserProfile(uid: String): Result<UserProfileData> = runCatching {
        require(isValidDocId(uid)) { "معرف مستخدم غير صالح" }
        val snap = firestore.collection("users").document(uid).get().await()
        require(snap.exists()) { "لم يتم العثور على الملف الشخصي" }
        parseUserSnapshot(snap, uid)
    }

    suspend fun getPrimaryAdminProfile(): Result<UserProfileData?> = runCatching {
        val snap = firestore.collection("users")
            .whereEqualTo("role", "admin")
            .limit(1)
            .get()
            .await()
        val doc = snap.documents.firstOrNull() ?: return@runCatching null
        parseUserSnapshot(doc)
    }

    suspend fun updateOwnProfile(displayName: String, bio: String): Result<UserProfileData> = runCatching {
        val current = auth.currentUser ?: error("يجب تسجيل الدخول أولاً")
        val cleanName = cleanText(displayName, 60)
        require(cleanName.isNotEmpty()) { "الاسم مطلوب ولا يمكن أن يكون فارغاً" }
        val cleanBio = cleanText(bio, 150)

        val updates = hashMapOf<String, Any>(
            "displayName" to cleanName,
            "bio" to cleanBio
        )
        val ref = firestore.collection("users").document(current.uid)
        ref.update(updates).await()
        parseUserSnapshot(ref.get().await(), current.uid)
    }

    suspend fun setUserBlocked(targetUid: String, blocked: Boolean): Result<Unit> = runCatching {
        val current = auth.currentUser ?: error("يجب تسجيل الدخول أولاً")
        require(isValidDocId(targetUid)) { "معرف مستخدم غير صالح" }
        require(targetUid != current.uid) { "لا يمكن حظر حسابك الشخصي" }
        firestore.collection("users").document(targetUid).update("blocked", blocked).await()
    }

    suspend fun fetchAllThemeStats(): Result<Map<String, ThemeStatsData>> = runCatching {
        val snap = firestore.collection("themes").get().await()
        val map = mutableMapOf<String, ThemeStatsData>()
        for (doc in snap.documents) {
            val count = doc.getLong("ratingCount") ?: 0L
            val sum = doc.getDouble("ratingSum") ?: (doc.getLong("ratingSum")?.toDouble() ?: 0.0)
            val avg = doc.getDouble("ratingAvg") ?: (doc.getLong("ratingAvg")?.toDouble() ?: 0.0)
            map[doc.id] = ThemeStatsData(doc.id, sum, count, avg, 0)
        }
        map
    }

    suspend fun fetchThemeRatingState(themeId: String): Result<ThemeStatsData> = runCatching {
        require(isValidDocId(themeId)) { "معرف ثيم غير صالح" }
        val statsSnap = firestore.collection("themes").document(themeId).get().await()
        val count = statsSnap.getLong("ratingCount") ?: 0L
        val sum = statsSnap.getDouble("ratingSum") ?: (statsSnap.getLong("ratingSum")?.toDouble() ?: 0.0)
        val avg = statsSnap.getDouble("ratingAvg") ?: (statsSnap.getLong("ratingAvg")?.toDouble() ?: 0.0)
        val currentUid = auth.currentUser?.uid
        val myStars = if (currentUid != null) {
            val mySnap = firestore.collection("themes").document(themeId)
                .collection("ratings").document(currentUid).get().await()
            mySnap.getLong("stars")?.toInt() ?: 0
        } else {
            0
        }
        ThemeStatsData(themeId, sum, count, avg, myStars)
    }

    suspend fun submitThemeRating(themeId: String, stars: Int): Result<ThemeStatsData> = runCatching {
        val current = auth.currentUser ?: error("سجّل الدخول لتتمكن من التقييم")
        require(isValidDocId(themeId)) { "معرف ثيم غير صالح" }
        require(stars in 1..5) { "التقييم يجب أن يكون بين 1 و 5 نجوم" }

        val statsRef = firestore.collection("themes").document(themeId)
        val ratingRef = statsRef.collection("ratings").document(current.uid)

        firestore.runTransaction { tx ->
            val statsSnap = tx.get(statsRef)
            val existingRatingSnap = tx.get(ratingRef)

            val prevCount = if (statsSnap.exists()) (statsSnap.getLong("ratingCount") ?: 0L) else 0L
            val prevSum = if (statsSnap.exists()) {
                statsSnap.getDouble("ratingSum") ?: (statsSnap.getLong("ratingSum")?.toDouble() ?: 0.0)
            } else {
                0.0
            }
            val oldStars = if (existingRatingSnap.exists()) (existingRatingSnap.getLong("stars")?.toInt() ?: 0) else 0

            val newCount = if (oldStars > 0) prevCount else prevCount + 1
            val newSum = (prevSum - oldStars + stars).coerceAtLeast(0.0)
            val newAvg = if (newCount > 0L) {
                ((newSum / newCount.toDouble()) * 100.0).roundToInt() / 100.0
            } else {
                0.0
            }

            tx.set(
                ratingRef,
                hashMapOf<String, Any>(
                    "stars" to stars,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            tx.set(
                statsRef,
                hashMapOf<String, Any>(
                    "ratingSum" to newSum,
                    "ratingCount" to newCount,
                    "ratingAvg" to newAvg,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            ThemeStatsData(themeId, newSum, newCount, newAvg, stars)
        }.await()
    }

    suspend fun fetchCommentsPage(
        themeId: String,
        pageSize: Int = 20,
        cursorId: String? = null
    ): Result<CommentsPageData> = runCatching {
        require(isValidDocId(themeId)) { "معرف ثيم غير صالح" }
        val commentsCol = firestore.collection("themes").document(themeId).collection("comments")
        var query = commentsCol.orderBy("createdAt", Query.Direction.DESCENDING)

        if (!cursorId.isNullOrBlank()) {
            val cacheKey = "${themeId}::$cursorId"
            val cursorSnap = commentCursorCache[cacheKey] ?: commentsCol.document(cursorId).get().await()
            if (cursorSnap.exists()) {
                query = query.startAfter(cursorSnap)
            }
        }

        val snap = query.limit((pageSize + 1).toLong()).get().await()
        val hasMore = snap.documents.size > pageSize
        val pageDocs = if (hasMore) snap.documents.take(pageSize) else snap.documents
        pageDocs.lastOrNull()?.let { lastDoc ->
            commentCursorCache["${themeId}::${lastDoc.id}"] = lastDoc
        }

        val currentUid = auth.currentUser?.uid
        val likedFlags = if (currentUid != null && pageDocs.isNotEmpty()) {
            coroutineScope {
                pageDocs.map { doc ->
                    async {
                        runCatching {
                            doc.reference.collection("likes").document(currentUid).get().await().exists()
                        }.getOrDefault(false)
                    }
                }.awaitAll()
            }
        } else {
            List(pageDocs.size) { false }
        }

        val list = pageDocs.mapIndexed { idx, doc ->
            CommentData(
                id = doc.id,
                uid = doc.getString("uid") ?: "",
                name = doc.getString("name") ?: "مستخدم",
                photo = doc.getString("photo") ?: "",
                text = doc.getString("text") ?: "",
                createdAtMs = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
                likes = doc.getLong("likes") ?: 0L,
                likedByMe = likedFlags[idx]
            )
        }
        CommentsPageData(
            comments = list,
            hasMore = hasMore,
            nextCursorId = pageDocs.lastOrNull()?.id
        )
    }

    suspend fun addComment(themeId: String, rawText: String): Result<CommentData> = runCatching {
        val current = auth.currentUser ?: error("سجّل الدخول لإضافة تعليق")
        require(isValidDocId(themeId)) { "معرف ثيم غير صالح" }
        val text = cleanText(rawText, 500)
        require(text.isNotEmpty()) { "يرجى كتابة تعليق قبل الإرسال" }

        val userProfile = ensureUserProfile(current)
        require(!userProfile.blocked) { "حسابك محظور من التعليق" }

        val colRef = firestore.collection("themes").document(themeId).collection("comments")
        val newDocRef = colRef.document()
        val payload = hashMapOf<String, Any>(
            "uid" to current.uid,
            "name" to cleanText(userProfile.displayName, 60).ifBlank { "مستخدم" },
            "photo" to cleanText(userProfile.photoURL, 500),
            "text" to text,
            "createdAt" to FieldValue.serverTimestamp(),
            "likes" to 0L
        )
        newDocRef.set(payload).await()
        CommentData(
            id = newDocRef.id,
            uid = current.uid,
            name = userProfile.displayName,
            photo = userProfile.photoURL,
            text = text,
            createdAtMs = System.currentTimeMillis(),
            likes = 0L,
            likedByMe = false
        )
    }

    suspend fun toggleCommentLike(themeId: String, commentId: String): Result<Pair<Long, Boolean>> = runCatching {
        val current = auth.currentUser ?: error("سجّل الدخول للإعجاب بالتعليقات")
        require(isValidDocId(themeId) && isValidDocId(commentId)) { "معرف غير صالح" }

        val commentRef = firestore.collection("themes").document(themeId)
            .collection("comments").document(commentId)
        val likeRef = commentRef.collection("likes").document(current.uid)

        firestore.runTransaction { tx ->
            val commentSnap = tx.get(commentRef)
            if (!commentSnap.exists()) error("التعليق غير موجود")
            val currentLikes = commentSnap.getLong("likes") ?: 0L
            val likeSnap = tx.get(likeRef)

            if (likeSnap.exists()) {
                val nextLikes = (currentLikes - 1L).coerceAtLeast(0L)
                tx.delete(likeRef)
                tx.update(commentRef, "likes", nextLikes)
                nextLikes to false
            } else {
                val nextLikes = currentLikes + 1L
                tx.set(
                    likeRef,
                    hashMapOf<String, Any>(
                        "uid" to current.uid,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.update(commentRef, "likes", nextLikes)
                nextLikes to true
            }
        }.await()
    }

    suspend fun deleteComment(themeId: String, commentId: String): Result<Unit> = runCatching {
        require(auth.currentUser != null) { "يجب تسجيل الدخول أولاً" }
        require(isValidDocId(themeId) && isValidDocId(commentId)) { "معرف غير صالح" }
        firestore.collection("themes").document(themeId)
            .collection("comments").document(commentId).delete().await()
    }

    suspend fun reportContent(
        targetType: String,
        targetId: String,
        reportedUid: String,
        reason: String,
        textSnippet: String,
        themeId: String = "",
        conversationUid: String = ""
    ): Result<Unit> = runCatching {
        val current = auth.currentUser ?: error("سجّل الدخول للإبلاغ")
        require(targetType == "comment" || targetType == "message") { "نوع بلاغ غير صالح" }
        require(isValidDocId(targetId) && isValidDocId(reportedUid)) { "معرف غير صالح" }
        val cleanReason = cleanText(reason.ifBlank { "محتوى مخالف" }, 300)
        val cleanSnippet = cleanText(textSnippet, 300)

        val reportRef = firestore.collection("reports").document()
        val payload = hashMapOf<String, Any>(
            "targetType" to targetType,
            "targetId" to targetId,
            "themeId" to cleanText(themeId, 128),
            "conversationUid" to cleanText(conversationUid, 128),
            "reporterUid" to current.uid,
            "reportedUid" to reportedUid,
            "reason" to cleanReason,
            "textSnippet" to cleanSnippet,
            "createdAt" to FieldValue.serverTimestamp()
        )
        reportRef.set(payload).await()
    }

    fun observeInboxAndUnread(
        uid: String,
        isAdmin: Boolean,
        onUpdate: (JSONObject) -> Unit
    ): ListenerRegistration {
        return if (isAdmin) {
            firestore.collection("conversations")
                .orderBy("lastMessageAt", Query.Direction.DESCENDING)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        onUpdate(JSONObject().apply {
                            put("ok", false)
                            put("error", AuthErrorFormatter.format(err))
                        })
                        return@addSnapshotListener
                    }
                    var totalUnread = 0L
                    val items = JSONArray()
                    snap?.documents?.forEach { doc ->
                        val unreadAdmin = doc.getLong("unreadByAdmin") ?: 0L
                        totalUnread += unreadAdmin
                        items.put(JSONObject().apply {
                            put("uid", doc.getString("uid") ?: doc.id)
                            put("userName", doc.getString("userName") ?: "مستخدم")
                            put("userPhoto", doc.getString("userPhoto") ?: "")
                            put("lastMessage", doc.getString("lastMessage") ?: "")
                            put("lastMessageAt", doc.getTimestamp("lastMessageAt")?.toDate()?.time ?: 0L)
                            put("lastSenderId", doc.getString("lastSenderId") ?: "")
                            put("unreadCount", unreadAdmin)
                        })
                    }
                    onUpdate(JSONObject().apply {
                        put("ok", true)
                        put("isAdmin", true)
                        put("unreadTotal", totalUnread)
                        put("conversations", items)
                    })
                }
        } else {
            firestore.collection("conversations").document(uid)
                .addSnapshotListener { doc, err ->
                    if (err != null) {
                        onUpdate(JSONObject().apply {
                            put("ok", false)
                            put("error", AuthErrorFormatter.format(err))
                        })
                        return@addSnapshotListener
                    }
                    val unreadUser = if (doc != null && doc.exists()) (doc.getLong("unreadByUser") ?: 0L) else 0L
                    onUpdate(JSONObject().apply {
                        put("ok", true)
                        put("isAdmin", false)
                        put("unreadTotal", unreadUser)
                        put("conversations", JSONArray())
                    })
                }
        }
    }

    fun observeConversationMessages(
        conversationUid: String,
        viewerIsAdmin: Boolean,
        onMessages: (JSONObject) -> Unit
    ): ListenerRegistration {
        val convRef = firestore.collection("conversations").document(conversationUid)
        return convRef.collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limitToLast(120)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    onMessages(JSONObject().apply {
                        put("ok", false)
                        put("conversationUid", conversationUid)
                        put("error", AuthErrorFormatter.format(err))
                    })
                    return@addSnapshotListener
                }

                val myUid = auth.currentUser?.uid ?: ""
                val arr = JSONArray()
                var hasUnreadIncoming = false

                snap?.documents?.forEach { doc ->
                    val senderId = doc.getString("senderId") ?: ""
                    val isRead = doc.getBoolean("read") == true
                    val isIncoming = if (viewerIsAdmin) senderId == conversationUid else (senderId.isNotEmpty() && senderId != myUid)
                    if (isIncoming && !isRead) {
                        hasUnreadIncoming = true
                        doc.reference.update("read", true)
                    }
                    arr.put(JSONObject().apply {
                        put("id", doc.id)
                        put("text", doc.getString("text") ?: "")
                        put("senderId", senderId)
                        put("createdAt", doc.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis())
                        put("read", isRead)
                    })
                }

                if (hasUnreadIncoming) {
                    val unreadField = if (viewerIsAdmin) "unreadByAdmin" else "unreadByUser"
                    convRef.update(unreadField, 0L)
                }

                onMessages(JSONObject().apply {
                    put("ok", true)
                    put("conversationUid", conversationUid)
                    put("messages", arr)
                })
            }
    }

    suspend fun markConversationRead(conversationUid: String, viewerIsAdmin: Boolean) {
        if (!isValidDocId(conversationUid) || auth.currentUser == null) return
        val convRef = firestore.collection("conversations").document(conversationUid)
        val snap = runCatching { convRef.get().await() }.getOrNull() ?: return
        if (!snap.exists()) return
        val unreadField = if (viewerIsAdmin) "unreadByAdmin" else "unreadByUser"
        if ((snap.getLong(unreadField) ?: 0L) > 0L) {
            runCatching { convRef.update(unreadField, 0L).await() }
        }
    }

    suspend fun sendMessage(
        conversationUid: String,
        senderIsAdmin: Boolean,
        rawText: String
    ): Result<Unit> = runCatching {
        val current = auth.currentUser ?: error("سجّل الدخول لإرسال رسالة")
        require(isValidDocId(conversationUid)) { "معرف المحادثة غير صالح" }
        val text = cleanText(rawText, 1000)
        require(text.isNotEmpty()) { "يرجى كتابة نص الرسالة" }

        if (!senderIsAdmin) {
            require(conversationUid == current.uid) { "يمكن للمستخدمين مراسلة المشرف فقط" }
            val now = System.currentTimeMillis()
            val elapsed = now - lastNonAdminSentAtMs
            if (elapsed < 2000L) {
                error("يرجى الانتظار ثانيتين قبل إرسال رسالة أخرى")
            }
        }

        val ownerProfile = runCatching {
            val snap = firestore.collection("users").document(conversationUid).get().await()
            if (snap.exists()) parseUserSnapshot(snap, conversationUid) else null
        }.getOrNull()

        if (!senderIsAdmin && ownerProfile?.blocked == true) {
            error("حسابك محظور من إرسال الرسائل")
        }

        val convRef = firestore.collection("conversations").document(conversationUid)
        val existingConv = convRef.get().await()
        val msgRef = convRef.collection("messages").document()

        val displayName = cleanText(
            ownerProfile?.displayName ?: current.displayName ?: "مستخدم",
            60
        ).ifBlank { "مستخدم" }
        val photo = cleanText(ownerProfile?.photoURL ?: (current.photoUrl?.toString() ?: ""), 500)

        val prevUnreadAdmin = if (existingConv.exists()) (existingConv.getLong("unreadByAdmin") ?: 0L) else 0L
        val prevUnreadUser = if (existingConv.exists()) (existingConv.getLong("unreadByUser") ?: 0L) else 0L

        val convPayload = hashMapOf<String, Any>(
            "uid" to conversationUid,
            "userName" to displayName,
            "userPhoto" to photo,
            "lastMessage" to text.take(180),
            "lastMessageAt" to FieldValue.serverTimestamp(),
            "lastSenderId" to current.uid
        )

        if (senderIsAdmin) {
            convPayload["unreadByAdmin"] = 0L
            convPayload["unreadByUser"] = prevUnreadUser + 1L
        } else {
            convPayload["lastUserMessageAt"] = FieldValue.serverTimestamp()
            convPayload["unreadByAdmin"] = prevUnreadAdmin + 1L
            convPayload["unreadByUser"] = 0L
        }

        val msgPayload = hashMapOf<String, Any>(
            "text" to text,
            "senderId" to current.uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "read" to false
        )

        val batch = firestore.batch()
        if (existingConv.exists()) {
            batch.update(convRef, convPayload)
        } else {
            batch.set(convRef, convPayload)
        }
        batch.set(msgRef, msgPayload)
        batch.commit().await()

        if (!senderIsAdmin) {
            lastNonAdminSentAtMs = System.currentTimeMillis()
        }
    }

    companion object {
        private val ID_REGEX = Regex("^[a-zA-Z0-9_.\\-]+$")
    }
}
