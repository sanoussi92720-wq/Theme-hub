package com.example

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CommunityRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureAndUpdateProfile_respectsLimitsAndPreventsUnauthenticatedWrites() = runBlocking {
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        val aliceUid = signInTestUser("alice_$uniqueSuffix@test.com")
        val repo = FirebaseCommunityRepository(firestore, auth)

        val profile = withTimeout(TIMEOUT_MS) { repo.ensureUserProfile(checkNotNull(auth.currentUser)) }
        assertEquals(aliceUid, profile.uid)
        assertEquals("user", profile.role)
        assertFalse(profile.blocked)

        val updated = withTimeout(TIMEOUT_MS) {
            repo.updateOwnProfile("أليس المصممة", "نبذة قصيرة للتجربة").getOrThrow()
        }
        assertEquals("أليس المصممة", updated.displayName)
        assertEquals("نبذة قصيرة للتجربة", updated.bio)

        auth.signOut()
        val unauthUpdate = withTimeout(TIMEOUT_MS) {
            repo.updateOwnProfile("اختراق", "غير مصرح")
        }
        assertTrue(unauthUpdate.isFailure)
    }

    @Test
    fun submitRating_atomicTransactionUpdatesAverageAndRejectsGuest() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val themeId = "theme_rate_$suffix"
        val repo = FirebaseCommunityRepository(firestore, auth)

        signInTestUser("rater1_$suffix@test.com")
        withTimeout(TIMEOUT_MS) { repo.ensureUserProfile(checkNotNull(auth.currentUser)) }
        val r1 = withTimeout(TIMEOUT_MS) { repo.submitThemeRating(themeId, 5).getOrThrow() }
        assertEquals(1L, r1.ratingCount)
        assertEquals(5.0, r1.ratingAvg, 0.01)

        val r1Edit = withTimeout(TIMEOUT_MS) { repo.submitThemeRating(themeId, 3).getOrThrow() }
        assertEquals(1L, r1Edit.ratingCount)
        assertEquals(3.0, r1Edit.ratingAvg, 0.01)

        signInTestUser("rater2_$suffix@test.com")
        withTimeout(TIMEOUT_MS) { repo.ensureUserProfile(checkNotNull(auth.currentUser)) }
        val r2 = withTimeout(TIMEOUT_MS) { repo.submitThemeRating(themeId, 5).getOrThrow() }
        assertEquals(2L, r2.ratingCount)
        assertEquals(4.0, r2.ratingAvg, 0.01)

        auth.signOut()
        val guestAttempt = withTimeout(TIMEOUT_MS) { repo.submitThemeRating(themeId, 4) }
        assertTrue(guestAttempt.isFailure)

        val publicStats = withTimeout(TIMEOUT_MS) { repo.fetchThemeRatingState(themeId).getOrThrow() }
        assertEquals(2L, publicStats.ratingCount)
        assertEquals(4.0, publicStats.ratingAvg, 0.01)
    }

    @Test
    fun comments_addLikeReportAndAdminDeleteRespectsPermissions() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val themeId = "theme_comments_$suffix"
        val repo = FirebaseCommunityRepository(firestore, auth)

        val aliceUid = signInTestUser("commenter_$suffix@test.com")
        withTimeout(TIMEOUT_MS) { repo.ensureUserProfile(checkNotNull(auth.currentUser)) }
        val added = withTimeout(TIMEOUT_MS) {
            repo.addComment(themeId, "ثيم رائع جداً وألوانه فخمة!").getOrThrow()
        }

        val (likesCount, likedByMe) = withTimeout(TIMEOUT_MS) {
            repo.toggleCommentLike(themeId, added.id).getOrThrow()
        }
        assertEquals(1L, likesCount)
        assertTrue(likedByMe)

        val bobUid = signInTestUser("other_$suffix@test.com")
        withTimeout(TIMEOUT_MS) { repo.ensureUserProfile(checkNotNull(auth.currentUser)) }
        val forbiddenDelete = withTimeout(TIMEOUT_MS) { repo.deleteComment(themeId, added.id) }
        assertTrue(forbiddenDelete.isFailure)
        val fsErr = checkNotNull(forbiddenDelete.exceptionOrNull()?.unwrapFirestoreException())
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr.code)

        // Bob can report Alice's comment
        val reportResult = withTimeout(TIMEOUT_MS) {
            repo.reportContent("comment", added.id, aliceUid, "بلاغ تجريبي", added.text, themeId = themeId)
        }
        assertTrue(reportResult.isSuccess)

        // Promote Bob via emulator REST API to server-verified admin -> can delete and block
        promoteUserToAdminViaEmulator(bobUid)
        val adminDelete = withTimeout(TIMEOUT_MS) { repo.deleteComment(themeId, added.id) }
        assertTrue(adminDelete.isSuccess)
        val blockAlice = withTimeout(TIMEOUT_MS) { repo.setUserBlocked(aliceUid, true) }
        assertTrue(blockAlice.isSuccess)

        // Blocked Alice cannot comment anymore
        signInTestUser("commenter_$suffix@test.com")
        val blockedAttempt = withTimeout(TIMEOUT_MS) {
            repo.addComment(themeId, "محاولة بعد الحظر")
        }
        assertTrue(blockedAttempt.isFailure)
    }

    @Test
    fun privateMessaging_isolatesUsersAndAllowsAdminReply() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val repo = FirebaseCommunityRepository(firestore, auth)

        val aliceUid = signInTestUser("msg_alice_$suffix@test.com")
        withTimeout(TIMEOUT_MS) { repo.ensureUserProfile(checkNotNull(auth.currentUser)) }
        val sendRes = withTimeout(TIMEOUT_MS) {
            repo.sendMessage(aliceUid, senderIsAdmin = false, rawText = "مرحباً أيها المشرف")
        }
        assertTrue(sendRes.isSuccess)

        // Second immediate send (< 2s) is rejected
        val rapidSpam = withTimeout(TIMEOUT_MS) {
            repo.sendMessage(aliceUid, senderIsAdmin = false, rawText = "رسالة متكررة سريعة")
        }
        assertTrue(rapidSpam.isFailure)

        // Another user cannot send into Alice's conversation
        val otherUid = signInTestUser("msg_bob_$suffix@test.com")
        withTimeout(TIMEOUT_MS) { repo.ensureUserProfile(checkNotNull(auth.currentUser)) }
        val crossUserSend = withTimeout(TIMEOUT_MS) {
            repo.sendMessage(aliceUid, senderIsAdmin = false, rawText = "تطفل")
        }
        assertTrue(crossUserSend.isFailure)

        // Admin (otherUid promoted on server) can reply to Alice
        promoteUserToAdminViaEmulator(otherUid)
        val adminReply = withTimeout(TIMEOUT_MS) {
            repo.sendMessage(aliceUid, senderIsAdmin = true, rawText = "أهلاً بك، تم الرد من المشرف")
        }
        assertTrue(adminReply.isSuccess)
        assertNotNull(withTimeout(TIMEOUT_MS) { repo.getPrimaryAdminProfile().getOrNull() })
    }

    private companion object {
        const val TIMEOUT_MS = 5000L
    }
}
