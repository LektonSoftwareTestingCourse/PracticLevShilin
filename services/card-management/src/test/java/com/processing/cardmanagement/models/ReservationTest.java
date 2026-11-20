package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ReservationTest {
    @Test
    void shouldCreateRefundWhenReservationIsActive() {
        // Arrange
        Reservation reservation = reservation(ReservationStatus.RESERVED);
        // Act
        ReservationRollback result = reservation.startRollback(BigDecimal.TEN);
        // Assert
        assertThat(result.reservationId()).isEqualTo(reservation.id());
        assertThat(result.pan()).isEqualTo(reservation.pan());
        assertThat(result.rrn()).isEqualTo(reservation.rrn());
        assertThat(result.rollbackAmount()).isEqualByComparingTo("10");
    }

    @Test
    void shouldRejectWhenReservationWasAlreadyRolledBack() {
        // Arrange
        Reservation reservation = reservation(ReservationStatus.ROLLED_BACK);
        // Act / Assert
        assertThatThrownBy(() -> reservation.startRollback(BigDecimal.TEN))
                .isInstanceOf(RollbackAlreadySatisfiedException.class);
    }

    @Test
    void shouldMarkRolledBackWhenRefundBelongsToReservation() {
        // Arrange
        Reservation reservation = reservation(ReservationStatus.RESERVED);
        ReservationRollback refund = reservation.startRollback(BigDecimal.TEN);
        // Act
        Reservation result = reservation.rolledBack(refund);
        // Assert
        assertThat(result.status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(result.id()).isEqualTo(reservation.id());
    }

    private Reservation reservation(ReservationStatus status) {
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        return new Reservation(new UUID(0, 1), "4000001234567890", BigDecimal.TEN,
                "123456789012", status, time, time);
    }
}
