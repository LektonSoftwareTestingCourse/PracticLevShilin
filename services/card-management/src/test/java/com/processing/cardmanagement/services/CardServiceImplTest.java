package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEvent;
import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {
    private static final String PAN = "4000001234567890";
    @Mock private CardRepository cards;
    @Mock private ReservationRepository reservations;
    @Mock private ReservationRollbackRepository rollbacks;
    @Mock private CardServiceSettings settings;
    @Mock private CardServiceDefaults defaults;
    @Mock private PanGenerator generator;
    @Mock private CardEventNotifier events;
    @Mock private BinIssuerService issuers;
    @Mock private RetryService retries;
    @Mock private TransactionRunner transactions;
    private CardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(cards, reservations, rollbacks, settings, defaults,
                generator, events, issuers, retries, transactions);
    }

    @Test
    void shouldReturnCardWhenPanExists() {
        // Arrange
        Card expected = activeCard();
        when(cards.findByPan(PAN)).thenReturn(Optional.of(expected));
        // Act
        Card result = service.getCard(PAN);
        // Assert
        assertThat(result).isSameAs(expected);
    }

    @Test
    void shouldThrowWhenPanDoesNotExist() {
        // Arrange
        when(cards.findByPan(PAN)).thenReturn(Optional.empty());
        // Act / Assert
        assertThatThrownBy(() -> service.getCard(PAN)).isInstanceOf(CardNotFoundException.class);
    }

    @Test
    void shouldRejectWhenPageLimitExceedsMaximum() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);
        // Act / Assert
        assertThatThrownBy(() -> service.getCards(101, null, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class);
    }

    @Test
    void shouldReserveWhenCardHasEnoughFunds() {
        // Arrange
        executeTransactionCallbacks();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservations.isUnique("123456789012", PAN)).thenReturn(true);
        when(reservations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cards.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // Act
        Card result = service.reserve(PAN, new BigDecimal("40"), "123456789012");
        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo("60");
        verify(reservations).save(argThat(r -> r.pan().equals(PAN)
                && r.reservationAmount().compareTo(new BigDecimal("40")) == 0));
        verify(cards).update(result);
        verify(events).notifyListeners(any(CardEvent.class));
    }

    @Test
    void shouldRejectWhenReservationAlreadyExists() {
        // Arrange
        executeTransactionCallbacks();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservations.isUnique("123456789012", PAN)).thenReturn(false);
        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, BigDecimal.TEN, "123456789012"))
                .isInstanceOf(ReservationAlreadyExistsException.class);
        verify(reservations, never()).save(any());
        verify(cards, never()).update(any());
    }

    @Test
    void shouldPreserveUnspecifiedFieldsWhenPatchingCard() {
        // Arrange
        executeTransactionCallbacks();
        Card original = activeCard();
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(original));
        when(cards.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // Act
        Card result = service.patchCard(PAN, CardStatus.BLOCKED, null, null, null);
        // Assert
        assertThat(result.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(result.dailyLimit()).isEqualTo(original.dailyLimit());
        assertThat(result.monthlyLimit()).isEqualTo(original.monthlyLimit());
        assertThat(result.availableBalance()).isEqualTo(original.availableBalance());
        verify(cards).update(result);
        verify(events).notifyListeners(any(CardEvent.class));
    }

    private void executeTransactionCallbacks() {
        when(transactions.runSupplier(any())).thenAnswer(invocation ->
                invocation.<Supplier<?>>getArgument(0).get());
    }

    private Card activeCard() {
        return new Card(new UUID(0, 1), PAN, "400000", "IVAN IVANOV", YearMonth.of(2027, 12),
                CardStatus.ACTIVE, "643", new BigDecimal("1000"), new BigDecimal("5000"),
                new BigDecimal("100"), "ISS001", Instant.parse("2026-01-01T00:00:00Z"));
    }
}
