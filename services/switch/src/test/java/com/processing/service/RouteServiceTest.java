package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {
    @Mock private RoutingService routing;
    @Mock private AuthorizationClient authorization;
    @Mock private AcquiringFeeClient fees;
    @Mock private LoggerClient logger;
    private RouteService service;

    @BeforeEach
    void setUp() {
        service = new RouteService(routing, authorization, fees, logger);
    }

    @ParameterizedTest
    @MethodSource("authorizationResponses")
    void shouldPublishTransactionWhenAuthorizationResponds(AuthorizationResponse expected) {
        // Arrange
        AuthorizationRequest request = request("4000001234567890");
        when(routing.getIssuerIdByPan(request.pan())).thenReturn("ISS001");
        when(authorization.authorize(any())).thenReturn(expected);
        when(fees.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("2"));
        when(logger.log(any())).thenReturn(true);
        // Act
        AuthorizationResponse result = service.route(request);
        // Assert
        assertThat(result).isSameAs(expected);
        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(logger).log(transaction.capture());
        assertThat(transaction.getValue()).extracting("issuerId", "terminalId", "merchantId", "acquiringFee")
                .containsExactly("ISS001", "TERM0000", "SHOP00000000000", new BigDecimal("2"));
        assertThat(transaction.getValue().status().name()).isEqualTo(expected.status());
        verify(authorization, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "9999991234567890"})
    void shouldDeclineWhenBinIsUnknown(String pan) {
        // Arrange
        when(routing.getIssuerIdByPan(pan)).thenThrow(new UnknownBinException("unknown"));
        when(logger.log(any())).thenReturn(true);
        // Act
        AuthorizationResponse result = service.route(request(pan));
        // Assert
        assertThat(result.responseCode()).isEqualTo("14");
        assertThat(result.status()).isEqualTo("DECLINED");
        verify(logger).log(any());
        verify(authorization, never()).rollback(any(), any());
    }

    @Test
    void shouldDeclineWhenAuthorizationIsUnavailable() {
        // Arrange
        when(routing.getIssuerIdByPan(any())).thenReturn("ISS001");
        when(authorization.authorize(any())).thenThrow(new AuthorizationException("000001", 1, "offline"));
        when(logger.log(any())).thenReturn(true);
        // Act
        AuthorizationResponse result = service.route(request("4000001234567890"));
        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.declineReason()).isEqualTo("Authorization service unavailable");
        verify(logger).log(any());
    }

    @ParameterizedTest
    @MethodSource("rollbackResponses")
    void shouldRollbackWhenPublishingApprovedTransactionFails(RollbackResponse rollback) {
        // Arrange
        when(routing.getIssuerIdByPan(any())).thenReturn("ISS001");
        when(authorization.authorize(any())).thenReturn(approved());
        when(authorization.rollback(any(), eq("123456789012"))).thenReturn(rollback);
        // Act
        AuthorizationResponse result = service.route(request("4000001234567890"));
        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("96");
        verify(authorization).rollback(argThat(r -> "ISS001".equals(r.issuerId())), eq("123456789012"));
    }

    @Test
    void shouldKeepDeclineWhenPublishingDeclinedTransactionFails() {
        // Arrange
        when(routing.getIssuerIdByPan(any())).thenReturn("ISS001");
        AuthorizationResponse declined = AuthorizationResponse.unknownBin("000001");
        when(authorization.authorize(any())).thenReturn(declined);
        // Act
        AuthorizationResponse result = service.route(request("4000001234567890"));
        // Assert
        assertThat(result).isSameAs(declined);
        verify(authorization, never()).rollback(any(), any());
    }

    private static Stream<AuthorizationResponse> authorizationResponses() {
        return Stream.of(approved(), AuthorizationResponse.unknownBin("000001"),
                new AuthorizationResponse("0110", "000001", "123456789012", "ABC123", "00",
                        "APPROVED", null, null));
    }

    private static Stream<RollbackResponse> rollbackResponses() {
        return Stream.of(null, new RollbackResponse("123456789012", "00", "APPROVED", null, 0),
                new RollbackResponse("123456789012", "05", "DECLINED", "FAILED", 0));
    }

    private static AuthorizationResponse approved() {
        return new AuthorizationResponse("0110", "000001", "123456789012", "ABC123", "00", "APPROVED", null, 1);
    }

    private AuthorizationRequest request(String pan) {
        return new AuthorizationRequest("0100", "000001", pan, "000000", new BigDecimal("100"), "643",
                Instant.parse("2026-01-15T12:00:00Z"), "TERM", "POS", "SHOP", "5045", "ACQ001", null);
    }
}
