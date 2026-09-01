package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.ContentType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ContentTypeRepository : JpaRepository<ContentType, String>
