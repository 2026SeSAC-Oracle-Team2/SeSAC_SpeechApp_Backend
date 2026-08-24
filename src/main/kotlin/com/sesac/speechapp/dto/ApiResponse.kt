package com.sesac.speechapp.dto

import java.time.Instant

class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
    val timestamp: Instant = Instant.now()
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(true, data = data)
        fun <T> error(code: String, message: String, detail: String? = null): ApiResponse<T> =
            ApiResponse(false, error = ApiError(code, message, detail))
    }
}

class ApiError(
    val code: String,
    val message: String,
    val detail: String?
)