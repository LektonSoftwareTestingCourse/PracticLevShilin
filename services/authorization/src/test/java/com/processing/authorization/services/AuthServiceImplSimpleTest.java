package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplSimpleTest {
    private static final String PAN = "4000001234567890";
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-01-31T23:59:59Z");
    @Mock private LimitUsageRepository limits;
    @Mock private AuthorizationEventNotifier events;
    @Mock private BinLookupClient bins;
    @Mock private CardManagementClient cards;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(limits, events, bins, cards);
    }

    @ParameterizedTest
    @ValueSource(strings = {"99.99", "100.00"})
    void shouldApproveWhenAmountDoesNotExceedBalance(String amount) {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 1));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        AuthorizationRequest request = request(amount);
        // Act
        AuthorizationResponse response = service.authorize(request, Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).isNotBlank();
        assertThat(response.authCode()).matches("[A-Z0-9]{6}");
        verify(cards).reserve(request.amount(), response.rrn(), PAN);
        verify(events).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,CARD_BLOCKED,05", "EXPIRED,CARD_EXPIRED,54", "INACTIVE,CARD_INACTIVE,05"})
    void shouldDeclineWhenCardIsNotActive(CardModelStatus status, String reason, String code) {
        // Arrange
        availableCard(status, YearMonth.of(2026, 1));
        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());
        // Assert
        assertDeclined(response, reason, code);
        verify(cards, never()).reserve(any(), any(), any());
        verify(events).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenCardNotFound() {
        // Arrange
        when(cards.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));
        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());
        // Assert
        assertDeclined(response, "CARD_NOT_FOUND", "14");
        verify(events).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenAmountExceedsBalance() {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 1));
        // Act
        AuthorizationResponse response = service.authorize(request("100.01"), Instant.now());
        // Assert
        assertDeclined(response, "INSUFFICIENT_FUNDS", "51");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenExpiryMonthIsBeforeTransaction() {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2025, 12));
        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());
        // Assert
        assertDeclined(response, "CARD_EXPIRED", "54");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenLimitCannotBeUpdated() {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 1));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);
        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());
        // Assert
        assertDeclined(response, "EXCEEDS_AMOUNT_LIMIT", "61");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldApproveWhenLimitUpdateSucceedsAfterDuplicate() {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 1));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("concurrent insert")).thenReturn(1);
        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(cards).reserve(new BigDecimal("100"), response.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenDatabaseFails() {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 1));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database offline"));
        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());
        // Assert
        assertDeclined(response, "DB_UNAVAILABLE", "96");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWhenCardDisappearsDuringReservation() {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 1));
        when(limits.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        doThrow(new CardNotFoundException(PAN)).when(cards).reserve(any(), any(), eq(PAN));
        // Act
        AuthorizationResponse response = service.authorize(request("100"), Instant.now());
        // Assert
        assertDeclined(response, "CARD_NOT_FOUND", "14");
    }

    @Test
    void shouldApproveRollbackWhenClientSucceeds() {
        // Arrange
        RollbackRequest request = new RollbackRequest("123456789012", PAN, new BigDecimal("100"));
        // Act
        var response = service.rollback(request, Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.rrn()).isEqualTo(request.rrn());
        verify(cards).rollback(request);
        verify(events).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineRollbackWhenCardNotFound() {
        // Arrange
        RollbackRequest request = new RollbackRequest("123456789012", PAN, new BigDecimal("100"));
        doThrow(new CardNotFoundException(PAN)).when(cards).rollback(request);
        // Act
        var response = service.rollback(request, Instant.now());
        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("TRANSACTION_NOT_FOUND");
    }

    private void availableCard(CardModelStatus status, YearMonth expiry) {
        when(cards.getCard(PAN)).thenReturn(new CardModel(new UUID(0, 1), PAN, "400000", "IVAN IVANOV",
                expiry, status, "643", new BigDecimal("1000"), new BigDecimal("5000"),
                new BigDecimal("100"), "ISS001", TRANSACTION_TIME));
        when(bins.getIssuerId(PAN)).thenReturn(Optional.of("ISS001"));
    }

    private AuthorizationRequest request(String amount) {
        return new AuthorizationRequest("0100", "000001", PAN, "000000", new BigDecimal(amount), "643",
                TRANSACTION_TIME, "TERM0001", "POS", "MERCH00000000002", "5045", "ACQ001", "ISS001");
    }

    private void assertDeclined(AuthorizationResponse response, String reason, String code) {
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
    }
}
