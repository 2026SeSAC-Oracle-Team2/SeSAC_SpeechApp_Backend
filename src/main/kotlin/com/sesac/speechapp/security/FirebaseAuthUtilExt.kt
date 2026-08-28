package com.sesac.speechapp.security

import com.google.firebase.auth.FirebaseAuth
import org.slf4j.LoggerFactory

/**
 * Firebase Auth 계정 삭제 확장.
 *
 * - uid가 null이면(소셜 전용 가입 등) 삭제 대상이 없으므로 로그만 남기고 통과한다.
 * - 삭제 실패 시에도 예외를 던지지 않는다 — DB hard delete 이후에는 재시도 불가이며,
 *   잔존 Firebase 계정은 운영 정리 대상(로그로 추적)이기 때문이다.
 */
private val logger = LoggerFactory.getLogger("FirebaseAuthUtil")

fun FirebaseAuth.deleteUserByUid(firebaseUid: String?) {
    if (firebaseUid.isNullOrBlank()) {
        logger.info("Firebase 삭제 생략: uid 없음 (소셜 전용 계정 또는 미연동)")
        return
    }
    try {
        this.deleteUser(firebaseUid)
        logger.info("Firebase 사용자 삭제 완료: uid={}", firebaseUid)
    } catch (e: Exception) {
        logger.warn("Firebase 사용자 삭제 실패 (DB 삭제는 유지됨): uid={}, error={}", firebaseUid, e.message)
    }
}