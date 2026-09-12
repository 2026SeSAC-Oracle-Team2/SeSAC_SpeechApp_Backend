package com.sesac.speechapp.service.container

import com.sesac.speechapp.config.ContainerProperties
import com.sesac.speechapp.dto.container.ScoringRequest
import com.sesac.speechapp.dto.container.ScoringResponse
import org.slf4j.LoggerFactory
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration

/**
 * 채점 컨테이너(Scoring Container) 연동 서비스
 *
 * 통신 패턴: REST API (WebClient 비동기 호출)
 *
 * 흐름:
 *   1) VOICE_RECORD INSERT (DB 트랜잭션 완료)
 *   2) OCI Pre-Authenticated URL 생성
 *   3) 컨테이너 호출 (HTTP POST with audioUrl)
 *   4) 응답으로 VOICE_RECORD 지표 UPDATE + TURN score UPDATE
 *
 * 주의: HTTP 호출은 트랜잭션 밖에서, DB UPDATE는 트랜잭션 안에서 수행.
 */
@Service
class ScoringContainerService(
    private val webClient: WebClient,
    private val containerProperties: ContainerProperties
) {
    private val logger = LoggerFactory.getLogger(ScoringContainerService::class.java)

    /**
     * 채점 컨테이너에 음성 파일 URL을 전달하고 채점 결과를 받는다.
     *
     * @param voiceRecordId VoiceRecord PK
     * @param promptText 해당 턴의 문제 본문
     * @param audioUrl OCI Object Storage Pre-Authenticated URL
     * @param audioContentType Content-Type (기본 audio/mp4)
     * @param sessionType 세션 유형
     * @param expectedAnswer 정답/기대 응답 (선택)
     * @return Mono<ScoringResponse>
     */
    @Retryable(
        retryFor = [WebClientResponseException::class, java.util.concurrent.TimeoutException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 1500, multiplier = 2.0)
    )
    fun requestScoring(
        voiceRecordId: Long,
        promptText: String,
        audioUrl: String,
        audioContentType: String = "audio/mp4",
        sessionType: String,
        expectedAnswer: String? = null
    ): Mono<ScoringResponse> {
        val request = ScoringRequest(
            voiceRecordId = voiceRecordId,
            promptText = promptText,
            audioUrl = audioUrl,
            audioContentType = audioContentType,
            sessionType = sessionType,
            expectedAnswer = expectedAnswer
        )

        val uri = containerProperties.scoring.baseUrl + containerProperties.scoring.scorePath
        val timeout = Duration.ofMillis(containerProperties.scoring.timeoutMs)

        logger.info("[SCORING] 채점 요청: voiceRecordId={}, audioUrlLen={}, uri={}",
            voiceRecordId, audioUrl.length, uri)

        return webClient.post()
            .uri(uri)
            .bodyValue(request)
            .retrieve()
            .onStatus({ status -> status.isError }) { response ->
                response.bodyToMono(String::class.java)
                    .flatMap { body ->
                        logger.error("[SCORING] 컨테이너 오 응답: status={}, body={}", response.statusCode(), body)
                        Mono.error(
                            WebClientResponseException.create(
                                response.statusCode().value(),
                                "Scoring Container error: $body",
                                response.headers().asHttpHeaders(),
                                body.toByteArray(),
                                null
                            )
                        )
                    }
            }
            .bodyToMono(ScoringResponse::class.java)
            .timeout(timeout)
            .retryWhen(
                Retry.backoff(2, Duration.ofSeconds(1))
                    .filter { it is WebClientResponseException || it is java.util.concurrent.TimeoutException }
                    .doBeforeRetry { signal ->
                        logger.warn("[SCORING] 재시도 {}/{}: {}", signal.totalRetries() + 1, 3, signal.failure().message)
                    }
            )
            .doOnSuccess { response ->
                if (response.status == "SUCCESS") {
                    logger.info("[SCORING] 채점 성공: voiceRecordId={}, overallScore={}, syllables={}",
                        response.voiceRecordId, response.overallScore, response.syllables)
                } else {
                    logger.error("[SCORING] 채점 실패 (컨테이너 내부 오류): voiceRecordId={}, error={}",
                        response.voiceRecordId, response.errorMessage)
                }
            }
            .doOnError { error ->
                logger.error("[SCORING] 채점 최종 실패: voiceRecordId={}, error={}", voiceRecordId, error.message, error)
            }
    }
}
