package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizationClientTest {
    @Mock private RestClient rest;
    @Mock private RestClient.RequestBodyUriSpec post;
    @Mock private RestClient.ResponseSpec response;
    private CircuitBreaker breaker;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        breaker = CircuitBreaker.ofDefaults("unit");
        Retry retry = Retry.of("unit", RetryConfig.custom().maxAttempts(1).build());
        SwitchProperties properties = new SwitchProperties("test", Map.of(), "http://authorization",
                null, null, null, new SwitchProperties.RetryProperties(1, List.of()), null);
        client = new AuthorizationClient(properties, rest, retry, breaker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnResponseWhenAuthorizationStatusIsValid(String status) {
        // Arrange
        stubPost();
        var expected = new AuthorizationResponse("0110", "000001", null, null, "00", status, null, 0);
        when(response.body(AuthorizationResponse.class)).thenReturn(expected);
        // Act
        var result = client.authorize(request());
        // Assert
        assertThat(result).isSameAs(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PENDING", ""})
    void shouldRejectWhenAuthorizationStatusIsInvalid(String status) {
        // Arrange
        stubPost();
        when(response.body(AuthorizationResponse.class)).thenReturn(
                new AuthorizationResponse("0110", "000001", null, null, "00", status, null, 0));
        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response");
    }

    @Test
    void shouldRejectWhenAuthorizationBodyIsMissing() {
        // Arrange
        stubPost();
        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void shouldRejectWhenCircuitIsOpen() {
        // Arrange
        breaker.transitionToOpenState();
        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
    }

    @Test
    void shouldExposeCauseWhenClientFails() {
        // Arrange
        when(rest.post()).thenThrow(new IllegalStateException("outer", new IllegalArgumentException("offline")));
        // Act / Assert
        assertThatThrownBy(() -> client.authorize(request())).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("offline");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnRollbackWhenStatusIsValid(String status) {
        // Arrange
        stubPost();
        var expected = new RollbackResponse("123456789012", "00", status, null, 0);
        when(response.body(RollbackResponse.class)).thenReturn(expected);
        // Act
        var result = client.rollback(request(), "123456789012");
        // Assert
        assertThat(result).isSameAs(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PENDING", ""})
    void shouldReturnNullWhenRollbackStatusIsInvalid(String status) {
        // Arrange
        stubPost();
        when(response.body(RollbackResponse.class))
                .thenReturn(new RollbackResponse("123456789012", "00", status, null, 0));
        // Act
        var result = client.rollback(request(), "123456789012");
        // Assert
        assertThat(result).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackBodyIsMissing() {
        // Arrange
        stubPost();
        // Act
        var result = client.rollback(request(), "123456789012");
        // Assert
        assertThat(result).isNull();
    }

    private void stubPost() {
        when(rest.post()).thenReturn(post);
        when(post.uri(anyString())).thenReturn(post);
        when(post.body(any(Object.class))).thenReturn(post);
        when(post.retrieve()).thenReturn(response);
        when(response.onStatus(any(), any())).thenReturn(response);
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest("0100", "000001", "4000001234567890", "000000",
                BigDecimal.TEN, "643", Instant.parse("2026-01-01T00:00:00Z"),
                "TERM0001", "POS", "MERCH00000000002", "5045", "ACQ001", "ISS001");
    }
}
