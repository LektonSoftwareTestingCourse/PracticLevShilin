package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class TransactionRequestValidatorTest {
    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "1000000"})
    void shouldAcceptWhenRequestIsValid(BigDecimal amount) {
        // Arrange
        var request = request("0100", "4000001234567890", amount, "643", "5045");
        // Act / Assert
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void shouldRejectWhenFieldViolatesContract(AuthorizationRequest request, String field) {
        // Arrange: a single invalid field is supplied by each table row.
        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class).hasMessageContaining(field);
    }

    @Test
    void shouldRejectWhenBodyIsNull() {
        // Arrange
        AuthorizationRequest request = null;
        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class).hasMessage("Request body is required");
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(request(null, "4000001234567890", BigDecimal.TEN, "643", "5045"), "mti"),
                Arguments.of(request(" ", "4000001234567890", BigDecimal.TEN, "643", "5045"), "mti"),
                Arguments.of(request("0200", "4000001234567890", BigDecimal.TEN, "643", "5045"), "mti"),
                Arguments.of(request("0100", null, BigDecimal.TEN, "643", "5045"), "pan"),
                Arguments.of(request("0100", "", BigDecimal.TEN, "643", "5045"), "pan"),
                Arguments.of(request("0100", "400000123456789", BigDecimal.TEN, "643", "5045"), "pan"),
                Arguments.of(request("0100", "40000012345678901", BigDecimal.TEN, "643", "5045"), "pan"),
                Arguments.of(request("0100", "400000123456789X", BigDecimal.TEN, "643", "5045"), "pan"),
                Arguments.of(request("0100", "4000001234567890", null, "643", "5045"), "amount"),
                Arguments.of(request("0100", "4000001234567890", BigDecimal.ZERO, "643", "5045"), "amount"),
                Arguments.of(request("0100", "4000001234567890", new BigDecimal("-0.01"), "643", "5045"), "amount"),
                Arguments.of(request("0100", "4000001234567890", BigDecimal.TEN, "64", "5045"), "currencyCode"),
                Arguments.of(request("0100", "4000001234567890", BigDecimal.TEN, "6430", "5045"), "currencyCode"),
                Arguments.of(request("0100", "4000001234567890", BigDecimal.TEN, "643", "504"), "mcc"),
                Arguments.of(request("0100", "4000001234567890", BigDecimal.TEN, "643", "50450"), "mcc"),
                Arguments.of(request("0100", "4000001234567890", BigDecimal.TEN, "643", "ABCD"), "mcc"));
    }

    private static AuthorizationRequest request(String mti, String pan, BigDecimal amount, String currency, String mcc) {
        return new AuthorizationRequest(mti, "000001", pan, "000000", amount, currency,
                Instant.parse("2026-01-01T00:00:00Z"), "TERM0001", "POS", "MERCH00000000002", mcc, "ACQ001", null);
    }
}
