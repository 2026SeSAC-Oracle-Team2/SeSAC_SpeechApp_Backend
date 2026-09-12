package com.sesac.speechapp.service.container

import com.sesac.speechapp.config.ContainerProperties
import com.sesac.speechapp.dto.container.LlmGenerateRequest
import com.sesac.speechapp.dto.container.LlmGenerateResponse
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
 * 문제 생성 컨테이너(LLM Container) 연동 서비스
 *
 * 통신 패턴: REST API (WebClient 비동기 호출)
 *
 * 권장 이유:
 *   - Kafka/Queue는 인프라 복잡도가 높고, LLM 생성은 보통 1~3초 내 완료 → 동기/준동기 REST가 충분
 *   - DB 폴링은 지연(Latency)이 크고 불필요한 DB 부하 발생 → 비권장
 *   - WebClient 논블로킹 I/O로 컨테이너 응답 대기 중에도 서버 스레드를 반납 → 동시 처리량 ↑
 *
 * 트랜잭션 전략:
 *   - HTTP 호출은 반드시 @Transactional 바깥에서 수행 (외부 시스템 참여 트랜잭션 지양)
 *   - 성공 응답 수신 후 별도 @Transactional 메서드로 TURN INSERT 수행
 */
@Service
class AiContainerService(
    private val webClient: WebClient,
    private val containerProperties: ContainerProperties
) {
    private val logger = LoggerFactory.getLogger(AiContainerService::class.java)

    /**
     * 세션 생성 후 LLM 컨테이너를 호출해 첫 번째(또는 다음) 문제를 생성한다.
     *
     * @param sessionId 세션 PK
     * @param sessionType 세션 유형
     * @param level 사용자 레벨
     * @param language 언어 코드
     * @param context 이전 턴 맥락 (선택)
     * @return Mono<LlmGenerateResponse> — 구독 시 실제 HTTP 통신 발생
     */
    @Retryable(
        retryFor = [WebClientResponseException::class, java.util.concurrent.TimeoutException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 1000, multiplier = 2.0)  // 1s → 2s → 4s
    )
    fun generateProblem(
        sessionId: Long,
        sessionType: String,
        level: Int,
        language: String = "ko",
        context: List<LlmGenerateRequest.TurnContext>? = null
    ): Mono<LlmGenerateResponse> {
        val request = LlmGenerateRequest(
            sessionId = sessionId,
            sessionType = sessionType,
            level = level,
            language = language,
            context = context
        )

        val uri = containerProperties.llm.baseUrl + containerProperties.llm.generatePath
        val timeout = Duration.ofMillis(containerProperties.llm.timeoutMs)

        logger.info("[LLM] 문제 생성 요청: sessionId={}, type={}, level={}, uri={}",
            sessionId, sessionType, level, uri)

        return webClient.post()
            .uri(uri)
            .bodyValue(request)
            .retrieve()
            .onStatus({ status -> status.isError }) { response ->
                response.bodyToMono(String::class.java)
                    .flatMap { body ->
                        logger.error("[LLM] 컨테이너 오 응답: status={}, body={}", response.statusCode(), body)
                        Mono.error(
                            WebClientResponseException.create(
                                response.statusCode().value(),
                                "LLM Container error: $body",
                                response.headers().asHttpHeaders(),
                                body.toByteArray(),
                                null
                            )
                        )
                    }
            }
            .bodyToMono(LlmGenerateResponse::class.java)
            .timeout(timeout)
            .retryWhen(
                Retry.backoff(2, Duration.ofSeconds(1))
                    .filter { it is WebClientResponseException || it is java.util.concurrent.TimeoutException }
                    .doBeforeRetry { signal ->
                        logger.warn("[LLM] 재시도 {}/{}: {}", signal.totalRetries() + 1, 3, signal.failure().message)
                    }
            )
            .doOnSuccess { response ->
                if (response.status == "SUCCESS") {
                    logger.info("[LLM] 문제 생성 성공: sessionId={}, turnNumber={}, contentType={}",
                        response.sessionId, response.turnNumber, response.contentType)
                } else {
                    logger.error("[LLM] 문제 생성 실패 (컨테이너 내부 오류): sessionId={}, error={}",
                        response.sessionId, response.errorMessage)
                }
            }
            .doOnError { error ->
                logger.error("[LLM] 문제 생성 최종 실패: sessionId={}, error={}", sessionId, error.message, error)
            }
    }
}
