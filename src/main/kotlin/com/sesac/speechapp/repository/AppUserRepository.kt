package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.AppUser
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByFirebaseUid(firebaseUid: String): AppUser?
    fun findByEmail(email: String): AppUser?
    fun findByUuid(uuid: String): AppUser?
    fun existsByFirebaseUid(firebaseUid: String): Boolean
    fun existsByEmail(email: String): Boolean
    fun existsByUuid(uuid: String): Boolean
}