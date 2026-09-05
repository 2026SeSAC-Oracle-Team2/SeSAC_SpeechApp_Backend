package com.sesac.speechapp.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * 리포트 백그라운드 생성용 비동기 설정 (D-5 [2.2]).
 *
 * - /report/problems (스텁 2~3초) / /report/total (스텁 10초)을 백그라운드 실행 —
 *   제출·finish 응답은 대기 없이 즉시 반환 (03a §7 계약).
 * - 스텁 sleep이 스레드를 블록하므로 단일 스레드면 total 10초가 problems를 블록 —
 *   코어 2/최대 4 풀로 동시 다중 세션 지원.
 */
@Configuration
@EnableAsync
class AsyncConfig {

    @Bean(name = ["sessionReportExecutor"])
    fun sessionReportExecutor(): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 4
        executor.queueCapacity = 50
        executor.setThreadNamePrefix("report-bg-")
        executor.initialize()
        return executor
    }
}