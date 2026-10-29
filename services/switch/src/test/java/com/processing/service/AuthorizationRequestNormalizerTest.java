package com.processing.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {
    @ParameterizedTest
    @CsvSource(value = {"NULL|NULL", "''|''", "' '| ' '", "TERM|TERM0000",
            "TERM0001|TERM0001", "TERM000123|TERM0001"}, delimiter = '|', nullValues = "NULL")
    void shouldNormalizeTerminalWhenLengthVaries(String input, String expected) {
        // Arrange: input and expected value are supplied by the table.
        // Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);
        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL|NULL", "''|''", "' '| ' '", "SHOP|SHOP00000000000",
            "MERCH1234567890|MERCH1234567890", "MERCH01234567890|MERCH1234567890",
            "MERCH12345678901|MERCH1234567890", "OTHER12345678901|OTHER1234567890",
            "ABCDEFGHIJKLMNOPQ|ABCDEFGHIJKLMNO"},
            delimiter = '|', nullValues = "NULL")
    void shouldNormalizeMerchantWhenFormatVaries(String input, String expected) {
        // Arrange: input and expected value are supplied by the table.
        // Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);
        // Assert
        assertThat(result).isEqualTo(expected);
    }
}
