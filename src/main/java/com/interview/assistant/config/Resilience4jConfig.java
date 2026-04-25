package com.interview.assistant.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j 全局配置
 *
 * RetryRegistry 由 Spring Boot Resilience4jAutoConfiguration 自动从 application.yml 创建，
 * 此处仅提供 CircuitBreaker 和 TimeLimiter 的额外定制。
 */
@Slf4j
@Configuration
public class Resilience4jConfig {

    @Bean
    public CircuitBreaker defaultCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreaker cb = registry.circuitBreaker("defaultCircuitBreaker");
        cb.getEventPublisher()
                .onStateTransition(event -> log.warn("[CircuitBreaker] state transition: {} → {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()))
                .onFailureRateExceeded(event -> log.error("[CircuitBreaker] 失败率超限: {}%",
                        event.getFailureRate()));
        return cb;
    }

    @Bean
    public TimeLimiter defaultTimeLimiter(TimeLimiterRegistry registry) {
        return registry.timeLimiter("defaultTimeLimiter");
    }
}
