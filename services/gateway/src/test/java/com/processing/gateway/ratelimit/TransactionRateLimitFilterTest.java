package com.processing.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionRateLimitFilterTest {
    private final InMemoryRateLimiter limiter = mock(InMemoryRateLimiter.class);
    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private final GatewayFilterChain chain = mock(GatewayFilterChain.class);
    private final TransactionRateLimitFilter filter = new TransactionRateLimitFilter(
            limiter, new ClientIpResolver(), new ObjectMapper(), metrics);

    @Test
    void shouldReturnTooManyRequestsWhenLimitIsExhausted() {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions"));
        when(limiter.allowRequest("POST /api/transactions:unknown")).thenReturn(false);
        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        // Assert
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("RATE_LIMIT_EXCEEDED");
        verify(metrics).recordRateLimitRejected();
        verify(chain, never()).filter(any());
    }

    @Test
    void shouldContinueWhenLimitAllowsRequest() {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions"));
        when(limiter.allowRequest("POST /api/transactions:unknown")).thenReturn(true);
        when(chain.filter(exchange)).thenReturn(Mono.empty());
        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        // Assert
        verify(chain).filter(exchange);
        verifyNoInteractions(metrics);
    }
}
