package com.processing.gateway.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionValidationFilterTest {
    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private final GatewayFilterChain chain = mock(GatewayFilterChain.class);
    private final TransactionValidationFilter filter = new TransactionValidationFilter(
            new ObjectMapper().findAndRegisterModules(), new TransactionRequestValidator(), metrics);

    @ParameterizedTest
    @ValueSource(strings = {"", "{broken", "{}"})
    void shouldReturnBadRequestWhenBodyIsInvalid(String body) {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions").body(body));
        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        // Assert
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("VALIDATION_ERROR");
        verify(chain, never()).filter(any());
    }

    @Test
    void shouldContinueWhenRequestIsValid() {
        // Arrange
        String body = """
                {"mti":"0100","stan":"000001","pan":"4000001234567890","processingCode":"000000",
                "amount":100,"currencyCode":"643","transmissionDateTime":"2026-01-01T00:00:00Z",
                "terminalId":"TERM0001","terminalType":"POS","merchantId":"MERCH00000000002",
                "mcc":"5045","acquirerId":"ACQ001"}
                """;
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions").body(body));
        when(chain.filter(any())).thenReturn(Mono.empty());
        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        // Assert
        verify(chain).filter(any());
        verifyNoInteractions(metrics);
    }

    @Test
    void shouldSkipValidationWhenRequestIsNotTransaction() {
        // Arrange
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/health"));
        when(chain.filter(exchange)).thenReturn(Mono.empty());
        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        // Assert
        verify(chain).filter(exchange);
        verifyNoInteractions(metrics);
    }
}
