package com.sesac.speechapp.dto

import com.fasterxml.jackson.annotation.JsonProperty

// POST /api/v1/auth/firebase 요청
class FirebaseAuthRequest(
    @JsonProperty("id_token")
    val idToken: String
)
