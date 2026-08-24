package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.UserProfile
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserProfileRepository : JpaRepository<UserProfile, Long> {
    fun findByUserId(userId: Long): UserProfile?
}