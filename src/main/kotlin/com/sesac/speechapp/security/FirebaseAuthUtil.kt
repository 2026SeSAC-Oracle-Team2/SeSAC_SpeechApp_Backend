package com.sesac.speechapp.security

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.FileInputStream

@Component
class FirebaseAuthUtil(
    @Value("\${firebase.admin-sdk-path}")
    private val adminSdkPath: String
) {
    private val logger = LoggerFactory.getLogger(FirebaseAuthUtil::class.java)

    @PostConstruct
    fun initializeFirebase() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                val serviceAccount = FileInputStream(adminSdkPath)
                val options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build()
                FirebaseApp.initializeApp(options)
                logger.info("Firebase Admin SDK 초기화 완료")
            }
        } catch (e: Exception) {
            logger.error("Firebase Admin SDK 초기화 실패: ${e.message}", e)
            throw e
        }
    }

    fun verifyIdToken(idToken: String): FirebaseToken? {
        return try {
            FirebaseAuth.getInstance().verifyIdToken(idToken)
        } catch (e: FirebaseAuthException) {
            logger.error("Firebase ID Token 검증 실패: ${e.message}")
            null
        }
    }

    fun getUid(idToken: String): String? {
        return verifyIdToken(idToken)?.uid
    }

    fun getEmail(idToken: String): String? {
        return verifyIdToken(idToken)?.email
    }
}