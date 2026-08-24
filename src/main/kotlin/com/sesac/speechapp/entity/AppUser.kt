package com.sesac.speechapp.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "app_user")
class AppUser(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "uuid", nullable = false, unique = true, updatable = false)
    val uuid: String = UUID.randomUUID().toString(),

    @Column(name = "firebase_uid", nullable = false, unique = true, length = 128)
    val firebaseUid: String,

    @Column(name = "email", nullable = false, unique = true, length = 255)
    val email: String,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    @OneToOne(mappedBy = "user", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    var profile: UserProfile? = null

    constructor() : this(null, "", "", "")
}
