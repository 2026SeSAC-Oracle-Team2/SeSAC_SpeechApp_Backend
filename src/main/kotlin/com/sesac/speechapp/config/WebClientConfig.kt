package com.sesac.speechapp.config

import io.netty.channel.ChannelOption
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * WebClient(WebFlux) 빈 설정 — 컨테이너 비동기 호출용
 *
 * 권장 이유:
 * - RestTemplate은 Spring 6+부터 deprecated 되었으며, WebClient가 공식 대체재
 * - 논블로킹 I/O로 컨테이너 호출 지연 시에도 톰캣 스레드 반납 가능 → 처리량 ↑
 * - reactive stream 기반으로 타임아웃/재시도/에러 핸들링이 선언적
 */
@Configuration
class WebClientConfig {

    @Bean
    fun webClient(): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)   // TCP 연결 타임아웃
            .responseTimeout(Duration.ofSeconds(60))              // 전체 응답 타임아웃

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { configurer ->
                // 컨테이너가 Base64 음성을 포함할 수 있으므로 버퍼 늘림
                configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)
            }
            .build()
    }
}
