package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CardTest {
    @ParameterizedTest
    @CsvSource({"0.01,99.99", "99.99,0.01", "100,0"})
    void shouldDecreaseBalanceWhenReservationBelongsToCard(BigDecimal amount, BigDecimal expected) {
        // Arrange
        Card card = card(CardStatus.ACTIVE);
        Reservation reservation = new Reservation(card.pan(), amount, "123456789012");
        // Act
        Card result = card.withReservation(reservation);
        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo(expected);
        assertThat(card.availableBalance()).isEqualByComparingTo("100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"100.01", "101"})
    void shouldRejectWhenAmountExceedsBalance(BigDecimal amount) {
        // Arrange
        Card card = card(CardStatus.ACTIVE);
        // Act / Assert
        assertThatThrownBy(() -> card.startReservation(amount, "123456789012"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldRejectReservationWhenCardIsNotActive(CardStatus status) {
        // Arrange
        Card card = card(status);
        // Act / Assert
        assertThatThrownBy(() -> card.startReservation(BigDecimal.TEN, "123456789012"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1,100", "100,-1", "101,100"})
    void shouldRejectWhenLimitsAreInvalid(BigDecimal daily, BigDecimal monthly) {
        // Arrange
        Card card = card(CardStatus.ACTIVE);
        // Act / Assert
        assertThatThrownBy(() -> card.withData(CardStatus.ACTIVE, daily, monthly, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "100,100", "100,101"})
    void shouldAcceptWhenLimitsAreNonNegativeAndOrdered(BigDecimal daily, BigDecimal monthly) {
        // Arrange
        Card card = card(CardStatus.ACTIVE);
        // Act
        Card result = card.withData(CardStatus.ACTIVE, daily, monthly, BigDecimal.TEN);
        // Assert
        assertThat(result.dailyLimit()).isEqualByComparingTo(daily);
        assertThat(result.monthlyLimit()).isEqualByComparingTo(monthly);
    }

    private Card card(CardStatus status) {
        return new Card(new UUID(0, 1), "4000001234567890", "400000", "IVAN IVANOV",
                YearMonth.of(2027, 12), status, "643", new BigDecimal("1000"), new BigDecimal("5000"),
                new BigDecimal("100"), "ISS001", Instant.parse("2026-01-01T00:00:00Z"));
    }
}
