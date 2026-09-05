package com.sesac.speechapp.exception

import com.sesac.speechapp.dto.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ApiResponse<Nothing>> {
        logger.warn("잘못된 요청: ${e.message}")
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error("E0400", e.message ?: "잘못된 요청입니다."))
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ResponseEntity<ApiResponse<Nothing>> {
        logger.warn("상태 오류: ${e.message}")
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error("E0401", e.message ?: "처리할 수 없는 상태입니다."))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(e: NoResourceFoundException): ResponseEntity<ApiResponse<Nothing>> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error("E0404", "요청한 리소스를 찾을 수 없습니다."))
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFoundElement(e: NoSuchElementException): ResponseEntity<ApiResponse<Nothing>> {
        logger.warn("리소스 없음: ${e.message}")
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error("E0404", e.message ?: "요청한 리소스를 찾을 수 없습니다."))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception): ResponseEntity<ApiResponse<Nothing>> {
        logger.error("서버 내부 오류: ${e.message}", e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("E0500", "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요."))
    }
}