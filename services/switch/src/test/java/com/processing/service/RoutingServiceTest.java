package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RoutingServiceTest {
    private final RoutingService service = new RoutingService(new SwitchProperties(
            "test", Map.of("400000", "ISS001", "500000", "ISS002"), null, null, null, null, null, null));

    @ParameterizedTest
    @CsvSource({"400000, ISS001", "4000001234567890, ISS001", "5000001234567890, ISS002"})
    void shouldResolveIssuerWhenBinIsKnown(String pan, String issuer) {
        // Arrange: the routing table contains two issuers.
        // Act
        String result = service.getIssuerIdByPan(pan);
        // Assert
        assertThat(result).isEqualTo(issuer);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "9999991234567890"})
    void shouldRejectWhenPanHasNoKnownBin(String pan) {
        // Arrange: PAN is missing, too short or unknown.
        // Act / Assert
        assertThatThrownBy(() -> service.getIssuerIdByPan(pan)).isInstanceOf(UnknownBinException.class);
    }
}
